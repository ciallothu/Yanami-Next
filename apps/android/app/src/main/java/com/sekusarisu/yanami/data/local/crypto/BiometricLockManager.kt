package com.sekusarisu.yanami.data.local.crypto

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Creates a cryptographic proof for the app lock.
 *
 * The key is auth-per-use and never leaves Android Keystore. A successful prompt is accepted only
 * when the exact [Cipher] returned by [BiometricPrompt.AuthenticationResult] can seal or open the
 * versioned verifier. This prevents a hooked UI callback from becoming an unlock primitive.
 */
class BiometricLockManager {

    class AuthenticationSession internal constructor(
            internal val cipher: Cipher,
            internal val encryptedVerifier: ByteArray?,
            internal val encodedEnvelope: String?,
            internal val keyId: String
    ) {
        val cryptoObject: BiometricPrompt.CryptoObject
            get() = BiometricPrompt.CryptoObject(cipher)
    }

    val allowedAuthenticators: Int
        get() =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                            BiometricManager.Authenticators.DEVICE_CREDENTIAL
                } else {
                    // CryptoObject + DEVICE_CREDENTIAL is unsupported before Android 11.
                    BiometricManager.Authenticators.BIOMETRIC_STRONG
                }

    /**
     * Prepares either first-time enrollment or verification of an existing envelope.
     *
     * Malformed, missing, or invalidated material starts a new enrollment session under a fresh
     * random alias. That recovery still requires a successful auth-bound encryption operation and
     * never unlocks merely because the old material was unavailable.
     */
    @Throws(GeneralSecurityException::class)
    fun prepareAuthentication(encodedEnvelope: String?): AuthenticationSession {
        val envelope = encodedEnvelope?.let(BiometricEnvelopeCodec::decode)
                ?: return prepareEnrollment()
        return try {
            val key = getExistingKey(aliasFor(envelope.keyId)) ?: return prepareEnrollment()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, envelope.iv))
            cipher.updateAAD(aadFor(envelope.keyId))
            AuthenticationSession(
                    cipher = cipher,
                    encryptedVerifier = envelope.ciphertext,
                    encodedEnvelope = encodedEnvelope,
                    keyId = envelope.keyId
            )
        } catch (_: GeneralSecurityException) {
            prepareEnrollment()
        } catch (_: RuntimeException) {
            prepareEnrollment()
        }
    }

    /**
     * Completes the authenticated operation and returns the verified envelope, or `null` on any
     * mismatch. Callers must not unlock or change the setting when this method returns `null`.
     */
    fun completeAuthentication(
            session: AuthenticationSession,
            result: BiometricPrompt.AuthenticationResult
    ): String? {
        val authenticatedCipher = result.cryptoObject?.cipher ?: return null
        if (authenticatedCipher !== session.cipher) return null

        return try {
            val encryptedVerifier = session.encryptedVerifier
            val verifiedEnvelope =
                    if (encryptedVerifier == null) {
                        val ciphertext = authenticatedCipher.doFinal(VERIFIER)
                        BiometricEnvelopeCodec.encode(
                                keyId = session.keyId,
                                iv = authenticatedCipher.iv,
                                ciphertext = ciphertext
                        )
                    } else {
                        val plaintext = authenticatedCipher.doFinal(encryptedVerifier)
                        if (MessageDigest.isEqual(plaintext, VERIFIER)) {
                            session.encodedEnvelope
                        } else {
                            deleteKey(aliasFor(session.keyId))
                            null
                        }
                    }
            verifiedEnvelope
        } catch (_: GeneralSecurityException) {
            if (session.encryptedVerifier != null) deleteKey(aliasFor(session.keyId))
            null
        } catch (_: RuntimeException) {
            if (session.encryptedVerifier != null) deleteKey(aliasFor(session.keyId))
            null
        }
    }

    private fun prepareEnrollment(): AuthenticationSession {
        val keyId = newKeyId()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(aliasFor(keyId)))
        cipher.updateAAD(aadFor(keyId))
        return AuthenticationSession(
                cipher = cipher,
                encryptedVerifier = null,
                encodedEnvelope = null,
                keyId = keyId
        )
    }

    /** Best-effort cleanup after the lock envelope has been removed atomically from preferences. */
    fun deleteKeys() {
        runCatching { deleteObsoleteKeys(keepAlias = null) }
    }

    /** Removes crash-orphaned aliases only after [encodedEnvelope] has been persisted. */
    fun retainKeyForEnvelope(encodedEnvelope: String) {
        runCatching {
            val envelope = BiometricEnvelopeCodec.decode(encodedEnvelope) ?: return@runCatching
            deleteObsoleteKeys(keepAlias = aliasFor(envelope.keyId))
        }
    }

    /** Removes a never-persisted enrollment key after cancellation or prompt setup failure. */
    fun abandonAuthentication(session: AuthenticationSession) {
        if (session.encryptedVerifier == null) deleteKey(aliasFor(session.keyId))
    }

    /** Removes a replacement envelope when its atomic preference update did not commit. */
    internal fun discardUnpersistedEnvelope(encodedEnvelope: String) {
        runCatching {
            val envelope = BiometricEnvelopeCodec.decode(encodedEnvelope) ?: return@runCatching
            deleteKey(aliasFor(envelope.keyId))
        }
    }

    private fun deleteObsoleteKeys(keepAlias: String?) {
        val keyStore = loadKeyStore()
        val aliases = keyStore.aliases()
        while (aliases.hasMoreElements()) {
            val alias = aliases.nextElement()
            val keyId = alias.removePrefix(KEY_ALIAS_PREFIX)
            if (alias.startsWith(KEY_ALIAS_PREFIX) &&
                            BiometricEnvelopeCodec.isValidKeyId(keyId) &&
                            alias != keepAlias
            ) {
                keyStore.deleteEntry(alias)
            }
        }
    }

    private fun deleteKey(alias: String) {
        runCatching {
            val keyStore = loadKeyStore()
            if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
        }
    }

    private fun getExistingKey(alias: String): SecretKey? {
        val entry = loadKeyStore().getEntry(alias, null) ?: return null
        return (entry as? KeyStore.SecretKeyEntry)?.secretKey
                ?: throw GeneralSecurityException("Unexpected biometric lock key type")
    }

    private fun getOrCreateKey(alias: String): SecretKey {
        getExistingKey(alias)?.let { return it }

        val builder =
                KeyGenParameterSpec.Builder(
                                alias,
                                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                        )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setRandomizedEncryptionRequired(true)
                        .setUserAuthenticationRequired(true)
                        .setInvalidatedByBiometricEnrollment(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(
                    0,
                    KeyProperties.AUTH_BIOMETRIC_STRONG or
                            KeyProperties.AUTH_DEVICE_CREDENTIAL
            )
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }

        val keyGenerator =
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        keyGenerator.init(builder.build())
        return keyGenerator.generateKey()
    }

    private fun newKeyId(): String {
        val bytes = ByteArray(KEY_ID_BYTES)
        secureRandom.nextBytes(bytes)
        return keyIdEncoder.encodeToString(bytes)
    }

    private fun aliasFor(keyId: String): String {
        require(BiometricEnvelopeCodec.isValidKeyId(keyId)) { "Invalid biometric key id" }
        return KEY_ALIAS_PREFIX + keyId
    }

    private fun aadFor(keyId: String): ByteArray =
            ADDITIONAL_AUTHENTICATED_DATA + byteArrayOf(':'.code.toByte()) +
                    keyId.toByteArray(Charsets.US_ASCII)

    private fun loadKeyStore(): KeyStore =
            KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    private companion object {
        const val KEY_ALIAS_PREFIX = "yanami_next_app_lock_v1_"
        const val KEY_ID_BYTES = 16
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH = 128
        val VERIFIER = "YANAMI_NEXT_APP_LOCK_VERIFIER_V1".toByteArray(Charsets.UTF_8)
        val ADDITIONAL_AUTHENTICATED_DATA =
                "com.sekusarisu.yanami:app-lock:v1".toByteArray(Charsets.UTF_8)
        val secureRandom = SecureRandom()
        val keyIdEncoder = Base64.getUrlEncoder().withoutPadding()
    }
}

internal data class BiometricEnvelope(
        val keyId: String,
        val iv: ByteArray,
        val ciphertext: ByteArray
)

/** Strict, size-bounded codec for the non-secret verifier envelope stored in DataStore. */
internal object BiometricEnvelopeCodec {
    private const val VERSION = "v1"
    private const val MAX_ENCODED_LENGTH = 512
    private const val KEY_ID_BYTES = 16
    private const val KEY_ID_ENCODED_LENGTH = 22
    private const val GCM_IV_BYTES = 12
    private const val MIN_CIPHERTEXT_BYTES = 17
    private const val MAX_CIPHERTEXT_BYTES = 256
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(keyId: String, iv: ByteArray, ciphertext: ByteArray): String {
        require(isValidKeyId(keyId)) { "Invalid biometric key id" }
        require(iv.size == GCM_IV_BYTES) { "Unexpected GCM IV length" }
        require(ciphertext.size in MIN_CIPHERTEXT_BYTES..MAX_CIPHERTEXT_BYTES) {
            "Unexpected biometric verifier length"
        }
        return listOf(VERSION, keyId, encoder.encodeToString(iv), encoder.encodeToString(ciphertext))
                .joinToString(".")
    }

    fun decode(value: String): BiometricEnvelope? {
        if (value.length !in 1..MAX_ENCODED_LENGTH) return null
        val parts = value.split('.')
        if (parts.size != 4 || parts[0] != VERSION || !isValidKeyId(parts[1])) return null
        val iv = decodeCanonical(parts[2]) ?: return null
        val ciphertext = decodeCanonical(parts[3]) ?: return null
        if (iv.size != GCM_IV_BYTES ||
                        ciphertext.size !in MIN_CIPHERTEXT_BYTES..MAX_CIPHERTEXT_BYTES
        ) {
            return null
        }
        return BiometricEnvelope(parts[1], iv.copyOf(), ciphertext.copyOf())
    }

    fun isValidKeyId(value: String): Boolean = decodeKeyId(value) != null

    private fun decodeKeyId(value: String): ByteArray? {
        if (value.length != KEY_ID_ENCODED_LENGTH ||
                        value.any { !it.isLetterOrDigit() && it != '-' && it != '_' }
        ) {
            return null
        }
        return decodeCanonical(value)?.takeIf { it.size == KEY_ID_BYTES }
    }

    private fun decodeCanonical(value: String): ByteArray? {
        if (value.isEmpty() || value.any { !it.isLetterOrDigit() && it != '-' && it != '_' }) {
            return null
        }
        return try {
            decoder.decode(value).takeIf { encoder.encodeToString(it) == value }
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
