package com.avinash.relaydisplay.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.avinash.relaydisplay.domain.model.BRIGHTNESS_SYSTEM_DEFAULT
import com.avinash.relaydisplay.domain.model.DeviceRole
import com.avinash.relaydisplay.domain.model.FitMode
import com.avinash.relaydisplay.domain.model.OperatingMode
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * Owns every lightweight preference.
 *
 * Reads never throw: a corrupt or unreadable store surfaces as [RelaySettings.readError] with
 * safe defaults still in place, because losing the role is far better than a launch crash.
 * Unknown enum values fall back through each enum's own `fromStorage`.
 */
class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    val settings: Flow<RelaySettings> = dataStore.data
        .catch { cause ->
            if (cause is IOException) {
                emit(emptyPreferences())
            } else {
                throw cause
            }
        }
        .map { prefs -> prefs.toSettings() }
        .catch { cause ->
            // Anything that survives the map (a corrupt value we failed to guard) still must not
            // take the process down; report it and keep the app usable with defaults.
            emit(RelaySettings(loaded = true, readError = cause.javaClass.simpleName))
        }

    private fun Preferences.toSettings(): RelaySettings = RelaySettings(
        loaded = true,
        role = DeviceRole.fromStorage(this[Keys.ROLE]),
        operatingMode = OperatingMode.fromStorage(this[Keys.OPERATING_MODE]),
        localDeviceName = this[Keys.DEVICE_NAME].orEmpty(),
        localDeviceId = this[Keys.DEVICE_ID].orEmpty(),
        keepScreenAwake = this[Keys.KEEP_SCREEN_AWAKE] ?: true,
        immersiveByDefault = this[Keys.IMMERSIVE] ?: true,
        defaultFitMode = FitMode.fromStorage(this[Keys.FIT_MODE]),
        windowBrightness = (this[Keys.BRIGHTNESS] ?: BRIGHTNESS_SYSTEM_DEFAULT).coerceBrightness(),
        reconnectAutomatically = this[Keys.RECONNECT] ?: true,
        blankOnDisconnect = this[Keys.BLANK_ON_DISCONNECT] ?: false,
        retainContentSeconds = (this[Keys.RETAIN_SECONDS] ?: 300).coerceIn(0, 24 * 3600),
        allowTrustedLinkOpen = this[Keys.ALLOW_TRUSTED_LINK_OPEN] ?: false,
        onboardingSeen = this[Keys.ONBOARDING_SEEN] ?: false,
        notificationRationaleSeen = this[Keys.NOTIFICATION_RATIONALE_SEEN] ?: false,
        localNetworkRationaleSeen = this[Keys.LOCAL_NETWORK_RATIONALE_SEEN] ?: false,
    )

    private fun Int.coerceBrightness(): Int =
        if (this == BRIGHTNESS_SYSTEM_DEFAULT) BRIGHTNESS_SYSTEM_DEFAULT else coerceIn(0, 100)

    suspend fun setRole(role: DeviceRole) = edit { it[Keys.ROLE] = role.storageValue }

    /**
     * Switch role and drop everything that only made sense in the old one.
     *
     * Presentation preferences, the device name and the device id survive; the operating mode
     * resets to the safe default so a fresh role never starts out advertising.
     */
    suspend fun changeRole(newRole: DeviceRole) = edit { prefs ->
        prefs[Keys.ROLE] = newRole.storageValue
        prefs[Keys.OPERATING_MODE] = OperatingMode.DEFAULT.storageValue
    }

    suspend fun setOperatingMode(mode: OperatingMode) = edit { it[Keys.OPERATING_MODE] = mode.storageValue }

    suspend fun setLocalDeviceName(name: String) = edit {
        it[Keys.DEVICE_NAME] = name.trim().take(MAX_DEVICE_NAME_CHARS)
    }

    suspend fun setLocalDeviceId(id: String) = edit { it[Keys.DEVICE_ID] = id }

    /** Creates the device id exactly once, then always returns the stored one. */
    suspend fun ensureLocalDeviceId(generate: () -> String): String {
        var result = ""
        dataStore.edit { prefs ->
            val existing = prefs[Keys.DEVICE_ID]
            result = if (existing.isNullOrEmpty()) generate().also { prefs[Keys.DEVICE_ID] = it } else existing
        }
        return result
    }

    suspend fun setKeepScreenAwake(value: Boolean) = edit { it[Keys.KEEP_SCREEN_AWAKE] = value }
    suspend fun setImmersiveByDefault(value: Boolean) = edit { it[Keys.IMMERSIVE] = value }
    suspend fun setDefaultFitMode(value: FitMode) = edit { it[Keys.FIT_MODE] = value.storageValue }
    suspend fun setWindowBrightness(value: Int) = edit {
        it[Keys.BRIGHTNESS] = if (value == BRIGHTNESS_SYSTEM_DEFAULT) BRIGHTNESS_SYSTEM_DEFAULT else value.coerceIn(0, 100)
    }
    suspend fun setReconnectAutomatically(value: Boolean) = edit { it[Keys.RECONNECT] = value }
    suspend fun setBlankOnDisconnect(value: Boolean) = edit { it[Keys.BLANK_ON_DISCONNECT] = value }
    suspend fun setRetainContentSeconds(value: Int) = edit { it[Keys.RETAIN_SECONDS] = value.coerceIn(0, 24 * 3600) }
    suspend fun setAllowTrustedLinkOpen(value: Boolean) = edit { it[Keys.ALLOW_TRUSTED_LINK_OPEN] = value }
    suspend fun setOnboardingSeen(value: Boolean) = edit { it[Keys.ONBOARDING_SEEN] = value }
    suspend fun setNotificationRationaleSeen(value: Boolean) = edit { it[Keys.NOTIFICATION_RATIONALE_SEEN] = value }
    suspend fun setLocalNetworkRationaleSeen(value: Boolean) = edit { it[Keys.LOCAL_NETWORK_RATIONALE_SEEN] = value }

    /**
     * Wipes every preference. Used by instrumentation tests to get a genuine first-launch state,
     * and available for a future "reset this phone" action.
     */
    suspend fun clearAll() {
        dataStore.edit { it.clear() }
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        dataStore.edit(block)
    }

    private object Keys {
        val ROLE = stringPreferencesKey("device_role")
        val OPERATING_MODE = stringPreferencesKey("operating_mode")
        val DEVICE_NAME = stringPreferencesKey("local_device_name")
        val DEVICE_ID = stringPreferencesKey("local_device_id")
        val KEEP_SCREEN_AWAKE = booleanPreferencesKey("keep_screen_awake")
        val IMMERSIVE = booleanPreferencesKey("immersive_default")
        val FIT_MODE = stringPreferencesKey("default_fit_mode")
        val BRIGHTNESS = intPreferencesKey("window_brightness")
        val RECONNECT = booleanPreferencesKey("reconnect_automatically")
        val BLANK_ON_DISCONNECT = booleanPreferencesKey("blank_on_disconnect")
        val RETAIN_SECONDS = intPreferencesKey("retain_content_seconds")
        val ALLOW_TRUSTED_LINK_OPEN = booleanPreferencesKey("allow_trusted_link_open")
        val ONBOARDING_SEEN = booleanPreferencesKey("onboarding_seen")
        val NOTIFICATION_RATIONALE_SEEN = booleanPreferencesKey("notification_rationale_seen")
        val LOCAL_NETWORK_RATIONALE_SEEN = booleanPreferencesKey("local_network_rationale_seen")
    }

    companion object {
        const val MAX_DEVICE_NAME_CHARS = 32
    }
}
