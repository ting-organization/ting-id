// Where Ting ID keeps its secrets: every key's secret and every recovery
// phrase, sealed by keys that live in the phone's keystore (StrongBox where
// the phone has one, else its trusted environment) and never leave it.
//
// - A key's secret is sealed with AES-256-GCM under the vault's seal key,
//   which the keystore uses only while the phone is unlocked. Signing opens
//   the secret inside [SigningKey.sign], for the one signature, and wipes it.
// - A recovery phrase is sealed under a key of its own that the keystore uses
//   only once the owner confirms with fingerprint, face or screen lock, for
//   each use. A new fingerprint or face, or a screen lock taken away, destroys
//   that key: the phone can then no longer show the words, and the written
//   copy is the only one. Without any screen lock there is nothing to ask, so
//   the words are sealed like a key's secret, and the app says so.
//
// Sealed records sit in the app's no-backup storage, each bound to its kind
// and id by the cipher's associated data, so that one record cannot pass for
// another. Nothing here goes into backups or to a new phone.
package app.tingid.vault

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.security.keystore.UserNotAuthenticatedException
import android.util.AtomicFile
import app.tingid.chain.Phrase
import app.tingid.chain.SigningKey
import app.tingid.chain.publicKey
import app.tingid.chain.signDigest
import app.tingid.chain.wipe
import app.tingid.state.PhraseLock
import app.tingid.state.Secrets
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/** Why the vault could not do what was asked. The message is for the person. */
open class VaultError(message: String) : Exception(message)

/** The owner cancelled the check, or the phone did not confirm it was them. */
class NotConfirmed(message: String) : VaultError(message)

/**
 * The keystore holds the vault's keys shut: it opens them only once the phone
 * counts as unlocked, and some phones count that only after the screen lock's
 * PIN, pattern or password, not a face, a trusted device or a fingerprint
 * asked in an app.
 */
class KeysShut : VaultError("this phone keeps your keys shut until its PIN, pattern or password is entered: confirm with it, or unlock the phone with it, and try again")

/** The keystore destroyed the phrase's key: a new fingerprint or face, or the screen lock was removed. */
class PhraseGone : VaultError("this phone can no longer show the words, as its fingerprints, face or screen lock changed; your written copy still works")

/** Asks the owner to confirm, with the cipher the keystore will only use once they do. */
fun interface OwnerPrompt {
    /** The cipher, now usable; throws [NotConfirmed] when the owner cancels or is not recognized. */
    suspend fun confirm(title: String, subtitle: String?, cipher: Cipher): Cipher
}

/** Where the keystore keeps the vault's keys: StrongBox, the trusted environment, or software. */
enum class KeyHome { StrongBox, TrustedEnvironment, Software, Unknown }

class Vault(dir: File, private val ownerLockAvailable: () -> Boolean) : Secrets<OwnerPrompt> {
    private val dir = File(dir, "vault").apply { mkdirs() }
    private val keystore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    // ---- Keys ------------------------------------------------------------------

    /** Seals a key's secret under `id`, in place of any before. The caller wipes its copy. */
    override fun saveKey(id: String, secret: ByteArray) {
        val pub = publicKey(secret)
        write(Kind.Key, id, seal(sealKey(), Kind.Key, id, secret))
        // Read back before anything relies on it: a key that cannot be opened again is no key.
        val back = open(Kind.Key, id) ?: throw VaultError("the phone did not keep the key")
        try {
            if (!MessageDigest.isEqual(publicKey(back), pub)) throw VaultError("the phone kept another key than it was given")
        } finally {
            back.wipe()
        }
    }

    override fun hasKey(id: String): Boolean = file(Kind.Key, id).exists()

    /** A key that signs with the secret sealed under `id`, which must be the secret of `publicKey`. */
    fun signingKey(id: String, publicKey: ByteArray): SigningKey = SealedKey(id, publicKey.copyOf())

    override fun deleteKey(id: String) = delete(Kind.Key, id)

    private inner class SealedKey(private val id: String, override val publicKey: ByteArray) : SigningKey {
        override fun sign(digest: ByteArray): ByteArray {
            val secret = open(Kind.Key, id) ?: throw VaultError("the secret of this key is missing from the phone")
            try {
                if (!MessageDigest.isEqual(publicKey(secret), publicKey)) throw VaultError("the phone holds another key under this name")
                return signDigest(secret, digest)
            } finally {
                secret.wipe()
            }
        }
    }

    /**
     * Whether the keystore opens the vault's keys now, asked without a prompt:
     * false while it holds them shut (KeysShut), which the screen lock's own
     * PIN, pattern or password ends.
     */
    fun keysOpen(): Boolean = try {
        val cipher = Cipher.getInstance(AES_GCM).apply { init(Cipher.DECRYPT_MODE, sealKey(), GCMParameterSpec(128, ByteArray(12))) }
        // Finished at once, so the keystore keeps no operation open: a tag that cannot match is expected.
        try {
            cipher.doFinal(ByteArray(16))
        } catch (e: javax.crypto.AEADBadTagException) {
            // The probe's own record, which no key seals.
        }
        true
    } catch (e: UserNotAuthenticatedException) {
        false
    }

    // ---- Recovery phrases --------------------------------------------------------

    /** How a phrase saved now would be kept: behind the owner's check, unless the phone has no screen lock. */
    override fun phraseLock(): PhraseLock = if (ownerLockAvailable()) PhraseLock.Owner else PhraseLock.Sealed

    /**
     * Keeps the words of `userId`, in place of any before: the new words are
     * written over the old, so a save that fails or is cancelled leaves the old
     * ones as they were. Asks the owner first when the phone has a screen lock.
     */
    override suspend fun savePhrase(userId: String, phrase: Phrase, prompt: OwnerPrompt): PhraseLock {
        val lock = phraseLock()
        val words = phrase.utf8()
        try {
            if (lock == PhraseLock.Sealed) {
                write(Kind.Phrase, userId, seal(sealKey(), Kind.Phrase, userId, words))
                deletePhraseKeys(userId, keep = null)
                return lock
            }
            // A fresh key for every save: an older key, with words sealed under it, stays until the new words are in.
            val alias = phraseAlias(userId) + System.nanoTime()
            val key = newPhraseKey(alias)
            val cipher = Cipher.getInstance(AES_GCM).apply { init(Cipher.ENCRYPT_MODE, key) }
            val confirmed = prompt.confirm("Keep your recovery phrase on this phone", "Only you can show it again", cipher)
            confirmed.updateAAD(aad(Kind.Phrase, userId))
            val sealed = Blob(alias, confirmed.iv, confirmed.doFinal(words))
            write(Kind.Phrase, userId, sealed)
            // The older keys of this user's words are of no use now.
            deletePhraseKeys(userId, keep = alias)
            return lock
        } finally {
            words.wipe()
        }
    }

    /**
     * The words of `userId` once the owner confirms; null when this phone keeps
     * none. Throws [NotConfirmed] when the check fails or is cancelled, and
     * [PhraseGone] when the keystore no longer has the key.
     */
    override suspend fun readPhrase(userId: String, prompt: OwnerPrompt): Phrase? {
        val blob = read(Kind.Phrase, userId) ?: return null
        val bytes = if (blob.alias == SEAL_ALIAS) {
            // Without a screen lock there is no one to ask: the app says so when it shows them.
            openBlob(sealKey(), Kind.Phrase, userId, blob)
        } else {
            val key = keystore.getKey(blob.alias, null) as? SecretKey ?: throw PhraseGone()
            val cipher = Cipher.getInstance(AES_GCM)
            try {
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob.iv))
            } catch (e: KeyPermanentlyInvalidatedException) {
                delete(Kind.Phrase, userId)
                deleteKeystoreEntry(blob.alias)
                throw PhraseGone()
            }
            val confirmed = prompt.confirm("Show your recovery phrase", null, cipher)
            confirmed.updateAAD(aad(Kind.Phrase, userId))
            confirmed.doFinal(blob.sealed)
        }
        try {
            return Phrase.fromBytes(bytes)
        } finally {
            bytes.wipe()
        }
    }

    fun hasPhrase(userId: String): Boolean = file(Kind.Phrase, userId).exists()

    /** Removes the words of `userId` and the key they were sealed under. */
    override fun deletePhrase(userId: String) {
        delete(Kind.Phrase, userId)
        deletePhraseKeys(userId, keep = null)
    }

    private fun deletePhraseKeys(userId: String, keep: String?) {
        for (a in keystore.aliases().toList()) if (a.startsWith(phraseAlias(userId)) && a != keep) deleteKeystoreEntry(a)
    }

    // ---- Everything ------------------------------------------------------------

    /** Ids of the keys and phrases the vault holds. */
    fun keyIds(): List<String> = ids(Kind.Key)
    fun phraseIds(): List<String> = ids(Kind.Phrase)

    /**
     * Every sealed record and every key of the vault, gone: with the keystore's
     * keys destroyed, no copy of a sealed record could be opened again.
     */
    fun deleteEverything() {
        dir.listFiles()?.forEach { it.delete() }
        for (a in keystore.aliases().toList()) if (a.startsWith(PREFIX)) deleteKeystoreEntry(a)
    }

    /** Where the keystore keeps the seal key, as the phone reports it. */
    fun keyHome(): KeyHome {
        val key = sealKey()
        return try {
            val info = SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE).getKeySpec(key, KeyInfo::class.java) as KeyInfo
            if (Build.VERSION.SDK_INT >= 31) {
                when (info.securityLevel) {
                    KeyProperties.SECURITY_LEVEL_STRONGBOX -> KeyHome.StrongBox
                    KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> KeyHome.TrustedEnvironment
                    KeyProperties.SECURITY_LEVEL_SOFTWARE -> KeyHome.Software
                    else -> KeyHome.Unknown
                }
            } else {
                // Android 11 says only whether the key is in secure hardware, not which.
                @Suppress("DEPRECATION")
                if (info.isInsideSecureHardware) KeyHome.TrustedEnvironment else KeyHome.Software
            }
        } catch (e: Exception) {
            KeyHome.Unknown
        }
    }

    // ---- Sealing -----------------------------------------------------------------

    private enum class Kind(val tag: String) { Key("key"), Phrase("phrase") }

    /** A sealed record: the keystore alias it was sealed under, the nonce and the ciphertext with its tag. */
    private class Blob(val alias: String, val iv: ByteArray, val sealed: ByteArray)

    @Synchronized
    private fun sealKey(): SecretKey {
        (keystore.getKey(SEAL_ALIAS, null) as? SecretKey)?.let { return it }
        return generate(SEAL_ALIAS) { builder ->
            // Used only while the phone is unlocked. No owner check: signing asks
            // the app's own check instead (Settings → Security), and a key bound to
            // the screen lock would die with it, taking keys that have no other copy.
            builder.setUnlockedDeviceRequired(true)
        }
    }

    private fun newPhraseKey(alias: String): SecretKey = generate(alias) { builder ->
        builder
            .setUnlockedDeviceRequired(true)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
            .setInvalidatedByBiometricEnrollment(true)
    }

    /** An AES-256-GCM key in StrongBox where the phone has one, else in its trusted environment. */
    private fun generate(alias: String, configure: (KeyGenParameterSpec.Builder) -> KeyGenParameterSpec.Builder): SecretKey {
        fun spec(strongBox: Boolean) = configure(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setIsStrongBoxBacked(strongBox),
        ).build()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        return try {
            generator.init(spec(strongBox = true))
            generator.generateKey()
        } catch (e: StrongBoxUnavailableException) {
            generator.init(spec(strongBox = false))
            generator.generateKey()
        }
    }

    private fun aad(kind: Kind, id: String): ByteArray = "ting-vault-v1\u0000${kind.tag}\u0000$id".encodeToByteArray()

    private fun seal(key: SecretKey, kind: Kind, id: String, plain: ByteArray): Blob = shutAsKeysShut {
        val cipher = Cipher.getInstance(AES_GCM).apply { init(Cipher.ENCRYPT_MODE, key) }
        cipher.updateAAD(aad(kind, id))
        Blob(SEAL_ALIAS, cipher.iv, cipher.doFinal(plain))
    }

    private fun openBlob(key: SecretKey, kind: Kind, id: String, blob: Blob): ByteArray = shutAsKeysShut {
        val cipher = Cipher.getInstance(AES_GCM).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob.iv)) }
        cipher.updateAAD(aad(kind, id))
        try {
            cipher.doFinal(blob.sealed)
        } catch (e: javax.crypto.AEADBadTagException) {
            throw VaultError("a sealed record on this phone was changed")
        }
    }

    /** The seal key's work, with the keystore's "User not authenticated" said as what it means here. */
    private inline fun <T> shutAsKeysShut(work: () -> T): T = try {
        work()
    } catch (e: UserNotAuthenticatedException) {
        throw KeysShut()
    }

    /** The secret of a sealed key record, or null when there is none; the caller wipes it. */
    private fun open(kind: Kind, id: String): ByteArray? {
        val blob = read(kind, id) ?: return null
        if (blob.alias != SEAL_ALIAS) throw VaultError("a sealed record on this phone is not what it should be")
        return openBlob(sealKey(), kind, id, blob)
    }

    // ---- Files ---------------------------------------------------------------------

    private fun checkId(id: String) {
        require(ID.matches(id)) { "not a vault id" }
    }

    private fun file(kind: Kind, id: String): File {
        checkId(id)
        return File(dir, "${kind.tag}-$id")
    }

    private fun ids(kind: Kind): List<String> =
        dir.list()?.filter { it.startsWith("${kind.tag}-") }?.map { it.removePrefix("${kind.tag}-") }?.filter { ID.matches(it) } ?: emptyList()

    /** `"TV1" ‖ u8 alias length ‖ alias ‖ u8 iv length ‖ iv ‖ sealed`, written whole or not at all. */
    private fun write(kind: Kind, id: String, blob: Blob) {
        val alias = blob.alias.encodeToByteArray()
        val bytes = MAGIC + byteArrayOf(alias.size.toByte()) + alias + byteArrayOf(blob.iv.size.toByte()) + blob.iv + blob.sealed
        val atomic = AtomicFile(file(kind, id))
        val out = atomic.startWrite()
        try {
            out.write(bytes)
            atomic.finishWrite(out)
        } catch (e: Exception) {
            atomic.failWrite(out)
            throw VaultError("the phone did not save the sealed record")
        }
    }

    private fun read(kind: Kind, id: String): Blob? {
        val bytes = try {
            AtomicFile(file(kind, id)).readFully()
        } catch (e: FileNotFoundException) {
            return null
        }
        val broken = VaultError("a sealed record on this phone is damaged")
        if (bytes.size < 5 || !bytes.copyOfRange(0, 3).contentEquals(MAGIC)) throw broken
        val aliasLength = bytes[3].toInt() and 0xff
        val ivAt = 4 + aliasLength
        if (bytes.size < ivAt + 1) throw broken
        val alias = bytes.copyOfRange(4, ivAt).decodeToString()
        val ivLength = bytes[ivAt].toInt() and 0xff
        val sealedAt = ivAt + 1 + ivLength
        if (ivLength != 12 || bytes.size < sealedAt + 16 || !alias.startsWith(PREFIX)) throw broken
        return Blob(alias, bytes.copyOfRange(ivAt + 1, sealedAt), bytes.copyOfRange(sealedAt, bytes.size))
    }

    private fun delete(kind: Kind, id: String) {
        AtomicFile(file(kind, id)).delete()
    }

    private fun deleteKeystoreEntry(alias: String) {
        if (keystore.containsAlias(alias)) keystore.deleteEntry(alias)
    }

    companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val AES_GCM = "AES/GCM/NoPadding"

        /** Every keystore alias of the vault starts with this; the app has no others. */
        const val PREFIX = "ting."
        private const val SEAL_ALIAS = "ting.seal.v1"
        /** The keys of a user's words: this, then a number, one for each save. */
        private fun phraseAlias(userId: String) = "ting.phrase.v1.$userId."

        private val MAGIC = "TV1".encodeToByteArray()
        private val ID = Regex("^[A-Za-z0-9-]{1,64}$")
    }
}
