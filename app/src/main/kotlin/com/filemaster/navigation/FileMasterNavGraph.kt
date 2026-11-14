package com.filemaster.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.filemaster.di.AppContainer
import com.filemaster.ui.screens.files.FileListScreen
import com.filemaster.ui.screens.files.FileListViewModel
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Grafo de navegação. Cada nível de diretório empilha uma nova rota
 * "browser", de modo que o botão voltar do sistema sobe naturalmente
 * um nível — comportamento esperado em um file manager.
 *
 * O `path` é URL-encoded (campos de diretórios contêm '/', '?', '#').
 */
object Routes {
    const val BROWSER = "browser?path={path}"

    fun browser(path: String): String =
        "browser?path=${URLEncoder.encode(path, Charsets.UTF_8.name())}"
}

@Composable
fun FileMasterNavGraph(container: AppContainer) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Routes.browser("/storage/emulated/0"),
    ) {
        composable(
            route = Routes.BROWSER,
            arguments = listOf(
                navArgument("path") {
                    type = NavType.StringType
                    defaultValue = "/storage/emulated/0"
                }
            ),
        ) { backStackEntry ->
            val pathArg = backStackEntry.arguments?.getString("path") ?: "/storage/emulated/0"
            val decodedPath = URLDecoder.decode(pathArg, Charsets.UTF_8.name())

            val viewModel: FileListViewModel = viewModel(
                // Chave por destino: cada nível de pasta tem o próprio ViewModel
                // e o próprio estado (não re-lista ao voltar de um filho).
                viewModelStoreOwner = backStackEntry,
                factory = FileListViewModel.factory(container),
            )
            val state by viewModel.state.collectAsState()

            FileListScreen(
                state = state,
                onNavigateTo = { path ->
                    if (path != state.path) navController.navigate(Routes.browser(path))
                },
                onGoUp = {
                    val parent = state.path.substringBeforeLast('/', "")
                    if (parent.isNotEmpty()) navController.popBackStack() else viewModel.load("/")
                },
                onLoad = viewModel::load,
                onToggleHidden = viewModel::toggleHidden,
                onToggleGrid = viewModel::toggleGrid,
                onSortMode = viewModel::setSortMode,
                onToggleSelect = viewModel::toggleSelect,
                onClearSelection = viewModel::clearSelection,
                onDeleteSelection = viewModel::deleteSelected,
                onCreateFolder = viewModel::createFolder,
            )
        }
    }
}
