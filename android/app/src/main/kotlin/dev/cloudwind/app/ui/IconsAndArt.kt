package dev.cloudwind.app.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ==================== Minimalist Apple-Stroke Vector Icons ====================

@Composable
fun BrandGlyphIcon(tint: Color, modifier: Modifier = Modifier.size(22.dp)) {
    Canvas(modifier) {
        val sx = size.width / 24f
        val sy = size.height / 20f
        val stroke = Stroke(width = 2.0f * sx, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val cloud = Path().apply {
            moveTo(6.5f * sx, 16.5f * sy)
            lineTo(17f * sx, 16.5f * sy)
            cubicTo(21.2f * sx, 16.5f * sy, 21.8f * sx, 9.6f * sy, 17.7f * sx, 8.6f * sy)
            cubicTo(16.6f * sx, 3.2f * sy, 8.8f * sx, 3.0f * sy, 7.0f * sx, 6.8f * sy)
            cubicTo(2.4f * sx, 7.3f * sy, 2.3f * sx, 16.5f * sy, 6.5f * sx, 16.5f * sy)
        }
        drawPath(cloud, tint, style = stroke)
        drawLine(tint, Offset(2.2f * sx, 12.2f * sy), Offset(9.2f * sx, 12.2f * sy), strokeWidth = 2.0f * sx, cap = StrokeCap.Round)
    }
}

@Composable
fun SyncTabIcon(tint: Color, modifier: Modifier = Modifier.size(22.dp)) {
    Canvas(modifier) {
        val s = size.minDimension / 24f
        val stroke = Stroke(width = 1.85f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val cloud = Path().apply {
            moveTo(7.2f * s, 18.5f * s)
            lineTo(17.5f * s, 18.5f * s)
            cubicTo(21.6f * s, 18.5f * s, 21.8f * s, 11.2f * s, 18.0f * s, 10.2f * s)
            cubicTo(16.8f * s, 4.4f * s, 8.6f * s, 4.2f * s, 6.6f * s, 9.2f * s)
            cubicTo(2.4f * s, 9.8f * s, 2.5f * s, 18.5f * s, 7.2f * s, 18.5f * s)
        }
        drawPath(cloud, tint, style = stroke)
        drawLine(tint, Offset(12f * s, 11.4f * s), Offset(12f * s, 16.6f * s), strokeWidth = 1.85f * s, cap = StrokeCap.Round)
        val arrow = Path().apply {
            moveTo(9.6f * s, 13.7f * s)
            lineTo(12f * s, 11.2f * s)
            lineTo(14.4f * s, 13.7f * s)
        }
        drawPath(arrow, tint, style = stroke)
    }
}

@Composable
fun FolderTabIcon(tint: Color, modifier: Modifier = Modifier.size(22.dp)) {
    Canvas(modifier) {
        val s = size.minDimension / 24f
        val stroke = Stroke(width = 1.85f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val p = Path().apply {
            moveTo(5.5f * s, 19f * s)
            lineTo(18.5f * s, 19f * s)
            quadraticTo(21f * s, 19f * s, 21f * s, 16.5f * s)
            lineTo(21f * s, 9.5f * s)
            quadraticTo(21f * s, 7f * s, 18.5f * s, 7f * s)
            lineTo(11.3f * s, 7f * s)
            lineTo(9.9f * s, 5.4f * s)
            quadraticTo(9.4f * s, 5f * s, 8.7f * s, 5f * s)
            lineTo(5.5f * s, 5f * s)
            quadraticTo(3f * s, 5f * s, 3f * s, 7.5f * s)
            lineTo(3f * s, 16.5f * s)
            quadraticTo(3f * s, 19f * s, 5.5f * s, 19f * s)
            close()
        }
        drawPath(p, tint, style = stroke)
    }
}

/** 4-square photo grid icon for History tab (matches cloudwind_v4.html). */
@Composable
fun HistoryGridTabIcon(tint: Color, modifier: Modifier = Modifier.size(22.dp)) {
    Canvas(modifier) {
        val s = size.minDimension / 24f
        val stroke = Stroke(width = 1.85f * s, join = StrokeJoin.Round)
        val r = CornerRadius(1.7f * s, 1.7f * s)
        val sz = Size(7f * s, 7f * s)
        drawRoundRect(tint, Offset(3.5f * s, 3.5f * s), sz, r, style = stroke)
        drawRoundRect(tint, Offset(13.5f * s, 3.5f * s), sz, r, style = stroke)
        drawRoundRect(tint, Offset(3.5f * s, 13.5f * s), sz, r, style = stroke)
        drawRoundRect(tint, Offset(13.5f * s, 13.5f * s), sz, r, style = stroke)
    }
}

/** Crisp 2-row sliders icon for Settings tab (matches cloudwind_v4.html). */
@Composable
fun SlidersTabIcon(tint: Color, modifier: Modifier = Modifier.size(22.dp)) {
    Canvas(modifier) {
        val s = size.minDimension / 24f
        val sw = 1.85f * s
        val stroke = Stroke(width = sw, cap = StrokeCap.Round)
        drawLine(tint, Offset(4f * s, 7f * s), Offset(12.6f * s, 7f * s), sw, StrokeCap.Round)
        drawLine(tint, Offset(17.4f * s, 7f * s), Offset(20f * s, 7f * s), sw, StrokeCap.Round)
        drawCircle(tint, 2.3f * s, Offset(15f * s, 7f * s), style = stroke)

        drawLine(tint, Offset(4f * s, 17f * s), Offset(6.6f * s, 17f * s), sw, StrokeCap.Round)
        drawLine(tint, Offset(11.4f * s, 17f * s), Offset(20f * s, 17f * s), sw, StrokeCap.Round)
        drawCircle(tint, 2.3f * s, Offset(9f * s, 17f * s), style = stroke)
    }
}

@Composable
fun ClockTabIcon(tint: Color, modifier: Modifier = Modifier.size(16.dp)) {
    Canvas(modifier) {
        val s = size.minDimension / 24f
        val stroke = Stroke(width = 2.0f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawCircle(tint, radius = 8.5f * s, center = Offset(12f * s, 12f * s), style = stroke)
        val hands = Path().apply {
            moveTo(12f * s, 7.5f * s)
            lineTo(12f * s, 12f * s)
            lineTo(15.2f * s, 13.8f * s)
        }
        drawPath(hands, tint, style = stroke)
    }
}

@Composable
fun FilterCircleIcon(tint: Color, modifier: Modifier = Modifier.size(22.dp)) {
    Canvas(modifier) {
        val s = size.minDimension / 24f
        val sw = 1.85f * s
        drawCircle(tint, 9.5f * s, Offset(12f * s, 12f * s), style = Stroke(sw))
        drawLine(tint, Offset(7.5f * s, 9.5f * s), Offset(16.5f * s, 9.5f * s), sw, StrokeCap.Round)
        drawLine(tint, Offset(9.5f * s, 12.5f * s), Offset(14.5f * s, 12.5f * s), sw, StrokeCap.Round)
        drawLine(tint, Offset(11f * s, 15.5f * s), Offset(13f * s, 15.5f * s), sw, StrokeCap.Round)
    }
}

@Composable
fun InfoCircleIcon(tint: Color, modifier: Modifier = Modifier.size(18.dp)) {
    Canvas(modifier) {
        val s = size.minDimension / 24f
        val sw = 1.9f * s
        drawCircle(tint, 9.2f * s, Offset(12f * s, 12f * s), style = Stroke(sw))
        drawCircle(tint, 1.3f * s, Offset(12f * s, 8.2f * s))
        drawLine(tint, Offset(12f * s, 11.4f * s), Offset(12f * s, 16.5f * s), sw, StrokeCap.Round)
    }
}

@Composable
fun PlusIcon(tint: Color, modifier: Modifier = Modifier.size(15.dp)) {
    Canvas(modifier) {
        val s = size.minDimension / 24f
        val sw = 2.4f * s
        drawLine(tint, Offset(12f * s, 5f * s), Offset(12f * s, 19f * s), sw, StrokeCap.Round)
        drawLine(tint, Offset(5f * s, 12f * s), Offset(19f * s, 12f * s), sw, StrokeCap.Round)
    }
}

@Composable
fun ThemeToggleIcon(isDark: Boolean, tint: Color, modifier: Modifier = Modifier.size(15.dp)) {
    Canvas(modifier) {
        val s = size.minDimension / 24f
        val sw = 2.2f * s
        if (isDark) {
            drawCircle(tint, 4.2f * s, Offset(12f * s, 12f * s))
            for (i in 0 until 8) {
                val rad = Math.toRadians(i * 45.0)
                val x1 = (12f + 7.2f * cos(rad)).toFloat() * s
                val y1 = (12f + 7.2f * sin(rad)).toFloat() * s
                val x2 = (12f + 9.5f * cos(rad)).toFloat() * s
                val y2 = (12f + 9.5f * sin(rad)).toFloat() * s
                drawLine(tint, Offset(x1, y1), Offset(x2, y2), sw, StrokeCap.Round)
            }
        } else {
            val moon = Path().apply {
                moveTo(20f * s, 14.5f * s)
                cubicTo(18.2f * s, 19.0f * s, 12.6f * s, 21.0f * s, 8.0f * s, 18.8f * s)
                cubicTo(3.6f * s, 16.8f * s, 2.2f * s, 11.2f * s, 4.8f * s, 6.8f * s)
                cubicTo(6.1f * s, 4.6f * s, 8.2f * s, 3.6f * s, 10.2f * s, 3.6f * s)
                cubicTo(8.3f * s, 6.8f * s, 8.8f * s, 11.0f * s, 12.0f * s, 13.4f * s)
                cubicTo(14.5f * s, 15.2f * s, 17.6f * s, 15.4f * s, 20f * s, 14.5f * s)
                close()
            }
            drawPath(moon, tint)
        }
    }
}

@Composable
fun ChevronIcon(tint: Color, modifier: Modifier = Modifier.size(13.dp)) {
    Canvas(modifier) {
        val s = size.minDimension / 24f
        val p = Path().apply {
            moveTo(9f * s, 5.5f * s)
            lineTo(15.5f * s, 12f * s)
            lineTo(9f * s, 18.5f * s)
        }
        drawPath(p, tint, style = Stroke(2.4f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
fun CheckSmallIcon(tint: Color, modifier: Modifier = Modifier.size(15.dp)) {
    Canvas(modifier) {
        val s = size.minDimension / 24f
        val p = Path().apply {
            moveTo(4.5f * s, 12.2f * s)
            lineTo(9.5f * s, 17.2f * s)
            lineTo(19.5f * s, 6.5f * s)
        }
        drawPath(p, tint, style = Stroke(2.6f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
fun CloudFilledIcon(tint: Color, modifier: Modifier = Modifier.size(16.dp)) {
    Canvas(modifier) {
        val s = size.minDimension / 24f
        val p = Path().apply {
            moveTo(7.2f * s, 19f * s)
            lineTo(17.5f * s, 19f * s)
            cubicTo(21.4f * s, 19f * s, 21.8f * s, 11.5f * s, 18.0f * s, 10.5f * s)
            cubicTo(16.8f * s, 4.8f * s, 8.8f * s, 4.6f * s, 6.8f * s, 9.8f * s)
            cubicTo(2.4f * s, 10.4f * s, 2.6f * s, 19f * s, 7.2f * s, 19f * s)
            close()
        }
        drawPath(p, tint)
    }
}

@Composable
fun HouseSmallIcon(tint: Color, modifier: Modifier = Modifier.size(14.dp)) {
    Canvas(modifier) {
        val s = size.minDimension / 24f
        val p = Path().apply {
            moveTo(12f * s, 3.2f * s)
            lineTo(3f * s, 10.6f * s)
            lineTo(3f * s, 20f * s)
            quadraticTo(3f * s, 21f * s, 4f * s, 21f * s)
            lineTo(9.5f * s, 21f * s)
            lineTo(9.5f * s, 15f * s)
            lineTo(14.5f * s, 15f * s)
            lineTo(14.5f * s, 21f * s)
            lineTo(20f * s, 21f * s)
            quadraticTo(21f * s, 21f * s, 21f * s, 20f * s)
            lineTo(21f * s, 10.6f * s)
            close()
        }
        drawPath(p, tint)
    }
}

@Composable
fun PauseSmallIcon(tint: Color, modifier: Modifier = Modifier.size(15.dp)) {
    Canvas(modifier) {
        val s = size.minDimension / 24f
        drawRoundRect(tint, Offset(6f * s, 4.5f * s), Size(4f * s, 15f * s), CornerRadius(1.4f * s, 1.4f * s))
        drawRoundRect(tint, Offset(14f * s, 4.5f * s), Size(4f * s, 15f * s), CornerRadius(1.4f * s, 1.4f * s))
    }
}

@Composable
fun CloseIcon(tint: Color, modifier: Modifier = Modifier.size(14.dp)) {
    Canvas(modifier) {
        val s = size.minDimension / 24f
        val sw = 2.4f * s
        drawLine(tint, Offset(6f * s, 6f * s), Offset(18f * s, 18f * s), sw, StrokeCap.Round)
        drawLine(tint, Offset(18f * s, 6f * s), Offset(6f * s, 18f * s), sw, StrokeCap.Round)
    }
}

@Composable
fun WifiTileIcon(tint: Color, modifier: Modifier = Modifier.size(17.dp)) {
    Canvas(modifier) {
        val s = size.minDimension / 24f
        val stroke = Stroke(2.2f * s, cap = StrokeCap.Round)
        drawArc(tint, -140f, 100f, false, Offset(2f * s, 5f * s), Size(20f * s, 20f * s), style = stroke)
        drawArc(tint, -138f, 96f, false, Offset(6f * s, 9.5f * s), Size(12f * s, 12f * s), style = stroke)
        drawCircle(tint, 1.5f * s, Offset(12f * s, 19.2f * s))
    }
}

@Composable
fun BoltTileIcon(tint: Color, modifier: Modifier = Modifier.size(16.dp)) {
    Canvas(modifier) {
        val s = size.minDimension / 24f
        val p = Path().apply {
            moveTo(13.2f * s, 2.5f * s)
            lineTo(4.5f * s, 13.6f * s)
            lineTo(10.8f * s, 13.6f * s)
            lineTo(9.9f * s, 21.5f * s)
            lineTo(18.6f * s, 10.3f * s)
            lineTo(12.3f * s, 10.3f * s)
            close()
        }
        drawPath(p, tint)
    }
}

@Composable
fun FileTileIcon(tint: Color, modifier: Modifier = Modifier.size(16.dp)) {
    Canvas(modifier) {
        val s = size.minDimension / 24f
        val stroke = Stroke(2.0f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val p = Path().apply {
            moveTo(6f * s, 3f * s)
            lineTo(14f * s, 3f * s)
            lineTo(18f * s, 7f * s)
            lineTo(18f * s, 21f * s)
            lineTo(6f * s, 21f * s)
            close()
        }
        drawPath(p, tint, style = stroke)
        val fold = Path().apply {
            moveTo(14f * s, 3f * s)
            lineTo(14f * s, 7f * s)
            lineTo(18f * s, 7f * s)
        }
        drawPath(fold, tint, style = stroke)
    }
}

@Composable
fun PhotoTileIcon(tint: Color, modifier: Modifier = Modifier.size(17.dp)) {
    Canvas(modifier) {
        val s = size.minDimension / 24f
        val stroke = Stroke(2.0f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawRoundRect(tint, Offset(3f * s, 4f * s), Size(18f * s, 16f * s), CornerRadius(3f * s, 3f * s), style = stroke)
        drawCircle(tint, 1.8f * s, Offset(9f * s, 10f * s), style = stroke)
        val mtn = Path().apply {
            moveTo(21f * s, 16f * s)
            lineTo(16f * s, 11f * s)
            lineTo(8f * s, 19f * s)
        }
        drawPath(mtn, tint, style = stroke)
    }
}

@Composable
fun BatteryTileIcon(tint: Color, modifier: Modifier = Modifier.size(18.dp)) {
    Canvas(modifier) {
        val s = size.minDimension / 24f
        val stroke = Stroke(2.0f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawRoundRect(tint, Offset(2f * s, 7f * s), Size(17f * s, 10f * s), CornerRadius(2.5f * s, 2.5f * s), style = stroke)
        drawLine(tint, Offset(22f * s, 10.5f * s), Offset(22f * s, 13.5f * s), 2.2f * s, StrokeCap.Round)
        drawRoundRect(tint, Offset(4.5f * s, 9.5f * s), Size(8f * s, 5f * s), CornerRadius(1f * s, 1f * s))
    }
}

@Composable
fun PcTileIcon(tint: Color, modifier: Modifier = Modifier.size(17.dp)) {
    Canvas(modifier) {
        val s = size.minDimension / 24f
        val stroke = Stroke(2.0f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawRoundRect(tint, Offset(3f * s, 4f * s), Size(18f * s, 12f * s), CornerRadius(2.2f * s, 2.2f * s), style = stroke)
        drawLine(tint, Offset(8.5f * s, 20f * s), Offset(15.5f * s, 20f * s), 2.0f * s, StrokeCap.Round)
        drawLine(tint, Offset(12f * s, 16f * s), Offset(12f * s, 20f * s), 2.0f * s, StrokeCap.Round)
    }
}

// ==================== Cached WebP Preview Renderer + Fallback Artwork ====================

/** Bounded in-memory LRU cache for decoded 320px WebP thumbnails so the 4-column grid scrolls smoothly. */
private object DecodedThumbCache {
    private val cache = LruCache<String, ImageBitmap>(80)
    fun get(path: String): ImageBitmap? = cache.get(path)
    fun put(path: String, bmp: ImageBitmap) { cache.put(path, bmp) }
}

@Composable
fun CachedThumbImage(
    thumbDirPath: String,
    thumbFile: String?,
    fallbackArt: ThumbArt,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 0.dp,
    showBorder: Boolean = false,
) {
    val fullPath = if (thumbDirPath.isNotEmpty() && !thumbFile.isNullOrBlank()) "$thumbDirPath/$thumbFile" else null
    var bmp by remember(fullPath) { mutableStateOf(fullPath?.let { DecodedThumbCache.get(it) }) }

    LaunchedEffect(fullPath) {
        if (fullPath == null || bmp != null) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) {
            try {
                val f = File(fullPath)
                if (!f.exists() || f.length() <= 0L) null
                else BitmapFactory.decodeFile(fullPath)?.asImageBitmap()
            } catch (_: Exception) {
                null
            }
        }
        if (loaded != null) {
            DecodedThumbCache.put(fullPath, loaded)
            bmp = loaded
        }
    }

    val shape = RoundedCornerShape(cornerRadius)
    val shaped = if (cornerRadius > 0.dp) {
        modifier
            .clip(shape)
            .then(if (showBorder) Modifier.border(0.5.dp, CwTheme.colors.cardBorder, shape) else Modifier)
    } else {
        modifier
    }

    val loadedBmp = bmp
    if (loadedBmp != null) {
        Image(
            bitmap = loadedBmp,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = shaped,
        )
    } else {
        ArtworkThumbnail(
            art = fallbackArt,
            modifier = modifier,
            cornerRadius = cornerRadius,
            showBorder = showBorder,
        )
    }
}

enum class ThumbArt { SUNSET, COAST, DESERT, VIDEO, SCREENSHOT, DOCUMENT, FOREST, NIGHT }

fun thumbArtForFolder(relPath: String): ThumbArt {
    val p = relPath.lowercase()
    return when {
        "screenshot" in p -> ThumbArt.SCREENSHOT
        "video" in p || "movie" in p || "clip" in p -> ThumbArt.VIDEO
        "download" in p -> ThumbArt.DOCUMENT
        "whatsapp" in p -> ThumbArt.FOREST
        "telegram" in p -> ThumbArt.COAST
        "canva" in p -> ThumbArt.DESERT
        "dcim" in p || "camera" in p -> ThumbArt.SUNSET
        else -> ThumbArt.COAST
    }
}

fun thumbArtForItem(name: String, relPath: String, isVideo: Boolean, seed: Long): ThumbArt {
    val n = name.lowercase()
    val p = relPath.lowercase()
    if ("screenshot" in n || "screenshot" in p) return ThumbArt.SCREENSHOT
    if (n.endsWith(".pdf") || "document" in p || "scan" in p) return ThumbArt.DOCUMENT
    if (isVideo) return ThumbArt.VIDEO
    val landscapes = listOf(ThumbArt.SUNSET, ThumbArt.COAST, ThumbArt.FOREST, ThumbArt.DESERT, ThumbArt.NIGHT)
    val idx = ((seed % landscapes.size) + landscapes.size).toInt() % landscapes.size
    return landscapes[idx]
}

@Composable
fun ArtworkThumbnail(
    art: ThumbArt,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 10.dp,
    showBorder: Boolean = false,
) {
    val borderColor = CwTheme.colors.cardBorder
    val shape = RoundedCornerShape(cornerRadius)
    Canvas(
        modifier
            .then(if (cornerRadius > 0.dp) Modifier.clip(shape) else Modifier)
            .then(if (showBorder && cornerRadius > 0.dp) Modifier.border(0.5.dp, borderColor, shape) else Modifier),
    ) {
        val w = size.width
        val h = size.height
        when (art) {
            ThumbArt.SUNSET -> {
                drawRect(Brush.verticalGradient(listOf(Color(0xFFDCE8F2), Color(0xFFF3E0C8), Color(0xFFDFB98E))))
                drawCircle(Color(0xE6FFF8EC), radius = w * 0.095f, center = Offset(w * 0.70f, h * 0.28f))
                drawPath(
                    Path().apply {
                        moveTo(0f, h * 0.64f)
                        quadraticTo(w * 0.30f, h * 0.48f, w * 0.56f, h * 0.62f)
                        quadraticTo(w * 0.80f, h * 0.72f, w, h * 0.57f)
                        lineTo(w, h); lineTo(0f, h); close()
                    },
                    Color(0xB3B9A48E),
                )
                drawPath(
                    Path().apply {
                        moveTo(0f, h * 0.74f)
                        quadraticTo(w * 0.35f, h * 0.62f, w * 0.65f, h * 0.76f)
                        quadraticTo(w * 0.85f, h * 0.82f, w, h * 0.70f)
                        lineTo(w, h); lineTo(0f, h); close()
                    },
                    Color(0xD96E6A63),
                )
                drawPath(
                    Path().apply {
                        moveTo(0f, h * 0.85f)
                        quadraticTo(w * 0.45f, h * 0.78f, w, h * 0.85f)
                        lineTo(w, h); lineTo(0f, h); close()
                    },
                    Color(0xFF3A3B3E),
                )
            }
            ThumbArt.COAST -> {
                drawRect(Brush.linearGradient(listOf(Color(0xFFCFE3F1), Color(0xFF9CC3DD), Color(0xFF5E8FB1))))
                drawCircle(Color(0xD9FFFFFF), radius = w * 0.085f, center = Offset(w * 0.28f, h * 0.25f))
                drawPath(
                    Path().apply {
                        moveTo(0f, h * 0.60f)
                        quadraticTo(w * 0.32f, h * 0.52f, w * 0.62f, h * 0.60f)
                        quadraticTo(w * 0.85f, h * 0.65f, w, h * 0.56f)
                        lineTo(w, h); lineTo(0f, h); close()
                    },
                    Color(0xB32F5E80),
                )
                drawPath(
                    Path().apply {
                        moveTo(0f, h * 0.75f)
                        quadraticTo(w * 0.38f, h * 0.66f, w * 0.72f, h * 0.76f)
                        lineTo(w, h * 0.72f); lineTo(w, h); lineTo(0f, h); close()
                    },
                    Color(0xFF173850),
                )
            }
            ThumbArt.FOREST -> {
                drawRect(Brush.verticalGradient(listOf(Color(0xFFDDE9E2), Color(0xFFAFC9BA))))
                drawCircle(Color(0xD9F7FBF4), radius = w * 0.085f, center = Offset(w * 0.64f, h * 0.24f))
                drawPath(
                    Path().apply {
                        moveTo(0f, h * 0.58f)
                        quadraticTo(w * 0.28f, h * 0.46f, w * 0.55f, h * 0.58f)
                        quadraticTo(w * 0.82f, h * 0.68f, w, h * 0.54f)
                        lineTo(w, h); lineTo(0f, h); close()
                    },
                    Color(0xB37C9F8D),
                )
                drawPath(
                    Path().apply {
                        moveTo(0f, h * 0.72f)
                        quadraticTo(w * 0.36f, h * 0.62f, w * 0.72f, h * 0.74f)
                        lineTo(w, h * 0.70f); lineTo(w, h); lineTo(0f, h); close()
                    },
                    Color(0xD9476B5C),
                )
                drawPath(
                    Path().apply {
                        moveTo(0f, h * 0.84f)
                        quadraticTo(w * 0.48f, h * 0.77f, w, h * 0.84f)
                        lineTo(w, h); lineTo(0f, h); close()
                    },
                    Color(0xFF22392F),
                )
            }
            ThumbArt.DESERT -> {
                drawRect(Brush.verticalGradient(listOf(Color(0xFFE6EEF4), Color(0xFFF0DCC2), Color(0xFFD3A877))))
                drawCircle(Color(0xD9FFFBF2), radius = w * 0.085f, center = Offset(w * 0.75f, h * 0.27f))
                drawPath(
                    Path().apply {
                        moveTo(0f, h * 0.64f)
                        quadraticTo(w * 0.35f, h * 0.50f, w * 0.68f, h * 0.64f)
                        lineTo(w, h * 0.60f); lineTo(w, h); lineTo(0f, h); close()
                    },
                    Color(0xB3A9774A),
                )
                drawPath(
                    Path().apply {
                        moveTo(0f, h * 0.78f)
                        quadraticTo(w * 0.42f, h * 0.66f, w * 0.80f, h * 0.78f)
                        lineTo(w, h * 0.75f); lineTo(w, h); lineTo(0f, h); close()
                    },
                    Color(0xFF6E4A2C),
                )
            }
            ThumbArt.NIGHT -> {
                drawRect(Brush.verticalGradient(listOf(Color(0xFF1E2733), Color(0xFF2D3A4A))))
                drawCircle(Color(0xCCE8EEF5), radius = w * 0.075f, center = Offset(w * 0.32f, h * 0.26f))
                drawPath(
                    Path().apply {
                        moveTo(0f, h * 0.62f)
                        quadraticTo(w * 0.30f, h * 0.52f, w * 0.62f, h * 0.64f)
                        lineTo(w, h * 0.58f); lineTo(w, h); lineTo(0f, h); close()
                    },
                    Color(0xB33E5266),
                )
                drawPath(
                    Path().apply {
                        moveTo(0f, h * 0.78f)
                        quadraticTo(w * 0.45f, h * 0.70f, w, h * 0.78f)
                        lineTo(w, h); lineTo(0f, h); close()
                    },
                    Color(0xFF141B24),
                )
            }
            ThumbArt.VIDEO -> {
                drawRect(Brush.verticalGradient(listOf(Color(0xFFD7E6DF), Color(0xFF84A697), Color(0xFF29463E))))
                drawPath(
                    Path().apply {
                        moveTo(0f, h * 0.58f); lineTo(w * 0.28f, h * 0.38f)
                        lineTo(w * 0.58f, h * 0.60f); lineTo(w * 0.80f, h * 0.37f)
                        lineTo(w, h * 0.57f); lineTo(w, h); lineTo(0f, h); close()
                    },
                    Color(0x9937574E),
                )
                drawPath(
                    Path().apply {
                        moveTo(0f, h * 0.73f)
                        quadraticTo(w * 0.42f, h * 0.64f, w, h * 0.70f)
                        lineTo(w, h); lineTo(0f, h); close()
                    },
                    Color(0xE01B302A),
                )
            }
            ThumbArt.SCREENSHOT -> {
                drawRect(Color(0xFF1C1F25))
                drawRoundRect(Color(0xFF2B3039), Offset(w * 0.14f, h * 0.14f), Size(w * 0.72f, h * 0.22f), CornerRadius(w * 0.06f, w * 0.06f))
                drawRoundRect(Color(0xFF2B3039), Offset(w * 0.14f, h * 0.42f), Size(w * 0.72f, h * 0.44f), CornerRadius(w * 0.06f, w * 0.06f))
                drawRoundRect(Color(0xFF8C95A3), Offset(w * 0.21f, h * 0.22f), Size(w * 0.30f, h * 0.05f), CornerRadius(4f, 4f))
                drawRoundRect(Color(0xD90A84FF), Offset(w * 0.21f, h * 0.51f), Size(w * 0.40f, h * 0.04f), CornerRadius(4f, 4f))
                drawRoundRect(Color(0xFF4A515D), Offset(w * 0.21f, h * 0.61f), Size(w * 0.52f, h * 0.04f), CornerRadius(4f, 4f))
                drawRoundRect(Color(0xFF4A515D), Offset(w * 0.21f, h * 0.70f), Size(w * 0.34f, h * 0.04f), CornerRadius(4f, 4f))
            }
            ThumbArt.DOCUMENT -> {
                drawRect(Color(0xFFE9ECF0))
                drawRoundRect(Color.White, Offset(w * 0.22f, h * 0.12f), Size(w * 0.56f, h * 0.76f), CornerRadius(w * 0.05f, w * 0.05f))
                drawRoundRect(Color(0xCC007AFF), Offset(w * 0.31f, h * 0.24f), Size(w * 0.24f, h * 0.05f), CornerRadius(4f, 4f))
                drawRoundRect(Color(0xFFD3D8DF), Offset(w * 0.31f, h * 0.36f), Size(w * 0.38f, h * 0.035f), CornerRadius(4f, 4f))
                drawRoundRect(Color(0xFFD3D8DF), Offset(w * 0.31f, h * 0.44f), Size(w * 0.34f, h * 0.035f), CornerRadius(4f, 4f))
                drawRoundRect(Color(0xFFD3D8DF), Offset(w * 0.31f, h * 0.52f), Size(w * 0.38f, h * 0.035f), CornerRadius(4f, 4f))
            }
        }
    }
}
