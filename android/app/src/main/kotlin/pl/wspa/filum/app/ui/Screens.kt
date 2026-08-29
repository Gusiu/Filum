package pl.wspa.filum.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import pl.wspa.filum.data.LogEventEntity
import pl.wspa.filum.data.LogLevel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    state: UiState,
    onSyncNow: () -> Unit,
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

        Button(
            onClick = onSyncNow,
            enabled = !state.busy && state.server != null && state.hasStorageAccess,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.busy) {
                CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.height(8.dp))
            }
            Text(if (state.busy) "Pracuję…" else "Synchronizuj teraz")
        }

        if (state.statusLine.isNotEmpty()) {
            Text(state.statusLine, style = MaterialTheme.typography.bodyMedium)
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

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onOpenLog) { Text("Dziennik") }
            TextButton(onClick = onUnpair) { Text("Rozparuj") }
        }
    }
}

@Composable
fun PairingScreen(
    state: UiState,
    onPair: (code: String, label: String) -> Unit,
) {
    var code by rememberSaveable { mutableStateOf("") }
    var label by rememberSaveable { mutableStateOf(android.os.Build.MODEL ?: "Telefon") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Parowanie", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Na komputerze otwórz panel Filum i pobierz kod parowania, " +
                "a następnie wklej go poniżej. Kod jest ważny 10 minut i działa raz.",
            style = MaterialTheme.typography.bodyMedium,
        )

        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            label = { Text("Kod parowania (filum1:…)") },
            singleLine = false,
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = label,
            onValueChange = { label = it },
            label = { Text("Nazwa tego telefonu") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = { onPair(code.trim(), label.trim()) },
            enabled = !state.busy && code.isNotBlank() && label.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.busy) "Paruję…" else "Sparuj")
        }

        state.lastError?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }

        Text(
            "Telefon wygeneruje własny klucz w bezpiecznym module urządzenia. " +
                "Klucz prywatny nigdy go nie opuszcza.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
fun LogScreen(events: List<LogEventEntity>, onBack: () -> Unit) {
    val format = remember { SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault()) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Dziennik", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) { Text("Wróć") }
        }

        if (events.isEmpty()) {
            Text("Brak zdarzeń.", style = MaterialTheme.typography.bodyMedium)
            return
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(events, key = { it.id }) { event ->
                Column {
                    Text(
                        "${format.format(Date(event.at))}  ${event.code}",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        color = when (event.level) {
                            LogLevel.ERROR -> MaterialTheme.colorScheme.error
                            LogLevel.WARN -> MaterialTheme.colorScheme.tertiary
                            LogLevel.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Text(event.message, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
