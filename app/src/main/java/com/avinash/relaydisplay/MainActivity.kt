package com.avinash.relaydisplay

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.avinash.relaydisplay.ui.RelayAppRoot
import com.avinash.relaydisplay.ui.SharedPayload
import com.avinash.relaydisplay.ui.theme.RelayDisplayTheme

/**
 * The single Activity.
 *
 * It owns no networking and no session state; everything long-lived lives in the application
 * container, so rotation and process recreation cost a redraw and nothing else.
 *
 * Its other job is being the app's only exported component, which makes it the boundary where
 * external intents are treated as untrusted input: [parseShare] validates before anything
 * reaches the UI, and `onNewIntent` matters because `singleTask` means a second share arrives
 * at a running instance rather than a fresh one.
 */
class MainActivity : ComponentActivity() {

    private var sharedPayload by mutableStateOf<SharedPayload?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        sharedPayload = parseShare(intent)

        setContent {
            RelayDisplayTheme {
                val payload = sharedPayload
                RelayAppRoot(
                    sharedPayload = payload,
                    onSharedPayloadConsumed = { sharedPayload = null },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        parseShare(intent)?.let { sharedPayload = it }
    }

    /**
     * Turns an incoming intent into a payload, or null.
     *
     * Only ACTION_SEND with text is accepted. A shared *file* deliberately is not: its URI grant
     * is tied to this one intent, and the send flow uses its own picker so nothing has to survive
     * a process death holding a permission it may no longer have.
     *
     * The text is bounded before it reaches a composable. It is not scheme-checked here, because
     * shared text is shown as text; the link path applies [UrlValidation] at the point where the
     * user actually asks for it to be treated as a link.
     */
    private fun parseShare(intent: Intent?): SharedPayload? {
        if (intent == null || intent.action != Intent.ACTION_SEND) return null

        val raw = try {
            intent.getStringExtra(Intent.EXTRA_TEXT)
        } catch (e: RuntimeException) {
            // A malformed extras Bundle from another app must not take this app down.
            null
        } ?: return null

        val text = raw.take(MAX_SHARED_TEXT_CHARS).trim()
        return if (text.isEmpty()) null else SharedPayload(text = text)
    }

    private companion object {
        const val MAX_SHARED_TEXT_CHARS = 4000
    }
}
