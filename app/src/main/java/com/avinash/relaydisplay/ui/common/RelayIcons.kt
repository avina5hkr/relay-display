package com.avinash.relaydisplay.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Icons drawn with Canvas rather than pulled from material-icons.
 *
 * The Compose BOM this project pins does not ship the icons artifact, and adding a whole icon
 * library for a handful of glyphs is not a trade worth making. A stroked path also stays exactly
 * crisp at any density, which matters on the Lenovo where a rasterised asset gets resampled.
 */

/** The settings gear: a toothed ring with a hole. */
@Composable
fun SettingsIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = DefaultIconSize,
) {
    Canvas(modifier.size(size)) { drawGear(tint) }
}

/** A left-pointing chevron, used as Back. */
@Composable
fun BackIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = DefaultIconSize,
) {
    Canvas(modifier.size(size)) { drawChevronLeft(tint) }
}

/**
 * Every glyph the app needs, as one enum.
 *
 * One switch in one place means a new screen cannot invent a fifth way to draw a chevron, and
 * the whole set stays visually consistent: same stroke ratio, same optical weight, same corner
 * treatment at every size.
 */
enum class RelayIcon {
    SETTINGS,
    BACK,
    QR,
    TEXT,
    LINK,
    IMAGE,
    DOCUMENT,
    SCREEN_SHARE,
    CLOSE,
    COPY,
    CONNECT,
}

@Composable
fun RelayGlyph(
    icon: RelayIcon,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = DefaultIconSize,
) {
    Canvas(modifier.size(size)) {
        when (icon) {
            RelayIcon.SETTINGS -> drawGear(tint)
            RelayIcon.BACK -> drawChevronLeft(tint)
            RelayIcon.QR -> drawQr(tint)
            RelayIcon.TEXT -> drawTextLines(tint)
            RelayIcon.LINK -> drawLink(tint)
            RelayIcon.IMAGE -> drawImage(tint)
            RelayIcon.DOCUMENT -> drawDocument(tint)
            RelayIcon.SCREEN_SHARE -> drawScreenShare(tint)
            RelayIcon.CLOSE -> drawClose(tint)
            RelayIcon.COPY -> drawCopy(tint)
            RelayIcon.CONNECT -> drawConnect(tint)
        }
    }
}

val DefaultIconSize = 24.dp

/** Stroke weight as a fraction of the icon box, so every glyph reads at the same weight. */
private const val STROKE_RATIO = 0.085f

private fun DrawScope.stroke() = size.minDimension * STROKE_RATIO

/** Three finder squares and a scatter of modules: unmistakably a QR code at 32dp. */
private fun DrawScope.drawQr(tint: Color) {
    val unit = size.minDimension / 7f
    val w = stroke() * 0.9f
    fun finder(cx: Float, cy: Float) {
        drawRect(tint, Offset(cx, cy), Size(unit * 2f, unit * 2f), style = Stroke(width = w))
        drawRect(tint, Offset(cx + unit * 0.65f, cy + unit * 0.65f), Size(unit * 0.7f, unit * 0.7f))
    }
    finder(0f, 0f)
    finder(size.width - unit * 2f, 0f)
    finder(0f, size.height - unit * 2f)
    // A few data modules, placed rather than random so the icon is identical every draw.
    for ((mx, my) in listOf(4f to 4f, 5.4f to 4f, 4f to 5.4f, 5.4f to 5.6f, 3f to 6f, 6f to 3f)) {
        drawRect(tint, Offset(unit * mx, unit * my), Size(unit * 0.8f, unit * 0.8f))
    }
}

private fun DrawScope.drawTextLines(tint: Color) {
    val w = stroke()
    val left = size.width * 0.14f
    val right = size.width * 0.86f
    for ((index, fraction) in listOf(0.26f, 0.44f, 0.62f, 0.80f).withIndex()) {
        // The last line is short, which is what makes this read as a paragraph and not a menu.
        val end = if (index == 3) left + (right - left) * 0.55f else right
        drawLine(tint, Offset(left, size.height * fraction), Offset(end, size.height * fraction), w, StrokeCap.Round)
    }
}

/**
 * Two open half-capsules bridged by a bar: a chain link.
 *
 * The first attempt drew two whole rounded rects that overlapped across the middle third. Their
 * outlines crossed, and at 30dp on the Lenovo the result was a solid blob that read as neither a
 * link nor anything else. Each half is now open towards the join, so the shape stays legible at
 * tile size.
 */
private fun DrawScope.drawLink(tint: Color) {
    val w = stroke()
    val r = size.minDimension * 0.19f
    val cy = size.height * 0.5f

    fun half(cx: Float, railEnd: Float, startAngle: Float) {
        drawArc(
            color = tint,
            startAngle = startAngle,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(cx - r, cy - r),
            size = Size(r * 2f, r * 2f),
            style = Stroke(width = w, cap = StrokeCap.Round),
        )
        for (sign in listOf(-1f, 1f)) {
            drawLine(tint, Offset(cx, cy + sign * r), Offset(railEnd, cy + sign * r), w, StrokeCap.Round)
        }
    }

    // 90 degrees is 6 o'clock in Compose, so a 180 degree sweep from there is the left half.
    half(cx = size.width * 0.28f, railEnd = size.width * 0.44f, startAngle = 90f)
    half(cx = size.width * 0.72f, railEnd = size.width * 0.56f, startAngle = 270f)
    drawLine(tint, Offset(size.width * 0.36f, cy), Offset(size.width * 0.64f, cy), w, StrokeCap.Round)
}

/** A frame with a sun and a hill: the universal "photo". */
private fun DrawScope.drawImage(tint: Color) {
    val w = stroke()
    val inset = size.minDimension * 0.12f
    drawRoundRect(
        tint,
        Offset(inset, inset),
        Size(size.width - inset * 2f, size.height - inset * 2f),
        CornerRadius(size.minDimension * 0.14f),
        style = Stroke(width = w),
    )
    drawCircle(tint, radius = size.minDimension * 0.075f, center = Offset(size.width * 0.34f, size.height * 0.34f))
    val path = androidx.compose.ui.graphics.Path().apply {
        moveTo(inset + w, size.height - inset - w)
        lineTo(size.width * 0.45f, size.height * 0.52f)
        lineTo(size.width * 0.66f, size.height * 0.74f)
        lineTo(size.width * 0.78f, size.height * 0.62f)
        lineTo(size.width - inset - w, size.height - inset - w)
    }
    drawPath(path, tint, style = Stroke(width = w, cap = StrokeCap.Round))
}

/** A page with a folded corner. */
private fun DrawScope.drawDocument(tint: Color) {
    val w = stroke()
    val left = size.width * 0.2f
    val right = size.width * 0.8f
    val top = size.height * 0.12f
    val bottom = size.height * 0.88f
    val fold = size.width * 0.22f
    val path = androidx.compose.ui.graphics.Path().apply {
        moveTo(left, top)
        lineTo(right - fold, top)
        lineTo(right, top + fold)
        lineTo(right, bottom)
        lineTo(left, bottom)
        close()
    }
    drawPath(path, tint, style = Stroke(width = w))
    // The fold itself, which is what separates "document" from "rectangle".
    drawPath(
        androidx.compose.ui.graphics.Path().apply {
            moveTo(right - fold, top)
            lineTo(right - fold, top + fold)
            lineTo(right, top + fold)
        },
        tint,
        style = Stroke(width = w),
    )
}

/** A monitor with a broadcast arc. */
private fun DrawScope.drawScreenShare(tint: Color) {
    val w = stroke()
    drawRoundRect(
        tint,
        Offset(size.width * 0.1f, size.height * 0.18f),
        Size(size.width * 0.8f, size.height * 0.5f),
        CornerRadius(size.minDimension * 0.1f),
        style = Stroke(width = w),
    )
    drawLine(
        tint,
        Offset(size.width * 0.32f, size.height * 0.86f),
        Offset(size.width * 0.68f, size.height * 0.86f),
        w,
        StrokeCap.Round,
    )
    drawLine(tint, Offset(size.width * 0.5f, size.height * 0.68f), Offset(size.width * 0.5f, size.height * 0.86f), w)
}

private fun DrawScope.drawClose(tint: Color) {
    val inset = size.minDimension * 0.26f
    val w = stroke() * 1.15f
    drawLine(tint, Offset(inset, inset), Offset(size.width - inset, size.height - inset), w, StrokeCap.Round)
    drawLine(tint, Offset(size.width - inset, inset), Offset(inset, size.height - inset), w, StrokeCap.Round)
}

private fun DrawScope.drawCopy(tint: Color) {
    val w = stroke()
    val corner = CornerRadius(size.minDimension * 0.12f)
    val side = size.minDimension * 0.56f
    drawRoundRect(tint, Offset(0f, size.height - side), Size(side, side), corner, style = Stroke(width = w))
    drawRoundRect(tint, Offset(size.width - side, 0f), Size(side, side), corner, style = Stroke(width = w))
}

/** Two arcs radiating from a dot: "reach the other device". */
private fun DrawScope.drawConnect(tint: Color) {
    val w = stroke()
    val centre = Offset(size.width / 2f, size.height * 0.78f)
    drawCircle(tint, radius = size.minDimension * 0.08f, center = centre)
    for (scale in listOf(0.34f, 0.58f)) {
        val r = size.minDimension * scale
        drawArc(
            color = tint,
            startAngle = 200f,
            sweepAngle = 140f,
            useCenter = false,
            topLeft = Offset(centre.x - r, centre.y - r),
            size = Size(r * 2f, r * 2f),
            style = Stroke(width = w, cap = StrokeCap.Round),
        )
    }
}

private fun DrawScope.drawGear(tint: Color) {
    val centre = Offset(size.width / 2f, size.height / 2f)
    val stroke = size.minDimension * 0.09f
    val ringRadius = size.minDimension * 0.28f
    val toothLength = size.minDimension * 0.16f
    val toothWidth = size.minDimension * 0.15f

    // Eight teeth at 45 degree intervals. Drawing them as rotated rounded rects keeps every
    // tooth identical, which a hand-built path would not guarantee at small sizes.
    repeat(8) { index ->
        rotate(degrees = index * 45f, pivot = centre) {
            drawRoundRect(
                color = tint,
                topLeft = Offset(
                    centre.x - toothWidth / 2f,
                    centre.y - ringRadius - toothLength,
                ),
                size = Size(toothWidth, toothLength + stroke),
                cornerRadius = CornerRadius(toothWidth * 0.35f),
            )
        }
    }

    drawCircle(color = tint, radius = ringRadius, center = centre, style = Stroke(width = stroke * 1.6f))
    // The hole. Drawn as a stroke rather than punched out so the icon works on any background.
    drawCircle(color = tint, radius = ringRadius * 0.42f, center = centre, style = Stroke(width = stroke))
}

private fun DrawScope.drawChevronLeft(tint: Color) {
    val stroke = size.minDimension * 0.11f
    val left = size.width * 0.36f
    val right = size.width * 0.64f
    val top = size.height * 0.24f
    val bottom = size.height * 0.76f
    val middle = size.height / 2f
    drawLine(tint, Offset(right, top), Offset(left, middle), strokeWidth = stroke, cap = StrokeCap.Round)
    drawLine(tint, Offset(left, middle), Offset(right, bottom), strokeWidth = stroke, cap = StrokeCap.Round)
}
