package com.avinash.relaydisplay.data.peers

import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.avinash.relaydisplay.domain.model.TrustedPeer
import com.avinash.relaydisplay.domain.model.TrustedPeerCodec
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Persists the one peer this device trusts.
 *
 * The MVP is deliberately single-peer: the product is "my two phones", and one slot keeps the
 * trust story simple enough to reason about. The record is stored as base64 of the deterministic
 * TLV encoding, so a schema bump is a decode-time decision rather than a migration script.
 */
class TrustedPeerRepository(
    private val dataStore: DataStore<Preferences>,
    private val base64: Base64Codec = AndroidBase64,
) {

    val trustedPeer: Flow<TrustedPeer?> = dataStore.data
        .catch { cause ->
            if (cause is IOException) emit(emptyPreferences()) else throw cause
        }
        .map { prefs -> prefs[KEY]?.let { decode(it) } }

    suspend fun current(): TrustedPeer? = trustedPeer.first()

    suspend fun save(peer: TrustedPeer) {
        dataStore.edit { it[KEY] = base64.encode(TrustedPeerCodec.encode(peer)) }
    }

    /** Updates the endpoint hint after a successful connection, leaving trust material alone. */
    suspend fun rememberEndpoint(peerId: String, host: String, port: Int, subnet: String?, nowMs: Long) {
        dataStore.edit { prefs ->
            val existing = prefs[KEY]?.let { decode(it) } ?: return@edit
            if (existing.peerId != peerId) return@edit
            prefs[KEY] = base64.encode(
                TrustedPeerCodec.encode(
                    existing.copy(
                        lastHost = host,
                        lastPort = port,
                        lastSubnet = subnet,
                        lastConnectedEpochMs = nowMs,
                    ),
                ),
            )
        }
    }

    /** Deletes all trust material. Callers must also tear down any live session. */
    suspend fun forget() {
        dataStore.edit { it.remove(KEY) }
    }

    private fun decode(encoded: String): TrustedPeer? =
        base64.decode(encoded)?.let { TrustedPeerCodec.decode(it) }

    private companion object {
        val KEY = stringPreferencesKey("trusted_peer_v1")
    }
}

/** Indirection so the repository can be unit tested off-device. */
interface Base64Codec {
    fun encode(bytes: ByteArray): String
    fun decode(value: String): ByteArray?
}

object AndroidBase64 : Base64Codec {
    override fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    override fun decode(value: String): ByteArray? = try {
        Base64.decode(value, Base64.NO_WRAP)
    } catch (e: IllegalArgumentException) {
        null
    }
}

/** Pure-JVM codec used by unit tests, where android.util.Base64 is not available. */
object JvmBase64 : Base64Codec {
    override fun encode(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)
    override fun decode(value: String): ByteArray? = try {
        java.util.Base64.getDecoder().decode(value)
    } catch (e: IllegalArgumentException) {
        null
    }
}
