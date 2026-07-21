package com.jonbo.downloader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.jonbo.downloader.Source
import com.jonbo.downloader.download.DownloadItem
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    downloads: List<DownloadItem>,
    engineVersion: String?,
    engineUpdating: Boolean,
    engineMessage: String?,
    onOpen: (Source) -> Unit,
    onCancel: (UUID) -> Unit,
    onClearFinished: () -> Unit,
    onUpdateEngine: () -> Unit,
    onDismissEngineMessage: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Downloader") },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                FunctionTile(
                    title = "YouTube",
                    subtitle = "Download a video, pick the quality",
                    icon = Icons.Default.PlayCircleFilled,
                    onClick = { onOpen(Source.YOUTUBE) },
                )
            }
            item {
                FunctionTile(
                    title = "Instagram",
                    subtitle = "Download a reel or video post",
                    icon = Icons.Default.PhotoCamera,
                    onClick = { onOpen(Source.INSTAGRAM) },
                )
            }

            item {
                DownloadsSection(
                    downloads = downloads,
                    onCancel = onCancel,
                    onClearFinished = onClearFinished,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            item {
                EngineCard(
                    version = engineVersion,
                    updating = engineUpdating,
                    message = engineMessage,
                    onUpdate = onUpdateEngine,
                    onDismissMessage = onDismissEngineMessage,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

/**
 * The yt-dlp engine panel. Updating is the only action in the app that downloads code and then
 * runs it, so it is behind an explicit button plus a dialog that says exactly that.
 */
@Composable
private fun EngineCard(
    version: String?,
    updating: Boolean,
    message: String?,
    onUpdate: () -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirming by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Download engine", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "yt-dlp ${version ?: "(bundled with the app)"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (message != null) {
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (message != null) {
                    TextButton(onClick = onDismissMessage) { Text("Dismiss") }
                }
                if (updating) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                } else {
                    OutlinedButton(onClick = { confirming = true }) { Text("Check for update") }
                }
            }
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Update yt-dlp?") },
            text = {
                Text(
                    "This downloads the latest yt-dlp from GitHub over HTTPS and runs it in " +
                        "place of the copy bundled in the app.\n\n" +
                        "The download is not signature- or checksum-verified, so this is the " +
                        "one action here that fetches code and then executes it. Nothing is " +
                        "downloaded unless you confirm."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirming = false; onUpdate() }) { Text("Update") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FunctionTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(2.4f),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.Start,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.padding(bottom = 4.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Column {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}
