package pl.wspa.filum.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import pl.wspa.filum.app.work.SyncActivity
import pl.wspa.filum.data.Schedule
import pl.wspa.filum.protocol.IntegrityPolicy

@Composable
fun HomeScreen(
    state: UiState,
    activity: SyncActivity,
    onSyncNow: () -> Unit,
    onVerify: () -> Unit,
    onScheduleChange: (Schedule) -> Unit,
    onIntegrityPolicyChange: (IntegrityPolicy) -> Unit,
    onOpenLog: () -> Unit,
    onOpenFolders: () -> Unit,
    onRequestStorageAccess: () -> Unit,
    onUnpair: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Filum", style = MaterialTheme.typography.headlineMedium)
        Text(
            "A backup of this phone on your computer. The server never deletes anything.",
            style = MaterialTheme.typography.bodyMedium,
        )

        state.server?.let { server ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(server.label, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Klucz serwera: ${server.serverPin.take(12)}…",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text("Queued: ${state.pendingFiles} files", style = MaterialTheme.typography.bodyMedium)
                    Text(describe(state.lastSession), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        if (!state.hasStorageAccess) {
            // Without full access the scan sees only the media directories, so documents
            // and downloads would never reach the backup (ADR-013).
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("No access to files", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Filum needs access to all storage so the backup can cover documents " +
                            "and downloads too, not only photos.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = onRequestStorageAccess) { Text("Grant access") }
                }
            }
        }

        ScheduleSelector(state.settings.schedule, onScheduleChange)

        val running = activity == SyncActivity.Running

        Button(
            onClick = onSyncNow,
            enabled = !running && state.server != null && state.hasStorageAccess,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (running) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("  Working…")
            } else {
                Text("Sync now")
            }
        }

        if (activity is SyncActivity.RetryScheduled) {
            // Without this line a failed cycle is invisible until the journal is opened.
            Text(
                "The last attempt did not reach the server. " +
                    (activity.atMillis?.let { "Next try at ${formatTime(it)}. " } ?: "") +
                    "Details are in the journal.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        state.lastError?.let { error ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Details", style = MaterialTheme.typography.titleSmall)
                    Text(error, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }

        HorizontalDivider()

        IntegrityPolicySelector(state.settings.integrityPolicy, onIntegrityPolicyChange)

        HorizontalDivider()

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Integrity check", style = MaterialTheme.typography.titleSmall)
            Text(
                "Checks whether the copies on the computer still match the files on the phone. " +
                    "A random sample, started by hand - checking the whole library would mean " +
                    "reading all the storage on both sides.",
                style = MaterialTheme.typography.bodySmall,
            )

            OutlinedButton(onClick = onVerify, enabled = !state.verifying && state.server != null) {
                Text(if (state.verifying) "Checking…" else "Check a sample")
            }

            // The result appears below the button, which on a phone is often below the edge
            // of the screen. Without scrolling it into view the check seems to end silently.
            val result = remember { BringIntoViewRequester() }
            LaunchedEffect(state.verification) {
                if (state.verification != null) result.bringIntoView()
            }

            state.verification?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.bringIntoViewRequester(result),
                )
            }
        }

        HorizontalDivider()

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onOpenFolders) { Text("Foldery") }
            OutlinedButton(onClick = onOpenLog) { Text("Dziennik") }
            TextButton(onClick = onUnpair) { Text("Rozparuj") }
        }
    }
}

@Composable
private fun ScheduleSelector(current: Schedule, onChange: (Schedule) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Harmonogram", style = MaterialTheme.typography.titleSmall)

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.selectableGroup(),
        ) {
            Schedule.entries.forEach { schedule ->
                FilterChip(
                    selected = schedule == current,
                    onClick = { onChange(schedule) },
                    label = { Text(label(schedule)) },
                )
            }
        }

        Text(
            when (current) {
                Schedule.MANUAL -> "A backup is made only when you press the button."
                Schedule.DAILY, Schedule.WEEKLY ->
                    "The automatic cycle starts while charging and on an unmetered network. " +
                        "It stops by itself before the daily background-work limit runs out."
            },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * Choosing the change-detection policy (ADR-016).
 *
 * The description says outright what fast mode will miss. Without that, the switch would be
 * a choice between "fast" and "slow", which is not the difference that matters here.
 */
@Composable
private fun IntegrityPolicySelector(current: IntegrityPolicy, onChange: (IntegrityPolicy) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Wykrywanie zmian", style = MaterialTheme.typography.titleSmall)

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.selectableGroup(),
        ) {
            IntegrityPolicy.entries.forEach { policy ->
                FilterChip(
                    selected = policy == current,
                    onClick = { onChange(policy) },
                    label = { Text(label(policy)) },
                )
            }
        }

        Text(
            when (current) {
                IntegrityPolicy.FAST ->
                    "A file counts as changed when its size or modification time changed. " +
                        "The scan does not read file contents, so it is fast - but a substitution " +
                        "that preserved those two values will go unnoticed."

                IntegrityPolicy.THOROUGH ->
                    "Every file is hashed on every scan and compared against the server's " +
                        "record. This detects a substitution that preserved the metadata. " +
                        "It costs reading the whole library every cycle, so it takes longer " +
                        "and uses more battery."
            },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun label(policy: IntegrityPolicy): String = when (policy) {
    IntegrityPolicy.FAST -> "Szybkie"
    IntegrityPolicy.THOROUGH -> "Thorough"
}

private fun label(schedule: Schedule): String = when (schedule) {
    Schedule.MANUAL -> "Manual"
    Schedule.DAILY -> "Codziennie"
    Schedule.WEEKLY -> "Weekly"
}
