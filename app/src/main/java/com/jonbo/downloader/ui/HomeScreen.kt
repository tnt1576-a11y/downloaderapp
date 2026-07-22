package com.jonbo.downloader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.jonbo.downloader.download.StorageInfo
import com.jonbo.downloader.download.StorageUsage
import java.util.UUID

/** Tiles are generated from the enum, so a new Source shows up here automatically. */
private fun iconFor(source: Source): ImageVector = when (source) {
    Source.YOUTUBE -> Icons.Default.PlayCircleFilled
    Source.INSTAGRAM -> Icons.Default.PhotoCamera
    Source.X -> Icons.Default.Tag
    Source.TIKTOK -> Icons.Default.MusicNote
    Source.REDDIT -> Icons.Default.Forum
    Source.FACEBOOK -> Icons.Default.Groups
    Source.TWITCH -> Icons.Default.Videocam
    Source.VIMEO -> Icons.Default.Movie
    Source.SOUNDCLOUD -> Icons.Default.GraphicEq
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    downloads: List<DownloadItem>,
    engineVersion: String?,
    engineDetail: String,
    engineStaleDays: Long?,
    engineUpdating: Boolean,
    engineMessage: String?,
    storage: StorageUsage,
    onOpen: (Source?) -> Unit,
    onCancel: (UUID) -> Unit,
    onRetry: (DownloadItem) -> Unit,
    onClearFinished: () -> Unit,
    onUpdateEngine: () -> Unit,
    onDismissEngineMessage: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Downloader") },
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(onClick = onOpenHistory) {
                        Icon(Icons.Default.History, contentDescription = "History")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
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
            // The quickest path: paste anything and let the app work out the site.
            item {
                FunctionTile(
                    title = "Any link",
                    subtitle = "Paste from any supported site",
                    icon = Icons.Default.Link,
                    primary = true,
                    onClick = { onOpen(null) },
                )
            }

            // Two per row: nine sites as full-width tiles would be a lot of scrolling.
            val pairs = Source.entries.chunked(2)
            items(pairs.size) { index ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pairs[index].forEach { source ->
                        SiteTile(
                            source = source,
                            onClick = { onOpen(source) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // Keeps the last tile half-width when the count is odd.
                    if (pairs[index].size == 1) Spacer(Modifier.weight(1f))
                }
            }

            item {
                DownloadsSection(
                    downloads = downloads,
                    onCancel = onCancel,
                    onRetry = onRetry,
                    onClearFinished = onClearFinished,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            item {
                StorageCard(storage, Modifier.padding(top = 12.dp))
            }

            item {
                EngineCard(
                    version = engineVersion,
                    detail = engineDetail,
                    staleDays = engineStaleDays,
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
/** How much of the phone the app's downloads are taking up. */
@Composable
private fun StorageCard(usage: StorageUsage, modifier: Modifier = Modifier) {
    if (usage.isEmpty) return
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Storage,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.padding(start = 14.dp)) {
                Text("Storage used", style = MaterialTheme.typography.titleSmall)
                Text(
                    "${StorageInfo.format(usage.bytes)} across ${usage.files} " +
                        if (usage.files == 1) "file" else "files",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EngineCard(
    version: String?,
    detail: String,
    staleDays: Long?,
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
            if (detail.isNotBlank()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (detail.contains("ready")) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }

            // Stale engines are the single most common cause of a site breaking, so say so
            // rather than letting the user discover it as a mysterious failure.
            if (staleDays != null) {
                Text(
                    text = "This engine is about ${staleDays / 7} weeks old. If a site stops " +
                        "working, update here first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

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
                        "It is checked against the SHA-256 that yt-dlp publishes for that " +
                        "release. If it doesn't match, or the checksums can't be reached, the " +
                        "download is discarded and the bundled engine is put back."
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

/** Compact half-width tile used for the site grid. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SiteTile(
    source: Source,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                iconFor(source),
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                source.label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FunctionTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    primary: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (primary) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = if (primary) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer
                },
            )
            Column(Modifier.padding(start = 16.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (primary) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    },
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (primary) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    },
                )
            }
        }
    }
}
