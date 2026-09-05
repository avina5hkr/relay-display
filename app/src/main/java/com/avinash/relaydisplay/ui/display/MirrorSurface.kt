package com.avinash.relaydisplay.ui.display

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import com.avinash.relaydisplay.mirroring.MirrorController

/**
 * A SurfaceView the decoder renders straight into.
 *
 * A SurfaceView rather than a Composable image, because the decoder can hand frames to this
 * surface without them ever passing through the app's heap. Turning each frame into a Compose
 * bitmap would mean a full-screen allocation and a recomposition per frame, which the companion
 * phone cannot sustain.
 */
@Composable
fun MirrorSurface(
    controller: MirrorController,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().testTag("mirror_surface")) {
        AndroidView(
            factory = { context ->
                SurfaceView(context).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            controller.attachSurface(holder.surface)
                        }

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                            // The decoder scales to the surface; nothing to reconfigure here.
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            // The decoder must go before the surface it renders into.
                            controller.detachSurface()
                        }
                    })
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }

    DisposableEffect(controller) {
        onDispose { controller.detachSurface() }
    }
}
