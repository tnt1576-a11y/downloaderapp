package com.jonbo.downloader.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.palette.graphics.Palette
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.jonbo.downloader.download.DownloadItem
import com.jonbo.downloader.download.VideoInfoRepo
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

@Composable
private fun DownloadRow(
    item: DownloadItem,
    onCancel: (UUID) -> Unit,
    onRetry: (DownloadItem) -> Unit,
) {
    val context = LocalContext.current
    val playable = item.state == DownloadItem.Status.DONE && item.uri != null
    val mime = if (item.request?.option?.audioOnly == true) "audio/*" else "video/*"

    val surface = MaterialTheme.colorScheme.surfaceVariant
    val accent = rememberAccentColor(item.thumbnail, fallback = surface)
    // Tint sits only on the left, fading into the card so text stays readable.
    val tint by animateColorAsState(accent, tween(450), label = "tint")

    Card(
        onClick = { if (playable) openMedia(context, item.uri!!, mime) },
        enabled = playable,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        colors = CardDefaults.cardColors(
            containerColor = surface,
            disabledContainerColor = surface,
        ),
    ) {
        Box(
            Modifier.background(
                Brush.horizontalGradient(
                    0f to tint.copy(alpha = 0.55f),
                    0.6f to surface,
                ),
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Thumbnail(item)

                Column(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                ) {
                    Text(
                        item.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
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
                    if (item.isActive) {
                        val mod = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                        if (item.progress > 0f) {
                            LinearProgressIndicator(progress = { item.progress / 100f }, modifier = mod)
                        } else {
                            LinearProgressIndicator(modifier = mod)
                        }
                    }
                }

                RowActions(item, mime, context, onCancel, onRetry)
            }
        }
    }
}

/** The video's thumbnail, or a coloured placeholder while there isn't one. */
@Composable
private fun Thumbnail(item: DownloadItem) {
    val shape = RoundedCornerShape(10.dp)
    val placeholder = MaterialTheme.colorScheme.surface

    Box(
        modifier = Modifier
            .size(width = 72.dp, height = 72.dp)
            .clip(shape)
            .background(placeholder),
        contentAlignment = Alignment.Center,
    ) {
        if (item.thumbnail != null) {
            AsyncImage(
                model = item.thumbnail,
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
            // Play glyph only over a real image; on the placeholder it just muddles the icon.
            if (item.state == DownloadItem.Status.DONE) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .background(Color.Black.copy(alpha = 0.25f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White)
                }
            }
        } else {
            Icon(
                if (item.request?.option?.audioOnly == true) {
                    Icons.Default.MusicNote
                } else {
                    Icons.Default.PlayArrow
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RowActions(
    item: DownloadItem,
    mime: String,
    context: Context,
    onCancel: (UUID) -> Unit,
    onRetry: (DownloadItem) -> Unit,
) {
    if (item.canRetry) {
        IconButton(onClick = { onRetry(item) }) {
            Icon(Icons.Default.Refresh, contentDescription = "Retry")
        }
    }

    when (item.state) {
        DownloadItem.Status.DONE ->
            if (item.uri != null) {
                IconButton(onClick = { shareMedia(context, item.uri, mime) }) {
                    Icon(Icons.Default.Share, contentDescription = "Share")
                }
            }

        DownloadItem.Status.FAILED ->
            Icon(
                Icons.Default.ErrorOutline,
                contentDescription = "Failed",
                tint = MaterialTheme.colorScheme.error,
            )

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

/**
 * Loads the thumbnail once more (Coil serves it from cache) and pulls a representative colour
 * out of it, so each row is tinted by its own video. Falls back to the card colour.
 */
@Composable
private fun rememberAccentColor(thumbnail: String?, fallback: Color): Color {
    val context = LocalContext.current
    var color by remember(thumbnail) { mutableStateOf(fallback) }

    androidx.compose.runtime.LaunchedEffect(thumbnail) {
        if (thumbnail == null) return@LaunchedEffect
        val request = ImageRequest.Builder(context)
            .data(thumbnail)
            .allowHardware(false) // Palette needs to read pixels back
            .size(96)
            .build()
        val bitmap = (context.imageLoader.execute(request).drawable as? BitmapDrawable)?.bitmap
            ?: return@LaunchedEffect
        val palette = Palette.from(bitmap).generate()
        val swatch = palette.vibrantSwatch
            ?: palette.mutedSwatch
            ?: palette.dominantSwatch
        if (swatch != null) color = Color(swatch.rgb)
    }
    return color
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
    DownloadItem.Status.RUNNING -> when {
        item.progress <= 0f -> "Preparing…"

        // Post-download work: percentages of the transfer no longer mean anything.
        item.stage == "Combining" -> "Combining video and audio…"
        item.stage == "Converting" -> "Converting to MP3…"

        // e.g. "Video · 42% of 137 MB · 4.6 MB/s · 0:27 left" — the phase label is what stops
        // a two-stream download (video, then audio) looking like it downloaded twice.
        else -> listOfNotNull(
            item.stage.takeIf { it.isNotBlank() },
            VideoInfoRepo.formatBytes(item.totalBytes)
                ?.let { "${item.progress.roundToInt()}% of $it" }
                ?: "${item.progress.roundToInt()}%",
            item.speed.takeIf { it.isNotBlank() },
            item.eta.takeIf { it.isNotBlank() && it != "00:00" }
                ?.let { it.trimStart('0').let { t -> if (t.startsWith(":")) "0$t" else t } }
                ?.let { "$it left" },
        ).joinToString(" · ")
    }

    DownloadItem.Status.DONE -> listOfNotNull(
        "Saved",
        VideoInfoRepo.formatBytes(item.sizeBytes),
        "tap to play",
    ).joinToString(" · ")
    DownloadItem.Status.CANCELLED -> "Cancelled"
    DownloadItem.Status.FAILED -> item.error?.takeIf { it.isNotBlank() } ?: "Failed"
}
