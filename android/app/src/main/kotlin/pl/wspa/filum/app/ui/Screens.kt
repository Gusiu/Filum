package pl.wspa.filum.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
            enabled = !state.pairing && code.isNotBlank() && label.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.pairing) "Paruję…" else "Sparuj")
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
