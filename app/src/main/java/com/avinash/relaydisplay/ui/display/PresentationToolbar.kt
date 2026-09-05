package com.avinash.relaydisplay.ui.display

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.Canvas
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * The controls shown over a presentation on the companion display.
 *
 * Design constraints, all from real-device testing:
 *  - Always reachable without a gesture. The Lenovo runs Android 7 and the previous
 *    back-gesture-only exit made the phone look frozen.
 *  - 48dp minimum touch target even though the icon is 24dp, because the target phone is small
 *    and the person using it may be at arm's length.
 *  - Positioned inside status bar, display cutout *and* navigation bar insets, so it is never
 *    under a notch or a gesture bar.
 *  - Only actions that mean something for the current content are shown. A Copy button over an
 *    image would be a lie.
 *  - Drawn in a top strip that content layouts already avoid, so it never covers a QR code.
 */
@Composable
fun PresentationToolbar(
    canCopy: Boolean,
    onCopy: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            // union() so the strip clears a notch and the status bar together, in any rotation.
            .windowInsetsPadding(
                WindowInsets.statusBars
                    .union(WindowInsets.displayCutout)
                    .union(WindowInsets.navigationBars),
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (canCopy) {
            ToolbarButton(
                icon = ToolbarIcon.COPY,
                description = "Copy text",
                testTag = "presentation_copy",
                onClick = onCopy,
            )
        }
        ToolbarButton(
            icon = ToolbarIcon.CLOSE,
            description = "Close presentation",
            testTag = "presentation_close",
            onClick = onClose,
        )
    }
}

/**
 * Icons drawn directly rather than pulled from material-icons.
 *
 * Two shapes do not justify a dependency, and a Canvas stroke stays perfectly crisp at any
 * density -- including the Lenovo's, where a rasterised asset would be resampled.
 */
private enum class ToolbarIcon { CLOSE, COPY }

/**
 * A 48dp target around a 24dp icon.
 *
 * The translucent scrim behind it keeps the icon visible over both a white QR code and a dark
 * photo without having to know what is underneath.
 */
@Composable
private fun ToolbarButton(
    icon: ToolbarIcon,
    description: String,
    testTag: String,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .size(MIN_TOUCH_TARGET)
            .background(SCRIM, CircleShape)
            .testTag(testTag)
            .semantics { contentDescription = description },
        colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White),
    ) {
        Canvas(Modifier.size(ICON_SIZE)) {
            when (icon) {
                ToolbarIcon.CLOSE -> drawClose()
                ToolbarIcon.COPY -> drawCopy()
            }
        }
    }
}

private fun DrawScope.drawClose() {
    val inset = size.minDimension * 0.24f
    val strokeWidth = size.minDimension * 0.1f
    drawLine(
        color = Color.White,
        start = Offset(inset, inset),
        end = Offset(size.width - inset, size.height - inset),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = Color.White,
        start = Offset(size.width - inset, inset),
        end = Offset(inset, size.height - inset),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round,
    )
}

/** Two overlapping rounded rectangles: the conventional "copy" glyph. */
private fun DrawScope.drawCopy() {
    val strokeWidth = size.minDimension * 0.1f
    val corner = CornerRadius(size.minDimension * 0.12f)
    val boxSide = size.minDimension * 0.56f
    val box = Size(boxSide, boxSide)
    // Back sheet, bottom-left; front sheet, top-right. The overlap reads as "two of these".
    drawRoundRect(
        color = Color.White,
        topLeft = Offset(0f, size.height - boxSide),
        size = box,
        cornerRadius = corner,
        style = Stroke(width = strokeWidth),
    )
    drawRoundRect(
        color = Color.White,
        topLeft = Offset(size.width - boxSide, 0f),
        size = box,
        cornerRadius = corner,
        style = Stroke(width = strokeWidth),
    )
}

private val ICON_SIZE = 24.dp

private val MIN_TOUCH_TARGET = 48.dp
private val SCRIM = Color(0x99000000)

/**
 * Copies text to the clipboard and confirms it.
 *
 * Deliberate choices:
 *  - the clip label is a fixed generic string, never the content, because the label is visible
 *    in the system clipboard UI on some builds;
 *  - the text is placed verbatim so Unicode, newlines and spacing survive exactly;
 *  - nothing is logged, not even a length;
 *  - no network message is sent: copying is a purely local act on already-received data.
 */
@Composable
fun rememberTextCopier(): (String) -> Unit {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    return remember(clipboard) {
        { text: String ->
            if (text.isNotEmpty()) {
                scope.launch {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(CLIP_LABEL, text)))
                }
            }
        }
    }
}

private const val CLIP_LABEL = "RelayDisplay content"

/** A brief confirmation that does not steal focus or block the presentation. */
@Composable
fun PresentationSnackbar(
    message: String?,
    onShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hostState = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        hostState.showSnackbar(message = text, duration = SnackbarDuration.Short)
        onShown()
    }
    Box(modifier) {
        SnackbarHost(hostState) { data ->
            Snackbar(
                containerColor = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier.testTag("presentation_snackbar"),
            ) {
                Text(data.visuals.message)
            }
        }
    }
}
