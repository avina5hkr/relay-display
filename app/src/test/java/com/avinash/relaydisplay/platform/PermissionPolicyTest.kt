package com.avinash.relaydisplay.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Representative API levels: the Lenovo (23), and each release that changed the rules. */
class PermissionPolicyTest {

    private val levels = listOf(23, 33, 34, 36, 37)

    @Test
    fun `local network permission is only requested on android 17 and later`() {
        for (sdk in levels) {
            val needs = PermissionPolicy.runtimeNeeds(RelayPurpose.LocalNetwork, sdk)
            if (sdk >= 37) {
                assertEquals("sdk $sdk", listOf(PermissionPolicy.ACCESS_LOCAL_NETWORK), needs.map { it.permission })
                assertTrue("sdk $sdk", needs.single().blocking)
            } else {
                assertTrue("sdk $sdk must not ask for a permission that does not exist", needs.isEmpty())
            }
        }
    }

    @Test
    fun `notification permission is only requested on android 13 and later`() {
        assertTrue(PermissionPolicy.runtimeNeeds(RelayPurpose.ForegroundAvailability, 23).isEmpty())
        assertTrue(PermissionPolicy.runtimeNeeds(RelayPurpose.ForegroundAvailability, 32).isEmpty())
        for (sdk in listOf(33, 34, 36, 37)) {
            assertEquals(
                "sdk $sdk",
                listOf(PermissionPolicy.POST_NOTIFICATIONS),
                PermissionPolicy.runtimeNeeds(RelayPurpose.ForegroundAvailability, sdk).map { it.permission },
            )
        }
    }

    @Test
    fun `a denied notification permission degrades rather than blocks`() {
        val verdict = PermissionPolicy.evaluate(RelayPurpose.ForegroundAvailability, 34, granted = emptySet())
        assertTrue(verdict is CapabilityVerdict.Degraded)
        assertTrue(verdict.allowsProceeding)
    }

    @Test
    fun `a denied local network permission blocks on android 17`() {
        val verdict = PermissionPolicy.evaluate(RelayPurpose.LocalNetwork, 37, granted = emptySet())
        assertTrue(verdict is CapabilityVerdict.Blocked)
        assertFalse(verdict.allowsProceeding)
        assertEquals(listOf(PermissionPolicy.ACCESS_LOCAL_NETWORK), (verdict as CapabilityVerdict.Blocked).missing)
    }

    @Test
    fun `local network is allowed without any grant below android 17`() {
        for (sdk in listOf(23, 33, 34, 36)) {
            assertEquals(
                "sdk $sdk",
                CapabilityVerdict.Allowed,
                PermissionPolicy.evaluate(RelayPurpose.LocalNetwork, sdk, granted = emptySet()),
            )
        }
    }

    @Test
    fun `granting the local network permission unblocks android 17`() {
        assertEquals(
            CapabilityVerdict.Allowed,
            PermissionPolicy.evaluate(RelayPurpose.LocalNetwork, 37, setOf(PermissionPolicy.ACCESS_LOCAL_NETWORK)),
        )
    }

    @Test
    fun `camera is required for scanning on every supported level`() {
        for (sdk in levels) {
            assertEquals(
                "sdk $sdk",
                listOf(PermissionPolicy.CAMERA),
                PermissionPolicy.blockingPermissions(RelayPurpose.QrScanning, sdk),
            )
        }
    }

    @Test
    fun `mirroring never asks for camera or storage`() {
        for (sdk in levels) {
            val perms = PermissionPolicy.runtimeNeeds(RelayPurpose.ScreenMirroring, sdk).map { it.permission }
            assertFalse("sdk $sdk", perms.contains(PermissionPolicy.CAMERA))
            assertFalse("sdk $sdk", perms.any { it.contains("STORAGE") || it.contains("MEDIA") })
        }
    }

    @Test
    fun `nothing asks for location or nearby devices`() {
        for (sdk in levels) {
            for (purpose in RelayPurpose.entries) {
                val perms = PermissionPolicy.runtimeNeeds(purpose, sdk).map { it.permission }
                assertFalse("$purpose on $sdk", perms.any { it.contains("LOCATION") })
                assertFalse("$purpose on $sdk", perms.any { it.contains("NEARBY") })
            }
        }
    }

    @Test
    fun `foreground service typing follows the platform`() {
        assertFalse(PermissionPolicy.requiresForegroundServiceType(23))
        assertFalse(PermissionPolicy.requiresForegroundServiceType(28))
        assertTrue(PermissionPolicy.requiresForegroundServiceType(29))
        assertTrue(PermissionPolicy.requiresForegroundServiceType(37))

        assertFalse(PermissionPolicy.requiresForegroundServiceTypePermission(33))
        assertTrue(PermissionPolicy.requiresForegroundServiceTypePermission(34))
        assertTrue(PermissionPolicy.requiresForegroundServiceTypePermission(37))
    }

    @Test
    fun `notification channels are only needed from oreo`() {
        assertFalse(PermissionPolicy.requiresNotificationChannel(23))
        assertFalse(PermissionPolicy.requiresNotificationChannel(25))
        assertTrue(PermissionPolicy.requiresNotificationChannel(26))
    }

    @Test
    fun `a fully granted purpose is allowed`() {
        assertEquals(
            CapabilityVerdict.Allowed,
            PermissionPolicy.evaluate(
                RelayPurpose.ForegroundAvailability,
                37,
                setOf(PermissionPolicy.POST_NOTIFICATIONS),
            ),
        )
    }
}
