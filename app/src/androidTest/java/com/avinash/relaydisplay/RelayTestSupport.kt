package com.avinash.relaydisplay

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.avinash.relaydisplay.app.AppContainer
import com.avinash.relaydisplay.app.RelayApp
import kotlinx.coroutines.runBlocking

/** Shared helpers so every instrumentation test starts from a genuine first-launch state. */
object RelayTestSupport {

    val container: AppContainer
        get() = ApplicationProvider.getApplicationContext<RelayApp>().container

    /**
     * Wakes the screen and dismisses a non-secure keyguard.
     *
     * **Do not call this from `@Before`.** On a device with a secure lock, `wm dismiss-keyguard`
     * raises the unlock prompt, which pushes the test activity to the background and surfaces as
     * an intermittent `No compose hierarchies found in the app`. Observed as a real flake on the
     * S22. The device simply has to be unlocked before the suite runs; this helper is kept only
     * for the swipe-to-unlock case, invoked deliberately.
     */
    fun wakeAndUnlock() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
        automation.executeShellCommand("wm dismiss-keyguard").close()
    }

    /** A plausible trusted peer, so the Forget flow has something to forget. */
    suspend fun saveFakePeer() {
        container.trustedPeerRepository.save(
            com.avinash.relaydisplay.domain.model.TrustedPeer(
                peerId = "test-display",
                displayName = "Test Display",
                fingerprint = ByteArray(32) { it.toByte() },
                identityPublicKey = ByteArray(64) { it.toByte() },
                role = com.avinash.relaydisplay.domain.model.DeviceRole.DISPLAY,
                protocolMajor = 1,
                protocolMinor = 0,
                capabilities = setOf("text", "qr"),
            ),
        )
    }

    /** Clears persisted role, mode and trust. Call from @Before. */
    fun resetPersistedState() = runBlocking {
        container.sessionCoordinator.hardReset("test reset")
        container.trustedPeerRepository.forget()
        container.settingsRepository.clearAll()
    }
}
