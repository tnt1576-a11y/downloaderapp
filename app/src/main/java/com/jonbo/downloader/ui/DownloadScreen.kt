package com.jonbo.downloader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.jonbo.downloader.Source
import com.jonbo.downloader.download.DownloadItem
import com.jonbo.downloader.download.QualityOption
import com.jonbo.downloader.download.VideoDetails
import com.jonbo.downloader.download.VideoInfoRepo
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadScreen(
    /** Null on the auto-detect screen, where the site is worked out from the link. */
    source: Source?,
    url: String,
    fetchState: FetchState,
    downloads: List<DownloadItem>,
    onUrlChange: (String) -> Unit,
    onFetch: () -> Unit,
    onDownload: (QualityOption) -> Unit,
    onCancel: (UUID) -> Unit,
    onRetry: (DownloadItem) -> Unit,
    onClearFinished: () -> Unit,
    onBack: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val title = source?.label ?: "Any link"
    val hint = source?.hint ?: "Paste a link from YouTube, Instagram, X or TikTok"
    val picksQuality = source?.pickQuality ?: true

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                OutlinedTextField(
                    value = url,
                    onValueChange = onUrlChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Link") },
                    placeholder = { Text(hint) },
                    singleLine = true,
                    trailingIcon = {
                        Row {
                            // Only worth showing when there is something to clear.
                            if (url.isNotEmpty()) {
                                IconButton(onClick = { onUrlChange("") }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear")
                                }
                            }
                            IconButton(onClick = {
                                clipboard.getText()?.text?.let(onUrlChange)
                            }) {
                                Icon(Icons.Default.ContentPaste, contentDescription = "Paste")
                            }
                        }
                    },
                )
            }

            item {
                Button(
                    onClick = onFetch,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = url.isNotBlank() && fetchState !is FetchState.Loading,
                ) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Text(
                        text = if (picksQuality) "Find qualities" else "Fetch video",
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }

            when (val state = fetchState) {
                is FetchState.Idle -> Unit

                is FetchState.Loading -> item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(22.dp))
                        Text("Reading link…", Modifier.padding(start = 12.dp))
                    }
                }

                is FetchState.Error -> item { ErrorCard(state.message) }

                is FetchState.Ready -> {
                    item { VideoCard(state.details) }
                    if (!state.details.hasAudio) {
                        item { NoAudioCard() }
                    }
                    item {
                        Text(
                            if (picksQuality) "Choose a quality" else "Ready",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    items(state.details.options) { option ->
                        QualityRow(option = option, onClick = { onDownload(option) })
                    }
                }
            }

            item {
                DownloadsSection(
                    downloads = downloads,
                    onCancel = onCancel,
                    onRetry = onRetry,
                    onClearFinished = onClearFinished,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
    }
}

/**
 * Shown when the site offered no audio stream. Usually the post is genuinely silent, but it
 * also happens when music is licensed per-region — the same link can come back with audio from
 * a different network or VPN exit.
 */
@Composable
private fun NoAudioCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.VolumeOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Column(Modifier.padding(start = 12.dp)) {
                Text(
                    "This will download without sound",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    "The site served no audio for this post. If it should have music, try " +
                        "again on a different network or VPN region — audio is often " +
                        "restricted by country.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
    }
}

@Composable
private fun VideoCard(details: VideoDetails) {
    Card(Modifier.fillMaxWidth()) {
        Column {
            if (!details.thumbnail.isNullOrBlank()) {
                AsyncImage(
                    model = details.thumbnail,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
            }
            Column(Modifier.padding(14.dp)) {
                Text(
                    details.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = listOfNotNull(
                    details.uploader,
                    VideoInfoRepo.formatDuration(details.durationSeconds),
                ).joinToString(" · ")
                if (meta.isNotBlank()) {
                    Text(
                        meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun QualityRow(option: QualityOption, onClick: () -> Unit) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(option.label, style = MaterialTheme.typography.bodyLarge)
                if (option.detail.isNotBlank()) {
                    Text(
                        option.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(Icons.Default.Download, contentDescription = "Download ${option.label}")
        }
        HorizontalDivider()
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Box(Modifier.size(width = 12.dp, height = 1.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}
