package com.avinash.relaydisplay

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.avinash.relaydisplay.content.PresentationOptions
import com.avinash.relaydisplay.content.PresentedContent
import com.avinash.relaydisplay.ui.display.PresentationSurface
import com.avinash.relaydisplay.ui.theme.RelayDisplayTheme
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Display's presentation controls, on a real device.
 *
 * These pin the two reported problems: the Lenovo could not close received text without help
 * from the Controller, and there was no way to copy it.
 */
@RunWith(AndroidJUnit4::class)
class PresentationToolbarTest {

    @get:Rule val compose = createComposeRule()

    private val closes = AtomicInteger(0)

    @Before
    fun setUp() {
        // Deliberately no keyguard poking here: on a device with a secure lock,
        // `wm dismiss-keyguard` raises the unlock prompt and pushes the test activity away,
        // which shows up as "no compose hierarchies found". The device must simply be unlocked.
        closes.set(0)
    }

    private fun show(content: PresentedContent) {
        compose.setContent {
            RelayDisplayTheme {
                PresentationSurface(
                    content = content,
                    // Immersive off in tests: the system-bar controller is irrelevant here and
                    // leaving it on makes the harness fight the window insets.
                    options = PresentationOptions(immersive = false, keepScreenAwake = false),
                    disconnected = false,
                    onExitRequested = { closes.incrementAndGet() },
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun textPresentationOffersCopyAndClose() {
        show(PresentedContent.Text("hello display"))
        compose.onNodeWithTag("presentation_copy").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithTag("presentation_close").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithTag("presented_text").assertIsDisplayed()
    }

    @Test
    fun theControlsAreLabelledForScreenReaders() {
        show(PresentedContent.Text("hello"))
        compose.onNodeWithContentDescription("Close presentation").assertIsDisplayed()
        compose.onNodeWithContentDescription("Copy text").assertIsDisplayed()
    }

    @Test
    fun theTouchTargetsAreBigEnoughForTheSmallerPhone() {
        show(PresentedContent.Text("hello"))
        // 48dp minimum even though the drawn icon is 24dp.
        compose.onNodeWithTag("presentation_close")
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("presentation_copy")
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun closingAsksTheHostToCloseExactlyOnce() {
        show(PresentedContent.Text("hello"))
        compose.onNodeWithTag("presentation_close").performClick()
        compose.waitForIdle()
        assertEquals(1, closes.get())
    }

    @Test
    fun backAlsoClosesThePresentation() {
        show(PresentedContent.Text("hello"))
        // Back used to be swallowed by immersive mode, which is why the phone looked stuck.
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK").close()
        compose.waitUntil(5_000) { closes.get() >= 1 }
        assertTrue(closes.get() >= 1)
    }

    @Test
    fun copyPutsTheExactTextOnTheClipboard() {
        val text = "Line one\nLine two\tTabbed  double  spaces\nÜnïcödé ✓ 中文 🎉\nend"
        show(PresentedContent.Text(text))
        compose.onNodeWithTag("presentation_copy").performClick()
        compose.waitForIdle()

        val copied = readClipboard()
        assertEquals("copy must preserve every character exactly", text, copied)
    }

    @Test
    fun copyIsOfferedForQrPayloadAndLink() {
        show(PresentedContent.Qr("https://example.com/scan?a=b", null))
        compose.onNodeWithTag("presentation_copy").assertIsDisplayed()
        compose.onNodeWithTag("presentation_copy").performClick()
        compose.waitForIdle()
        assertEquals("https://example.com/scan?a=b", readClipboard())
    }

    @Test
    fun copyIsNotOfferedWhereItWouldBeMeaningless() {
        show(PresentedContent.Image(UUID.randomUUID(), File("/does/not/exist.png"), "photo.png"))
        // A Copy button over an image would do nothing; it must not be shown at all.
        compose.onNodeWithTag("presentation_copy").assertDoesNotExist()
        compose.onNodeWithTag("presentation_close").assertIsDisplayed()
    }

    @Test
    fun mirroringOffersCloseButNotCopy() {
        show(PresentedContent.Mirror)
        compose.onNodeWithTag("presentation_copy").assertDoesNotExist()
        compose.onNodeWithTag("presentation_close").assertIsDisplayed()
    }

    @Test
    fun emptyTextIsHandledSafely() {
        show(PresentedContent.Text(""))
        // Copy is offered (the content type supports it) but must not crash or clear the clip.
        compose.onNodeWithTag("presentation_copy").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("presentation_close").assertIsDisplayed()
    }

    private fun readClipboard(): String? {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var result: String? = null
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            result = manager.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        }
        return result
    }
}
