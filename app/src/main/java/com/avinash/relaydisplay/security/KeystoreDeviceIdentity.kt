package com.avinash.relaydisplay.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * The device's long-lived identity, held in the Android Keystore.
 *
 * The private key is generated inside the keystore and never leaves it, so a filesystem dump of
 * the app's data directory does not yield a usable identity. There is deliberately no software
 * fallback: persisting a plaintext private key would quietly downgrade the guarantee the whole
 * pairing model rests on. If the keystore is unusable, [Provider.load] reports that and the UI
 * blocks pairing with an explanation.
 */
class KeystoreDeviceIdentity private constructor(
    private val privateKey: PrivateKey,
    publicKey: PublicKey,
) : DeviceIdentity {

    override val publicKeyEncoded: ByteArray = publicKey.encoded
    override val fingerprint: ByteArray = HandshakeCrypto.fingerprintOf(publicKeyEncoded)

    override fun sign(data: ByteArray): ByteArray =
        Signature.getInstance(HandshakeCrypto.SIGNATURE_ALGORITHM).run {
            initSign(privateKey)
            update(data)
            sign()
        }

    /** Loading result, so callers handle an unusable keystore instead of catching everywhere. */
    sealed interface Result {
        data class Available(val identity: DeviceIdentity) : Result
        data class Unavailable(val reason: String) : Result
    }

    object Provider {
        private const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "relaydisplay.identity.v1"

        @Volatile
        private var cached: DeviceIdentity? = null

        /** Loads the identity, creating it on first use. Safe to call repeatedly. */
        @Synchronized
        fun load(): Result {
            cached?.let { return Result.Available(it) }
            return try {
                val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
                val entry = keyStore.getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry
                    ?: run {
                        generate()
                        keyStore.load(null)
                        keyStore.getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry
                    }
                    ?: return Result.Unavailable("keystore did not return the generated identity")
                val identity = KeystoreDeviceIdentity(entry.privateKey, entry.certificate.publicKey)
                cached = identity
                Result.Available(identity)
            } catch (e: GeneralSecurityException) {
                Result.Unavailable(e.javaClass.simpleName)
            } catch (e: java.io.IOException) {
                Result.Unavailable(e.javaClass.simpleName)
            }
        }

        /** Deletes the identity. Only used by a full reset; pairing must be redone afterwards. */
        @Synchronized
        fun delete() {
            cached = null
            try {
                KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(ALIAS)
            } catch (e: KeyStoreException) {
                // Nothing to delete, or the keystore is unusable; either way there is no identity.
            } catch (e: GeneralSecurityException) {
                // Same.
            } catch (e: java.io.IOException) {
                // Same.
            }
        }

        private fun generate() {
            val generator = java.security.KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC,
                KEYSTORE,
            )
            val spec = KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec(HandshakeCrypto.CURVE))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .apply {
                    // Keep the key usable while the screen is locked: the display phone sits
                    // locked most of the time and still has to complete handshakes.
                    if (Build.VERSION.SDK_INT >= 28) setUnlockedDeviceRequired(false)
                }
                .build()
            generator.initialize(spec)
            generator.generateKeyPair()
        }
    }
}
