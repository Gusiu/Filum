package pl.wspa.filum.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** Katalog widoczny na ekranie wyboru, wraz z tym, czy użytkownik go wykluczył. */
data class FolderChoice(
    val path: String,
    val excluded: Boolean,
    val fileCount: Int,
    val suggested: Boolean,
)

@Composable
fun FoldersScreen(
    folders: List<FolderChoice>,
    loading: Boolean,
    onToggle: (String, Boolean) -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Foldery", style = MaterialTheme.typography.headlineMedium)
            TextButton(onClick = onBack) { Text("Wróć") }
        }

        Text(
            "Zaznacz katalogi, których nie chcesz w kopii zapasowej. " +
                "Pliki już wysłane zostają na komputerze — wykluczenie dotyczy tego, " +
                "co pójdzie dalej.",
            style = MaterialTheme.typography.bodyMedium,
        )

        if (loading) {
            Text("Czytam katalogi…", style = MaterialTheme.typography.bodyMedium)
        } else if (folders.isEmpty()) {
            Text("Nie udało się odczytać katalogów pamięci.", style = MaterialTheme.typography.bodyMedium)
        } else {
            folders.forEach { folder ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = folder.excluded,
                        onCheckedChange = { onToggle(folder.path, it) },
                    )
                    Column(Modifier.fillMaxWidth()) {
                        Text(folder.path, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            buildString {
                                append(
                                    if (folder.fileCount >= 0) "${folder.fileCount} elementów"
                                    else "niedostępny",
                                )
                                if (folder.suggested) append(" — często warto pominąć")
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        HorizontalDivider()

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Zawsze pomijane", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Android/data i Android/obb są od Androida 11 niedostępne dla aplikacji " +
                        "i nie próbujemy tego obchodzić. Pomijamy też miniatury, kosz i pamięci " +
                        "podręczne — to dane odtwarzalne.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Android/media pozostaje w kopii: tam trzyma swoje files między innymi " +
                        "WhatsApp.",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
