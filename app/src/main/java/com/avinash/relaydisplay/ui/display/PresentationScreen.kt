package com.avinash.relaydisplay.ui.display

import android.app.Activity
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.avinash.relaydisplay.content.ImageDecoding
import com.avinash.relaydisplay.content.PdfPageSource
import com.avinash.relaydisplay.content.PresentationOptions
import com.avinash.relaydisplay.content.PresentedContent
import com.avinash.relaydisplay.domain.model.BRIGHTNESS_SYSTEM_DEFAULT
import com.avinash.relaydisplay.domain.model.FitMode
import com.avinash.relaydisplay.ui.common.FullScreenQr
import com.avinash.relaydisplay.ui.common.RelayDimens
import com.avinash.relaydisplay.ui.theme.PresentationBackground
import com.avinash.relaydisplay.ui.theme.PresentationForeground
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The full-screen presentation on the companion phone.
 *
 * Everything here is optimised for the slower device: a flat black background, no animation, no
 * shadow, no blur, and a state shape narrow enough that a transfer-progress update on the
 * waiting screen cannot recompose a decoded image.
 */
@Composable
fun PresentationSurface(
    content: PresentedContent,
    options: PresentationOptions,
    disconnected: Boolean,
    onExitRequested: () -> Unit,
    modifier: Modifier = Modifier,
    mirrorController: com.avinash.relaydisplay.mirroring.MirrorController? = null,
) {
    ApplyWindowEffects(options)

    val copyText = rememberTextCopier()
    var notice by remember { mutableStateOf<String?>(null) }

    // Back closes the presentation directly. It used to consume the first press to leave
    // immersive mode, which is precisely why the phone looked frozen: the user pressed Back,
    // nothing visible happened, and there was no other way out.
    BackHandler(enabled = true) { onExitRequested() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PresentationBackground)
            .testTag("presentation_surface"),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxSize().rotate(options.rotation.degrees.toFloat())) {
            when (content) {
                PresentedContent.Waiting -> PresentationMessage("Waiting for content")
                PresentedContent.Blank -> Box(Modifier.fillMaxSize().background(Color.Black))
                is PresentedContent.Text -> PresentedText(content.text)
                is PresentedContent.Qr -> QrPresentation(content)
                is PresentedContent.Link -> LinkPresentation(content)
                is PresentedContent.Image -> ImagePresentation(content, options.fitMode)
                is PresentedContent.Pdf -> PdfPresentation(content)
                PresentedContent.Mirror ->
                    if (mirrorController != null) {
                        MirrorSurface(mirrorController)
                    } else {
                        PresentationMessage("Screen sharing is starting")
                    }
            }
        }

        // Always reachable, above the content, inside every inset.
        PresentationToolbar(
            canCopy = content.copyableText != null,
            onCopy = {
                content.copyableText?.let {
                    copyText(it)
                    notice = "Text copied"
                }
            },
            onClose = onExitRequested,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        PresentationSnackbar(
            message = notice,
            onShown = { notice = null },
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        if (disconnected) {
            // Subtle, and only ever a hint: it must not obscure whatever is being shown.
            Text(
                text = "Controller disconnected",
                color = PresentationForeground.copy(alpha = 0.55f),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp)
                    .testTag("disconnected_overlay"),
            )
        }
    }
}

@Composable
private fun PresentationMessage(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            color = PresentationForeground.copy(alpha = 0.7f),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(RelayDimens.ScreenPadding),
        )
    }
}

@Composable
private fun PresentedText(text: String) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            // Extra top padding keeps the text clear of the toolbar strip.
            .padding(
                start = RelayDimens.ScreenPadding,
                end = RelayDimens.ScreenPadding,
                top = TOOLBAR_CLEARANCE,
                bottom = RelayDimens.ScreenPadding,
            ),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        // Selectable so the user can grab part of it; Copy handles the whole thing.
        SelectionContainer {
            Text(
                text = text,
                color = PresentationForeground,
                style = MaterialTheme.typography.displaySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().testTag("presented_text"),
            )
        }
    }
}

/** Vertical space the toolbar occupies, reserved by content that would otherwise run under it. */
private val TOOLBAR_CLEARANCE = 64.dp

@Composable
private fun QrPresentation(content: PresentedContent.Qr) {
    Column(
        Modifier.fillMaxSize().padding(top = TOOLBAR_CLEARANCE),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            FullScreenQr(content.payload)
        }
        content.caption?.takeIf { it.isNotBlank() }?.let { caption ->
            Text(
                text = caption,
                color = PresentationForeground,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = RelayDimens.ScreenPadding, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun LinkPresentation(content: PresentedContent.Link) {
    Column(
        Modifier.fillMaxSize().padding(top = TOOLBAR_CLEARANCE),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            FullScreenQr(content.url)
        }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = RelayDimens.ScreenPadding, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The host, not the full URL: a long path is where a misleading link hides.
            Text(
                text = content.host ?: "unknown site",
                color = PresentationForeground,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            content.title?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    color = PresentationForeground.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun ImagePresentation(content: PresentedContent.Image, fitMode: FitMode) {
    var containerWidth by remember { mutableStateOf(0) }
    var containerHeight by remember { mutableStateOf(0) }

    Box(
        Modifier
            .fillMaxSize()
            .testTag("presented_image"),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .onSized { w, h ->
                    containerWidth = w
                    containerHeight = h
                },
        ) {
            if (containerWidth > 0 && containerHeight > 0) {
                // Decoding is keyed on the file *and* the target size, so a rotation decodes once
                // more rather than on every recomposition.
                val bitmap by produceState<Bitmap?>(null, content.file, containerWidth, containerHeight) {
                    value = withContext(Dispatchers.IO) {
                        ImageDecoding.decode(content.file, containerWidth, containerHeight)
                            ?.let { ImageDecoding.applyRotation(it) }
                    }
                }
                val image = bitmap
                if (image == null) {
                    PresentationMessage("Preparing image")
                } else {
                    Image(
                        bitmap = image.asImageBitmap(),
                        contentDescription = content.displayName,
                        contentScale = if (fitMode == FitMode.FILL) ContentScale.Crop else ContentScale.Fit,
                        filterQuality = FilterQuality.Medium,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun PdfPresentation(content: PresentedContent.Pdf) {
    var containerWidth by remember { mutableStateOf(0) }
    var containerHeight by remember { mutableStateOf(0) }

    Box(
        Modifier
            .fillMaxSize()
            .testTag("presented_pdf")
            .onSized { w, h ->
                containerWidth = w
                containerHeight = h
            },
        contentAlignment = Alignment.Center,
    ) {
        if (containerWidth > 0 && containerHeight > 0) {
            val rendered by produceState<PdfRenderResult?>(
                null, content.file, content.pageIndex, containerWidth, containerHeight,
            ) {
                value = withContext(Dispatchers.IO) { renderPdfPage(content.file, content.pageIndex, containerWidth, containerHeight) }
            }
            when (val result = rendered) {
                null -> PresentationMessage("Preparing page")
                is PdfRenderResult.Failed -> PresentationMessage(result.reason)
                is PdfRenderResult.Rendered -> Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Image(
                            bitmap = result.bitmap.asImageBitmap(),
                            contentDescription = "${content.displayName}, page ${content.pageIndex + 1}",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Text(
                        text = "Page ${content.pageIndex + 1} of ${content.pageCount}",
                        color = PresentationForeground.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                }
            }
        }
    }
}

private sealed interface PdfRenderResult {
    data class Rendered(val bitmap: Bitmap) : PdfRenderResult
    data class Failed(val reason: String) : PdfRenderResult
}

/**
 * Opens, renders one page, and closes.
 *
 * Deliberately not holding the renderer open between pages: a `PdfRenderer` pins a file
 * descriptor, and this display may sit on one page for a very long time.
 */
private fun renderPdfPage(file: File, pageIndex: Int, width: Int, height: Int): PdfRenderResult =
    when (val opened = PdfPageSource.open(file)) {
        is PdfPageSource.OpenResult.Failed -> PdfRenderResult.Failed(opened.reason)
        is PdfPageSource.OpenResult.Opened -> opened.source.use { source ->
            source.render(pageIndex, width, height)
                // Copy before the source recycles its cached bitmap on close.
                ?.let { PdfRenderResult.Rendered(it.copy(Bitmap.Config.ARGB_8888, false)) }
                ?: PdfRenderResult.Failed("This page could not be rendered")
        }
    }

/**
 * Applies immersive mode, screen-awake and in-app brightness, and undoes all three on the way
 * out. Only the window's own brightness is touched, never the system setting, so no permission
 * is involved.
 */
@Composable
private fun ApplyWindowEffects(options: PresentationOptions) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    DisposableEffect(activity, options.keepScreenAwake) {
        val window = activity?.window
        if (window != null && options.keepScreenAwake) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    DisposableEffect(activity, options.brightness) {
        val window = activity?.window
        val previous = window?.attributes?.screenBrightness
        if (window != null) {
            val attributes = window.attributes
            attributes.screenBrightness = if (options.brightness == BRIGHTNESS_SYSTEM_DEFAULT) {
                WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            } else {
                (options.brightness / 100f).coerceIn(0.01f, 1f)
            }
            window.attributes = attributes
        }
        onDispose {
            if (window != null && previous != null) {
                val attributes = window.attributes
                attributes.screenBrightness = previous
                window.attributes = attributes
            }
        }
    }

    DisposableEffect(activity, options.immersive) {
        val window = activity?.window
        val controller = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
        if (controller != null) {
            if (options.immersive) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

private tailrec fun android.content.Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Reports the composable's pixel size, so decoding can target it exactly. */
private fun Modifier.onSized(onSized: (Int, Int) -> Unit): Modifier =
    this.onSizeChanged { onSized(it.width, it.height) }
