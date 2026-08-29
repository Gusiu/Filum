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
import pl.wspa.filum.app.CycleResult
import pl.wspa.filum.app.PairingResult
import pl.wspa.filum.data.LogEventEntity
import pl.wspa.filum.data.PairedServerEntity
import pl.wspa.filum.scan.Environment
import pl.wspa.filum.sync.SyncOutcome

enum class Screen { HOME, PAIRING, LOG }

data class UiState(
    val screen: Screen = Screen.HOME,
    val server: PairedServerEntity? = null,
    val hasStorageAccess: Boolean = false,
    val busy: Boolean = false,
    val statusLine: String = "",
    val lastError: String? = null,
    val pendingFiles: Int = 0,
)

class MainViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    val log: StateFlow<List<LogEventEntity>> =
        container.database.log().observeRecent()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val server = withContext(Dispatchers.IO) { container.database.pairedServers().get() }
            val pending = withContext(Dispatchers.IO) { container.queue.pendingCount() }

            _state.value = _state.value.copy(
                server = server,
                hasStorageAccess = Environment.hasFullStorageAccess(),
                pendingFiles = pending,
                screen = if (server == null) Screen.PAIRING else _state.value.screen,
            )
        }
    }

    fun show(screen: Screen) {
        _state.value = _state.value.copy(screen = screen, lastError = null)
    }

    fun pair(code: String, label: String) {
        if (_state.value.busy) return

        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, lastError = null, statusLine = "Paruję…")

            val result = withContext(Dispatchers.IO) { container.pairing.pair(code, label) }

            _state.value = when (result) {
                is PairingResult.Paired -> _state.value.copy(
                    busy = false,
                    screen = Screen.HOME,
                    statusLine = "Sparowano z ${result.label} przez ${result.endpoint}.",
                )

                is PairingResult.Rejected -> _state.value.copy(
                    busy = false,
                    lastError = result.message,
                    statusLine = "",
                )
            }
            refresh()
        }
    }

    fun syncNow() {
        if (_state.value.busy) return

        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, lastError = null, statusLine = "Szukam serwera…")

            val result = withContext(Dispatchers.IO) { container.syncRunner.runOnce() }

            _state.value = _state.value.copy(busy = false, statusLine = describe(result), lastError = errorOf(result))
            refresh()
        }
    }

    fun unpair() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { container.pairing.unpair() }
            _state.value = UiState(screen = Screen.PAIRING)
            refresh()
        }
    }

    private fun describe(result: CycleResult): String = when (result) {
        is CycleResult.Done -> when (val outcome = result.outcome) {
            is SyncOutcome.Completed ->
                "Gotowe: ${outcome.filesSent} plików, ${formatBytes(outcome.bytesSent)} przez ${result.endpoint}."

            is SyncOutcome.Interrupted ->
                "Przerwano (${outcome.reason}) po ${outcome.filesSent} plikach, ${formatBytes(outcome.bytesSent)}."

            is SyncOutcome.Failed -> "Nie udało się: ${outcome.reason}"
        }

        is CycleResult.NotPaired -> result.message
        is CycleResult.NoRoute -> "Nie znaleziono serwera."
    }

    private fun errorOf(result: CycleResult): String? = when (result) {
        // Powody dla poszczególnych tras są tu najbardziej wartościowe: pokazują,
        // czy serwer jest wyłączony, czy tylko zmienił adres w sieci lokalnej.
        is CycleResult.NoRoute -> result.failures.joinToString("\n")
        is CycleResult.Done -> (result.outcome as? SyncOutcome.Failed)?.reason
        is CycleResult.NotPaired -> null
    }

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
