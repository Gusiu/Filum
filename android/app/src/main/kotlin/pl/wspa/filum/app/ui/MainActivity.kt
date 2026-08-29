package pl.wspa.filum.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import pl.wspa.filum.app.FilumApplication

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as FilumApplication).container

        setContent {
            MaterialTheme {
                val model: MainViewModel = viewModel(factory = MainViewModel.Factory(container))
                val state by model.state.collectAsState()
                val log by model.log.collectAsState()

                // Uprawnienie do całej pamięci nadaje się w ustawieniach systemu,
                // poza aplikacją, więc po powrocie trzeba odczytać jego stan na nowo.
                LaunchedEffect(Unit) { model.refresh() }

                Scaffold { insets ->
                    Box(Modifier.padding(insets)) {
                        when (state.screen) {
                            Screen.PAIRING -> PairingScreen(
                                state = state,
                                onPair = model::pair,
                            )

                            Screen.HOME -> HomeScreen(
                                state = state,
                                onSyncNow = model::syncNow,
                                onOpenLog = { model.show(Screen.LOG) },
                                onRequestStorageAccess = ::openStorageAccessSettings,
                                onUnpair = model::unpair,
                            )

                            Screen.LOG -> LogScreen(
                                events = log,
                                onBack = { model.show(Screen.HOME) },
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * MANAGE_EXTERNAL_STORAGE nie jest zwykłym uprawnieniem czasu wykonania — użytkownik
     * nadaje je w osobnym ekranie ustawień systemu (ADR-013).
     */
    private fun openStorageAccessSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        runCatching { startActivity(intent) }
            .onFailure { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
    }
}
