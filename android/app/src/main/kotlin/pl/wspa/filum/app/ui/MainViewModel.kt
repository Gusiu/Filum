package pl.wspa.filum.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.wspa.filum.app.AppContainer
import pl.wspa.filum.app.PairingResult
import pl.wspa.filum.app.work.SyncScheduler
import pl.wspa.filum.data.LogEventEntity
import pl.wspa.filum.data.PairedServerEntity
import pl.wspa.filum.data.Schedule
import pl.wspa.filum.data.SettingsEntity
import pl.wspa.filum.data.SyncSessionEntity
import pl.wspa.filum.scan.Environment

enum class Screen { HOME, PAIRING, LOG }

data class UiState(
    val screen: Screen = Screen.HOME,
    val server: PairedServerEntity? = null,
    val settings: SettingsEntity = SettingsEntity(),
    val hasStorageAccess: Boolean = false,
    val pairing: Boolean = false,
    val pendingFiles: Int = 0,
    val lastSession: SyncSessionEntity? = null,
    val lastError: String? = null,
)

class MainViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    val log: StateFlow<List<LogEventEntity>> =
        container.database.log().observeRecent()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Czy cykl właśnie trwa. Stan pochodzi z WorkManagera, a nie z pola w tej klasie:
     * praca przeżywa zamknięcie ekranu, więc pytamy o nią tam, gdzie naprawdę jest.
     */
    val running: StateFlow<Boolean> =
        SyncScheduler.observeRunning(container.context)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val (server, settings, pending, session) = withContext(Dispatchers.IO) {
                Snapshot(
                    server = container.database.pairedServers().get(),
                    settings = container.settings(),
                    pending = container.queue.pendingCount(),
                    session = container.database.sessions().latest(),
                )
            }

            _state.value = _state.value.copy(
                server = server,
                settings = settings,
                pendingFiles = pending,
                lastSession = session,
                hasStorageAccess = Environment.hasFullStorageAccess(),
                screen = if (server == null) Screen.PAIRING else _state.value.screen,
            )
        }
    }

    fun show(screen: Screen) {
        _state.value = _state.value.copy(screen = screen, lastError = null)
    }

    fun pair(code: String, label: String) {
        if (_state.value.pairing) return

        viewModelScope.launch {
            _state.value = _state.value.copy(pairing = true, lastError = null)

            val result = withContext(Dispatchers.IO) { container.pairing.pair(code, label) }

            _state.value = when (result) {
                is PairingResult.Paired -> _state.value.copy(pairing = false, screen = Screen.HOME)
                is PairingResult.Rejected -> _state.value.copy(pairing = false, lastError = result.message)
            }
            refresh()
        }
    }

    /**
     * Cykl ręczny idzie przez tę samą drogę co nocny — przez WorkManagera.
     *
     * Osobna ścieżka „na już" oznaczałaby drugą implementację tego samego, testowaną
     * przy okazji, i pewność, że któraś z nich prędzej czy później zacznie się
     * zachowywać inaczej.
     */
    fun syncNow() {
        SyncScheduler.runNow(container.context)
    }

    fun setSchedule(schedule: Schedule) {
        viewModelScope.launch {
            val updated = _state.value.settings.copy(schedule = schedule)
            withContext(Dispatchers.IO) {
                container.database.settings().save(updated)
                SyncScheduler.apply(container.context, updated)
            }
            _state.value = _state.value.copy(settings = updated)
        }
    }

    fun unpair() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                SyncScheduler.cancelAll(container.context)
                container.pairing.unpair()
            }
            _state.value = UiState(screen = Screen.PAIRING)
            refresh()
        }
    }

    private data class Snapshot(
        val server: PairedServerEntity?,
        val settings: SettingsEntity,
        val pending: Int,
        val session: SyncSessionEntity?,
    )

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(container) as T
    }
}

fun formatBytes(bytes: Long): String {
    val units = listOf("B", "kB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0

    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }

    return if (unit == 0) "$bytes B" else String.format("%.1f %s", value, units[unit])
}

fun describe(session: SyncSessionEntity?): String = when {
    session == null -> "Jeszcze nie synchronizowano."
    session.endedAt == null -> "Cykl w toku…"
    session.result?.startsWith("completed") == true ->
        "Ostatnio: ${session.filesDone} plików, ${formatBytes(session.bytesSent)}."

    session.result?.startsWith("interrupted") == true ->
        "Przerwano po ${session.filesDone} plikach, ${formatBytes(session.bytesSent)}."

    else -> "Ostatnia próba nie powiodła się."
}
