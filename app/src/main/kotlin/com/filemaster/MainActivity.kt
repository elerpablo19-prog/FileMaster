package com.filemaster

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.filemaster.navigation.FileMasterNavGraph
import com.filemaster.permissions.StoragePermissionState
import com.filemaster.ui.theme.FileMasterTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Activity raiz. Responsabilidades:
 * 1. Orquestrar o fluxo de permissões de armazenamento (MAES / runtime legacy);
 * 2. Instalar o Compose root com o tema e o NavGraph.
 *
 * Toda a lógica de arquivos fica no core Rust; a Activity nunca faz I/O.
 */
class MainActivity : ComponentActivity() {

    private val permissionState = MutableStateFlow<StoragePermissionState?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as FileMasterApplication).container
        val handler = container.permissionHandler

        lifecycleScope.launch {
            permissionState.emit(handler.currentStatus(this@MainActivity))
        }

        setContent {
            FileMasterTheme {
                val status by permissionState.collectAsState()
                when (status) {
                    StoragePermissionState.Granted, null -> {
                        Box(Modifier.fillMaxSize()) {
                            FileMasterNavGraph(container)
                        }
                    }
                    StoragePermissionState.NeedsAllFilesAccess -> AllFilesAccessGate(
                        onGrant = {
                            startActivity(handler.allFilesAccessIntent(this))
                        },
                    )
                    StoragePermissionState.NeedsLegacyRuntimeGrant -> LegacyPermissionGate(
                        onGrant = {
                            requestPermissions(
                                arrayOf(
                                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                ), 1001
                            )
                        },
                    )
                }
            }
        }
    }

    /** Reavalia o status quando o usuário volta da tela de Settings. */
    override fun onResume() {
        super.onResume()
        val handler = (application as FileMasterApplication).container.permissionHandler
        lifecycleScope.launch {
            permissionState.emit(handler.currentStatus(this@MainActivity))
        }
    }
}

@Composable
private fun AllFilesAccessGate(onGrant: () -> Unit) {
    PermissionGate(
        title = "Acesso a todos os arquivos",
        body = "O FileMaster precisa do acesso completo ao armazenamento para listar, " +
            "copiar e organizar seus arquivos. Você será levado às configurações do sistema.",
        buttonLabel = "Conceder acesso",
        onGrant = onGrant,
    )
}

@Composable
private fun LegacyPermissionGate(onGrant: () -> Unit) {
    PermissionGate(
        title = "Permissão de armazenamento",
        body = "O FileMaster precisa de permissão de leitura e escrita para gerenciar seus arquivos.",
        buttonLabel = "Conceder permissão",
        onGrant = onGrant,
    )
}

@Composable
private fun PermissionGate(title: String, body: String, buttonLabel: String, onGrant: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.layout.Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(32.dp),
            ) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.padding(8.dp))
                Text(body, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.padding(16.dp))
                Button(onClick = onGrant) {
                    Text(buttonLabel)
                }
            }
    }
}
