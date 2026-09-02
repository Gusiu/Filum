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

/** A directory shown on the selection screen, along with whether the user excluded it. */
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
            TextButton(onClick = onBack) { Text("Back") }
        }

        Text(
            "Tick the directories you do not want in the backup. " +
                "Files already sent stay on the computer — an exclusion applies to what " +
                "goes from here on.",
            style = MaterialTheme.typography.bodyMedium,
        )

        if (loading) {
            Text("Czytam katalogi…", style = MaterialTheme.typography.bodyMedium)
        } else if (folders.isEmpty()) {
            Text("The storage directories could not be read.", style = MaterialTheme.typography.bodyMedium)
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
                                    if (folder.fileCount >= 0) "${folder.fileCount} items"
                                    else "unavailable",
                                )
                                if (folder.suggested) append(" — often worth skipping")
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
                    "Android/data and Android/obb have been inaccessible to applications since " +
                        "Android 11 and we do not try to work around that. Thumbnails, trash and " +
                        "caches are skipped too — that data is reproducible.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Android/media stays in the backup: that is where WhatsApp, among others, " +
                        "WhatsApp.",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
