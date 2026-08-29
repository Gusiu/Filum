package pl.wspa.filum.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import pl.wspa.filum.data.Schedule

@Composable
fun HomeScreen(
    state: UiState,
    running: Boolean,
    onSyncNow: () -> Unit,
    onVerify: () -> Unit,
    onScheduleChange: (Schedule) -> Unit,
    onOpenLog: () -> Unit,
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
            "Kopia zapasowa telefonu na komputer. Serwer nigdy niczego nie usuwa.",
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
                    Text("W kolejce: ${state.pendingFiles} plików", style = MaterialTheme.typography.bodyMedium)
                    Text(describe(state.lastSession), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        if (!state.hasStorageAccess) {
            // Bez pełnego dostępu skan zobaczy wyłącznie katalogi mediów, więc
            // dokumenty i pobrane pliki nigdy nie trafiłyby do kopii (ADR-013).
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Brak dostępu do plików", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Filum potrzebuje dostępu do całej pamięci, żeby objąć kopią także " +
                            "dokumenty i pobrane pliki, a nie tylko zdjęcia.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = onRequestStorageAccess) { Text("Nadaj dostęp") }
                }
            }
        }

        ScheduleSelector(state.settings.schedule, onScheduleChange)

        Button(
            onClick = onSyncNow,
            enabled = !running && state.server != null && state.hasStorageAccess,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (running) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("  Pracuję…")
            } else {
                Text("Synchronizuj teraz")
            }
        }

        state.lastError?.let { error ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Szczegóły", style = MaterialTheme.typography.titleSmall)
                    Text(error, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }

        HorizontalDivider()

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Kontrola integralności", style = MaterialTheme.typography.titleSmall)
            Text(
                "Sprawdza, czy kopie na komputerze nadal odpowiadają plikom w telefonie. " +
                    "Losowa próbka, uruchamiana ręcznie — pełne sprawdzenie 200 GB " +
                    "oznaczałoby odczyt całej pamięci po obu stronach.",
                style = MaterialTheme.typography.bodySmall,
            )

            OutlinedButton(onClick = onVerify, enabled = !state.verifying && state.server != null) {
                Text(if (state.verifying) "Sprawdzam…" else "Sprawdź próbkę")
            }

            state.verification?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }

        HorizontalDivider()

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                Schedule.MANUAL -> "Kopia powstaje tylko po naciśnięciu przycisku."
                Schedule.DAILY, Schedule.WEEKLY ->
                    "Cykl automatyczny rusza przy ładowaniu i na sieci bez limitu danych. " +
                        "Kończy się sam przed wyczerpaniem dobowego limitu pracy w tle."
            },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun label(schedule: Schedule): String = when (schedule) {
    Schedule.MANUAL -> "Ręcznie"
    Schedule.DAILY -> "Codziennie"
    Schedule.WEEKLY -> "Co tydzień"
}
