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
fun PairingScreen(
    state: UiState,
    onPair: (code: String, label: String) -> Unit,
) {
    var code by rememberSaveable { mutableStateOf("") }
    var label by rememberSaveable { mutableStateOf(android.os.Build.MODEL ?: "Phone") }
    var scanning by rememberSaveable { mutableStateOf(false) }

    if (scanning) {
        // A scanned code only fills in the field: pairing still waits for the Pair button,
        // so the user sees what was read before the phone acts on it.
        QrScanner(
            onCode = { scanned ->
                code = scanned
                scanning = false
            },
            onCancel = { scanning = false },
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Pairing", style = MaterialTheme.typography.headlineMedium)
        Text(
            "On the computer, open the Filum panel and get a pairing code, " +
                "then scan it or paste it below. The code is valid for 10 minutes and works once.",
            style = MaterialTheme.typography.bodyMedium,
        )

        OutlinedButton(onClick = { scanning = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Scan the QR code")
        }

        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            label = { Text("Pairing code (filum1:…)") },
            singleLine = false,
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = label,
            onValueChange = { label = it },
            label = { Text("This phone's name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = { onPair(code.trim(), label.trim()) },
            enabled = !state.pairing && code.isNotBlank() && label.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.pairing) "Pairing…" else "Pair")
        }

        state.lastError?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }

        Text(
            "The phone will generate its own key and store it in the device's security " +
                "module - a separate chip where one is available, and the processor's trusted " +
                "environment where it is not. The private key leaves the device in neither " +
                "case. The level actually used is recorded " +
                "in the journal at pairing time.",
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
            Text("Journal", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) { Text("Back") }
        }

        if (events.isEmpty()) {
            Text("No events.", style = MaterialTheme.typography.bodyMedium)
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
