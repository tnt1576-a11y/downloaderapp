package com.jonbo.downloader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
            DownloadRow(item = item, onCancel = onCancel)
        }
    }
}

@Composable
private fun DownloadRow(item: DownloadItem, onCancel: (UUID) -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
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

                when (item.state) {
                    DownloadItem.Status.DONE ->
                        Icon(Icons.Default.CheckCircle, contentDescription = "Saved")

                    DownloadItem.Status.FAILED ->
                        Icon(
                            Icons.Default.ErrorOutline,
                            contentDescription = "Failed",
                            tint = MaterialTheme.colorScheme.error,
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

private fun statusLine(item: DownloadItem): String = when (item.state) {
    DownloadItem.Status.QUEUED -> "Queued"
    DownloadItem.Status.RUNNING ->
        if (item.progress > 0f) "${item.progress.roundToInt()}%" else "Preparing…"

    DownloadItem.Status.DONE -> "Saved to your library"
    DownloadItem.Status.CANCELLED -> "Cancelled"
    DownloadItem.Status.FAILED -> item.error?.takeIf { it.isNotBlank() } ?: "Failed"
}
