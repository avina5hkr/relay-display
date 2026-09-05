package com.avinash.relaydisplay.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.avinash.relaydisplay.content.QrEncodeResult
import com.avinash.relaydisplay.content.QrEncoder
import com.avinash.relaydisplay.content.QrMatrix
import kotlin.math.floor

/**
 * Draws a QR code as exact rectangles on a Canvas.
 *
 * Not a Bitmap, and deliberately so. Scaling a small bitmap up interpolates the module edges into
 * grey, which is exactly what makes a code fail to scan from another phone's camera. Here the
 * module size is floored to a whole number of pixels and the grid is centred in the leftover
 * space, so every module has hard edges at any size.
 *
 * Colours are fixed black on white regardless of theme: inverted or tinted codes are a common
 * cause of scan failures on older camera stacks like the companion phone's.
 */
@Composable
fun QrCodeView(
    payload: String,
    modifier: Modifier = Modifier,
    contentDescription: String = "QR code",
    onEncodeFailure: @Composable (() -> Unit)? = null,
) {
    val result = remember(payload) { QrEncoder.encode(payload) }
    when (result) {
        is QrEncodeResult.Success -> QrMatrixView(result.matrix, modifier, contentDescription)
        is QrEncodeResult.Failure -> {
            if (onEncodeFailure != null) {
                onEncodeFailure()
            } else {
                Box(modifier, contentAlignment = Alignment.Center) {
                    Text(
                        "This content is too long to show as a QR code.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
fun QrMatrixView(
    matrix: QrMatrix,
    modifier: Modifier = Modifier,
    contentDescription: String = "QR code",
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .testTag("qr_code")
            .semantics { this.contentDescription = contentDescription },
    ) {
        val available = minOf(size.width, size.height)
        // Floor to whole pixels: a fractional module size is what produces soft edges.
        val module = floor(available / matrix.size)
        if (module < 1f) return@Canvas
        val drawn = module * matrix.size
        val originX = (size.width - drawn) / 2f
        val originY = (size.height - drawn) / 2f

        // The quiet zone is part of the matrix, so filling the whole square gives it for free.
        drawRect(color = Color.White, topLeft = Offset(originX, originY), size = Size(drawn, drawn))

        for (y in 0 until matrix.size) {
            for (x in 0 until matrix.size) {
                if (!matrix[x, y]) continue
                drawRect(
                    color = Color.Black,
                    topLeft = Offset(originX + x * module, originY + y * module),
                    size = Size(module, module),
                )
            }
        }
    }
}

/** A QR code sized for the waiting screen, with the payload underneath for manual entry. */
@Composable
fun PairingCodeView(
    uri: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        QrCodeView(
            payload = uri,
            modifier = Modifier.fillMaxWidth(0.85f),
            contentDescription = "Pairing code. Scan this from the controller phone.",
        )
    }
}

/** Fills the whole screen with a code, for the presentation mode. */
@Composable
fun FullScreenQr(payload: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxSize()
            .testTag("fullscreen_qr"),
        contentAlignment = Alignment.Center,
    ) {
        QrCodeView(
            payload = payload,
            // A little short of the full width so the quiet zone is never clipped by a rounded
            // corner or a display cutout.
            modifier = Modifier.fillMaxWidth(0.92f),
        )
    }
}

internal val QrMinimumMargin = 16.dp
