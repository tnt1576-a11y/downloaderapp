package com.jonbo.downloader.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jonbo.downloader.download.DownloadItem
import java.util.UUID
import kotlin.math.roundToInt

/** The shared "what's downloading / what finished" section. */
@Composable
fun DownloadsSection(
    downloads: List<DownloadItem>,
    onCancel: (UUID) -> Unit,
    onRetry: (DownloadItem) -> Unit,
    onClearFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (downloads.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Downloads", style = MaterialTheme.typography.titleMedium)
            if (downloads.any { !it.isActive }) {
                TextButton(onClick = onClearFinished) { Text("Clear finished") }
            }
        }

        downloads.forEach { item ->
            DownloadRow(item = item, onCancel = onCancel, onRetry = onRetry)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadRow(
    item: DownloadItem,
    onCancel: (UUID) -> Unit,
    onRetry: (DownloadItem) -> Unit,
) {
    val context = LocalContext.current
    val playable = item.state == DownloadItem.Status.DONE && item.uri != null
    val mime = if (item.request?.option?.audioOnly == true) "audio/*" else "video/*"

    Card(
        onClick = { if (playable) openMedia(context, item.uri!!, mime) },
        enabled = playable,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        item.title,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        statusLine(item),
                        style = MaterialTheme.typography.bodySmall,
                        color = when (item.state) {
                            DownloadItem.Status.FAILED -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }

                if (item.canRetry) {
                    IconButton(onClick = { onRetry(item) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Retry")
                    }
                }

                when (item.state) {
                    DownloadItem.Status.DONE -> {
                        if (item.uri != null) {
                            IconButton(onClick = { shareMedia(context, item.uri, mime) }) {
                                Icon(Icons.Default.Share, contentDescription = "Share")
                            }
                        }
                        Icon(Icons.Default.PlayArrow, contentDescription = "Play")
                    }

                    DownloadItem.Status.FAILED ->
                        Icon(
                            Icons.Default.ErrorOutline,
                            contentDescription = "Failed",
                            tint = MaterialTheme.colorScheme.error,
                        )

                    // Already stopped — offering "cancel" again would be misleading.
                    DownloadItem.Status.CANCELLED ->
                        Icon(
                            Icons.Default.Block,
                            contentDescription = "Cancelled",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                    else -> IconButton(onClick = { onCancel(item.id) }) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel")
                    }
                }
            }

            if (item.isActive) {
                if (item.progress > 0f) {
                    LinearProgressIndicator(
                        progress = { item.progress / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                    )
                }
            }
        }
    }
}

private fun openMedia(context: Context, uri: String, mime: String) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(Uri.parse(uri), mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No app can play this", Toast.LENGTH_SHORT).show()
    }
}

private fun shareMedia(context: Context, uri: String, mime: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, Uri.parse(uri))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share"))
}

private fun statusLine(item: DownloadItem): String = when (item.state) {
    DownloadItem.Status.QUEUED -> "Queued"
    DownloadItem.Status.RUNNING ->
        if (item.progress > 0f) "${item.progress.roundToInt()}%" else "Preparing…"

    DownloadItem.Status.DONE -> "Saved · tap to play"
    DownloadItem.Status.CANCELLED -> "Cancelled"
    DownloadItem.Status.FAILED -> item.error?.takeIf { it.isNotBlank() } ?: "Failed"
}
