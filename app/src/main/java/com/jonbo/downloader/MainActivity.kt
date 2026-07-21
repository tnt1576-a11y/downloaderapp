package com.jonbo.downloader

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.jonbo.downloader.ui.DownloadScreen
import com.jonbo.downloader.ui.DownloaderTheme
import com.jonbo.downloader.ui.DownloaderViewModel
import com.jonbo.downloader.ui.HistoryScreen
import com.jonbo.downloader.ui.HomeScreen
import com.jonbo.downloader.ui.SettingsScreen

class MainActivity : ComponentActivity() {

    /** A link handed to us by the share sheet, waiting to be routed to the right screen. */
    private var sharedLink by mutableStateOf<String?>(null)

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        sharedLink = extractUrl(intent.getStringExtra(Intent.EXTRA_TEXT))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            val viewModel: DownloaderViewModel = viewModel()
            val theme by viewModel.settings.theme.collectAsStateWithLifecycle()

            DownloaderTheme(themeMode = theme) {
                DownloaderApp(
                    viewModel = viewModel,
                    sharedLink = sharedLink,
                    onSharedLinkHandled = { sharedLink = null },
                )
            }
        }
    }

    // launchMode=singleTask, so a second share arrives here rather than in a new activity.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractUrl(intent.getStringExtra(Intent.EXTRA_TEXT))?.let { sharedLink = it }
    }
}

private const val ROUTE_HOME = "home"
private const val ROUTE_DOWNLOAD = "download/{source}"
private const val ROUTE_HISTORY = "history"
private const val ROUTE_SETTINGS = "settings"

/** A null source routes to the auto-detect screen. */
private fun downloadRoute(source: Source?) = "download/${source?.key ?: Source.AUTO_KEY}"

@Composable
private fun DownloaderApp(
    viewModel: DownloaderViewModel,
    sharedLink: String?,
    onSharedLinkHandled: () -> Unit,
) {
    val navController = rememberNavController()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val theme by viewModel.settings.theme.collectAsStateWithLifecycle()
    val shareAction by viewModel.settings.shareAction.collectAsStateWithLifecycle()
    val mp3Audio by viewModel.settings.mp3Audio.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // A shared link either starts downloading straight away, or opens the matching screen.
    LaunchedEffect(sharedLink) {
        val link = sharedLink ?: return@LaunchedEffect
        val source = Source.detect(link)

        if (viewModel.quickDownload(link, source)) {
            Toast.makeText(context, "Downloading…", Toast.LENGTH_SHORT).show()
        } else {
            viewModel.acceptSharedLink(link, source ?: Source.YOUTUBE)
            navController.navigate(downloadRoute(source)) {
                popUpTo(ROUTE_HOME)
            }
        }
        onSharedLinkHandled()
    }

    NavHost(navController = navController, startDestination = ROUTE_HOME) {
        composable(ROUTE_HOME) {
            LaunchedEffect(downloads) { viewModel.refreshStorage() }

            HomeScreen(
                downloads = downloads,
                engineVersion = viewModel.engineVersion,
                engineDetail = viewModel.engineDetail,
                engineStaleDays = viewModel.engineStaleDays,
                engineUpdating = viewModel.engineUpdating,
                engineMessage = viewModel.engineMessage,
                storage = viewModel.storage,
                onOpen = { navController.navigate(downloadRoute(it)) },
                onCancel = viewModel::cancel,
                onRetry = viewModel::retry,
                onClearFinished = viewModel::clearFinished,
                onUpdateEngine = viewModel::updateEngine,
                onDismissEngineMessage = viewModel::dismissEngineMessage,
                onOpenHistory = { navController.navigate(ROUTE_HISTORY) },
                onOpenSettings = { navController.navigate(ROUTE_SETTINGS) },
            )
        }

        composable(ROUTE_DOWNLOAD) { entry ->
            // Null for the "auto" key, which is the detect-from-the-link screen.
            val source = Source.fromKey(entry.arguments?.getString("source"))
            LaunchedEffect(source) { viewModel.onScreenOpened(source) }

            DownloadScreen(
                source = source,
                url = viewModel.url,
                fetchState = viewModel.fetchState,
                downloads = downloads,
                onUrlChange = viewModel::onUrlChanged,
                onFetch = { viewModel.fetch(source) },
                onDownload = viewModel::download,
                onCancel = viewModel::cancel,
                onRetry = viewModel::retry,
                onClearFinished = viewModel::clearFinished,
                onTryAnyway = { viewModel.fetch(source, force = true) },
                onDownloadPlaylist = viewModel::downloadPlaylist,
                onBack = { navController.popBackStack() },
            )
        }

        composable(ROUTE_HISTORY) {
            HistoryScreen(
                entries = history,
                onBack = { navController.popBackStack() },
                onRedownload = { entry ->
                    viewModel.redownload(entry)
                    navController.navigate(downloadRoute(Source.detect(entry.url)))
                },
                onDelete = viewModel::deleteHistoryEntry,
                onClearAll = viewModel::clearHistory,
            )
        }

        composable(ROUTE_SETTINGS) {
            SettingsScreen(
                theme = theme,
                shareAction = shareAction,
                mp3Audio = mp3Audio,
                onTheme = viewModel.settings::setTheme,
                onShareAction = viewModel.settings::setShareAction,
                onMp3Audio = viewModel.settings::setMp3Audio,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
