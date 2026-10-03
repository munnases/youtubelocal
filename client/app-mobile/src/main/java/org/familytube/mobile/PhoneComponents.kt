package org.familytube.mobile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.familytube.core.designsystem.FamilyColors
import org.familytube.core.model.Video

internal enum class Glyph { PLAY, PAUSE, BACK, SEARCH, SERVER, HOME, LIBRARY, EXPAND, COLLAPSE, REWIND, FORWARD, NEXT, REFRESH, CLOSE }

@Composable
internal fun FamilyIcon(glyph: Glyph, modifier: Modifier = Modifier, color: Color = FamilyColors.text) {
    Canvas(modifier.size(24.dp)) {
        val unit = size.minDimension / 24f
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color,
            Offset(x1 * unit, y1 * unit), Offset(x2 * unit, y2 * unit), 2 * unit)
        fun polygon(vararg points: Float) {
            val path = Path().apply {
                moveTo(points[0] * unit, points[1] * unit)
                for (i in 2 until points.size step 2) lineTo(points[i] * unit, points[i + 1] * unit)
                close()
            }
            drawPath(path, color)
        }
        when (glyph) {
            Glyph.PLAY -> polygon(7f, 4f, 20f, 12f, 7f, 20f)
            Glyph.PAUSE -> { line(8f, 5f, 8f, 19f); line(16f, 5f, 16f, 19f) }
            Glyph.BACK -> { line(20f, 12f, 4f, 12f); line(4f, 12f, 11f, 5f); line(4f, 12f, 11f, 19f) }
            Glyph.SEARCH -> { drawCircle(color, 7 * unit, Offset(10 * unit, 10 * unit), style = Stroke(2 * unit)); line(15f, 15f, 22f, 22f) }
            Glyph.CLOSE -> { line(5f, 5f, 19f, 19f); line(5f, 19f, 19f, 5f) }
            Glyph.SERVER -> {
                for (y in listOf(4f, 13f)) {
                    drawRoundRect(color, Offset(3 * unit, y * unit), androidx.compose.ui.geometry.Size(18 * unit, 7 * unit), style = Stroke(2 * unit))
                    drawCircle(color, unit, Offset(7 * unit, (y + 3.5f) * unit))
                }
            }
            Glyph.HOME -> { polygon(2f, 11f, 12f, 2f, 22f, 11f, 19f, 11f, 19f, 22f, 14f, 22f, 14f, 15f, 10f, 15f, 10f, 22f, 5f, 22f, 5f, 11f) }
            Glyph.LIBRARY -> {
                line(2f, 5f, 19f, 5f); line(2f, 9f, 2f, 22f); line(2f, 22f, 19f, 22f)
                drawRect(color, Offset(6 * unit, 8 * unit), androidx.compose.ui.geometry.Size(16 * unit, 11 * unit), style = Stroke(2 * unit))
                polygon(11f, 10f, 17f, 13.5f, 11f, 17f)
            }
            Glyph.EXPAND -> {
                line(3f, 9f, 3f, 3f); line(3f, 3f, 9f, 3f); line(15f, 3f, 21f, 3f); line(21f, 3f, 21f, 9f)
                line(3f, 15f, 3f, 21f); line(3f, 21f, 9f, 21f); line(15f, 21f, 21f, 21f); line(21f, 21f, 21f, 15f)
            }
            Glyph.COLLAPSE -> {
                line(3f, 9f, 9f, 9f); line(9f, 9f, 9f, 3f); line(15f, 3f, 15f, 9f); line(15f, 9f, 21f, 9f)
                line(3f, 15f, 9f, 15f); line(9f, 15f, 9f, 21f); line(15f, 21f, 15f, 15f); line(15f, 15f, 21f, 15f)
            }
            Glyph.REWIND -> { polygon(12f, 5f, 2f, 12f, 12f, 19f); polygon(22f, 5f, 12f, 12f, 22f, 19f) }
            Glyph.FORWARD -> { polygon(2f, 5f, 12f, 12f, 2f, 19f); polygon(12f, 5f, 22f, 12f, 12f, 19f) }
            Glyph.NEXT -> { polygon(4f, 5f, 17f, 12f, 4f, 19f); line(20f, 5f, 20f, 19f) }
            Glyph.REFRESH -> {
                drawArc(color, 35f, 290f, false, Offset(4 * unit, 4 * unit), androidx.compose.ui.geometry.Size(16 * unit, 16 * unit), style = Stroke(2 * unit))
                polygon(22f, 4f, 22f, 12f, 14f, 9f)
            }
        }
    }
}

@Composable
internal fun ActionIcon(label: String, glyph: Glyph, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
        .semantics { contentDescription = label }) {
        FamilyIcon(glyph, color = if (enabled) FamilyColors.text else FamilyColors.muted)
    }
}

@Composable
internal fun Thumbnail(video: Video, modifier: Modifier = Modifier, progress: Float = 0f, badge: Boolean = true) {
    Box(modifier.aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(FamilyColors.surface), contentAlignment = Alignment.Center) {
        FamilyIcon(Glyph.PLAY, Modifier.size(36.dp), FamilyColors.muted)
        AsyncImage(model = video.thumbnailUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        if (badge && video.durationSeconds > 0) Text(formatTime((video.durationSeconds * 1000).toLong()),
            Modifier.align(Alignment.BottomEnd).padding(8.dp).clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = .8f)).padding(horizontal = 5.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall, color = Color.White)
        if (progress > 0) LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(3.dp).align(Alignment.BottomCenter),
            color = FamilyColors.accent, trackColor = Color.White.copy(alpha = .25f), drawStopIndicator = {})
    }
}

internal fun formatTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%d:%02d".format(seconds / 60, seconds % 60)
}
