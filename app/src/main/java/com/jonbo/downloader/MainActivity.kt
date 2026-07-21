package com.jonbo.downloader

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.jonbo.downloader.ui.DownloadScreen
import com.jonbo.downloader.ui.DownloaderTheme
import com.jonbo.downloader.ui.DownloaderViewModel
import com.jonbo.downloader.ui.HomeScreen

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
            DownloaderTheme {
                DownloaderApp(
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

/** A null source routes to the auto-detect screen. */
private fun downloadRoute(source: Source?) = "download/${source?.key ?: Source.AUTO_KEY}"

@Composable
private fun DownloaderApp(
    sharedLink: String?,
    onSharedLinkHandled: () -> Unit,
) {
    val navController = rememberNavController()
    val viewModel: DownloaderViewModel = viewModel()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()

    // A shared link jumps straight to the matching screen and starts reading it.
    LaunchedEffect(sharedLink) {
        val link = sharedLink ?: return@LaunchedEffect
        val source = Source.detect(link) ?: Source.YOUTUBE
        viewModel.acceptSharedLink(link, source)
        navController.navigate(downloadRoute(source)) {
            popUpTo(ROUTE_HOME)
        }
        onSharedLinkHandled()
    }

    NavHost(navController = navController, startDestination = ROUTE_HOME) {
        composable(ROUTE_HOME) {
            HomeScreen(
                downloads = downloads,
                engineVersion = viewModel.engineVersion,
                engineUpdating = viewModel.engineUpdating,
                engineMessage = viewModel.engineMessage,
                onOpen = { navController.navigate(downloadRoute(it)) },
                onCancel = viewModel::cancel,
                onRetry = viewModel::retry,
                onClearFinished = viewModel::clearFinished,
                onUpdateEngine = viewModel::updateEngine,
                onDismissEngineMessage = viewModel::dismissEngineMessage,
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
                onBack = { navController.popBackStack() },
            )
        }
    }
}
