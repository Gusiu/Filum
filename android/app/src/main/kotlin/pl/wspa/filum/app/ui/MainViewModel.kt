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
import pl.wspa.filum.app.VerificationResult
import pl.wspa.filum.app.work.SyncScheduler
import pl.wspa.filum.data.LogEventEntity
import pl.wspa.filum.data.PairedServerEntity
import pl.wspa.filum.data.Schedule
import pl.wspa.filum.protocol.IntegrityPolicy
import pl.wspa.filum.data.ExclusionEntity
import pl.wspa.filum.data.SettingsEntity
import pl.wspa.filum.data.SyncSessionEntity
import pl.wspa.filum.scan.Environment
import pl.wspa.filum.scan.ExclusionRules
import java.io.File

enum class Screen { HOME, PAIRING, LOG, FOLDERS }

data class UiState(
    val screen: Screen = Screen.HOME,
    val server: PairedServerEntity? = null,
    val settings: SettingsEntity = SettingsEntity(),
    val hasStorageAccess: Boolean = false,
    val pairing: Boolean = false,
    val pendingFiles: Int = 0,
    val lastSession: SyncSessionEntity? = null,
    val lastError: String? = null,
    val verifying: Boolean = false,
    val verification: String? = null,
    val folders: List<FolderChoice> = emptyList(),
    val foldersLoading: Boolean = false,
)

class MainViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    val log: StateFlow<List<LogEventEntity>> =
        container.database.log().observeRecent()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Whether a cycle is running. The state comes from WorkManager, not from a field in this
     * class: the work outlives the screen, so we ask about it where it actually lives.
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
     * A manual cycle takes the same road as a nightly one - through WorkManager.
     *
     * A separate "right now" path would mean a second implementation of the same thing, tested
     * incidentally, and a certainty that sooner or later one of them would start behaving
     * differently.
     */
    fun syncNow() {
        SyncScheduler.runNow(container.context)
    }

    /**
     * Checks a sample of files (§5.5). Only on the user's request: the server hashes its own
     * copies while doing it, so the operation is expensive on both sides.
     */
    fun verify() {
        if (_state.value.verifying) return

        viewModelScope.launch {
            _state.value = _state.value.copy(verifying = true, verification = null)

            val result = withContext(Dispatchers.IO) { container.verification.verifySample() }

            _state.value = _state.value.copy(
                verifying = false,
                verification = when (result) {
                    is VerificationResult.Done -> when {
                        result.corrupted.isNotEmpty() ->
                            "Uszkodzone kopie: ${result.corrupted.size} z ${result.checked}. " +
                                "They will be re-sent at the next full sync."

                        result.missing.isNotEmpty() ->
                            "${result.missing.size} of ${result.checked} files are missing; " +
                                "they will arrive at the next sync."

                        else -> "Checked ${result.checked} files - all matching."
                    }

                    is VerificationResult.Failed -> "The check could not be run: ${result.reason}"
                },
            )
        }
    }

    /**
     * Loads the top-level directories along with the current exclusions.
     *
     * The item count comes from a single `list()` call, not from walking the tree: the screen
     * is to open at once, and nobody needs the exact number of files in Download to make a
     * decision.
     */
    fun openFolders() {
        _state.value = _state.value.copy(screen = Screen.FOLDERS, foldersLoading = true)

        viewModelScope.launch {
            val folders = withContext(Dispatchers.IO) { readFolders() }
            _state.value = _state.value.copy(folders = folders, foldersLoading = false)
        }
    }

    fun setExcluded(path: String, excluded: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val dao = container.database.exclusions()
                if (excluded) dao.save(ExclusionEntity(path)) else dao.delete(ExclusionEntity(path))
            }

            _state.value = _state.value.copy(
                folders = _state.value.folders.map {
                    if (it.path == path) it.copy(excluded = excluded) else it
                },
            )
        }
    }

    private suspend fun readFolders(): List<FolderChoice> {
        val excluded = container.database.exclusions().active().map { it.pathPrefix }.toSet()
        val root = File(Environment.externalStorageRootPath())

        val directories = root.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.sortedBy { it.name.lowercase() }
            ?: return emptyList()

        return directories.map { directory ->
            FolderChoice(
                path = directory.name,
                excluded = directory.name in excluded,
                fileCount = directory.list()?.size ?: -1,
                suggested = directory.name in ExclusionRules.SUGGESTED_FOR_EXCLUSION,
            )
        }
    }

    fun setIntegrityPolicy(policy: IntegrityPolicy) {
        viewModelScope.launch {
            val updated = _state.value.settings.copy(integrityPolicy = policy)
            withContext(Dispatchers.IO) { container.database.settings().save(updated) }
            _state.value = _state.value.copy(settings = updated)
        }
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
        "Last run: ${session.filesDone} files, ${formatBytes(session.bytesSent)}."

    session.result?.startsWith("interrupted") == true ->
        "Przerwano po ${session.filesDone} plikach, ${formatBytes(session.bytesSent)}."

    else -> "The last attempt did not succeed."
}
