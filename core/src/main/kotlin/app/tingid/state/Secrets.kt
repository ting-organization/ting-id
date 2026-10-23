// Where the secrets of keys and recovery phrases are kept: the vault on the
// phone (app: vault/Vault.kt), memory in tests. Nothing else holds them: the
// store lists keys by their public key, and users by how their words are kept.
package app.tingid.state

import app.tingid.chain.Phrase
import app.tingid.chain.SigningKey
import app.tingid.chain.publicKey
import app.tingid.chain.signDigest
import app.tingid.chain.unhex
import app.tingid.chain.wipe
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * The vault, as the app's state uses it. `P` is how the vault asks the phone's
 * owner to confirm before it keeps or shows recovery words: on the phone a
 * biometric prompt bound to a keystore cipher, which only the app can make.
 * Core never looks into it; it passes the prompt from the screen that asked
 * to the vault.
 *
 * The calls that are not suspending block while the keystore works: never on
 * the main thread.
 */
interface Secrets<in P> {
    /** Keeps a key's secret under `id`, in place of any before. The caller wipes its copy. */
    fun saveKey(id: String, secret: ByteArray)

    fun hasKey(id: String): Boolean

    fun deleteKey(id: String)

    /** How words saved now would be kept: behind the owner's check, unless the phone has no screen lock. */
    fun phraseLock(): PhraseLock

    /**
     * Keeps the words of `userId`, in place of any before; a save that fails or
     * is cancelled leaves the old words as they were. Says how they are kept.
     */
    suspend fun savePhrase(userId: String, phrase: Phrase, prompt: P): PhraseLock

    /**
     * The words of `userId` once the owner confirmed; null when none are kept.
     * Throws, with a message for the person, when the owner did not confirm or
     * the phone can no longer show them. The caller closes the phrase.
     */
    suspend fun readPhrase(userId: String, prompt: P): Phrase?

    /** Removes the words of `userId`. */
    fun deletePhrase(userId: String)
}

/**
 * Secrets in memory, for tests: no owner to ask, so the prompt is ignored. It
 * also signs with the keys it holds, as the vault does through [KeyRing].
 */
open class MemorySecrets<in P>(private val lock: PhraseLock = PhraseLock.Owner) : Secrets<P>, KeyRing {
    private val keys = ConcurrentHashMap<String, ByteArray>()
    private val phrases = ConcurrentHashMap<String, ByteArray>()

    override fun saveKey(id: String, secret: ByteArray) {
        keys.put(id, secret.copyOf())?.wipe()
    }

    override fun hasKey(id: String): Boolean = keys.containsKey(id)

    override fun deleteKey(id: String) {
        keys.remove(id)?.wipe()
    }

    override fun phraseLock(): PhraseLock = lock

    override suspend fun savePhrase(userId: String, phrase: Phrase, prompt: P): PhraseLock {
        phrases.put(userId, phrase.utf8())?.wipe()
        return lock
    }

    override suspend fun readPhrase(userId: String, prompt: P): Phrase? = phrases[userId]?.let { Phrase.fromBytes(it) }

    override fun deletePhrase(userId: String) {
        phrases.remove(userId)?.wipe()
    }

    fun hasPhrase(userId: String): Boolean = phrases.containsKey(userId)

    /** Ids of the keys and of the users whose words are held. */
    fun keyIds(): Set<String> = keys.keys.toSet()
    fun phraseIds(): Set<String> = phrases.keys.toSet()

    /** Like the vault's: opens the secret only to sign, and checks it is the key's. */
    override fun signingKey(key: KeyMeta): SigningKey {
        val pub = unhex(key.pub)
        return object : SigningKey {
            override val publicKey: ByteArray = pub
            override fun sign(digest: ByteArray): ByteArray {
                val secret = keys[key.id]?.copyOf() ?: throw IllegalStateException("the secret of this key is missing from the phone")
                try {
                    if (!MessageDigest.isEqual(publicKey(secret), pub)) throw IllegalStateException("the phone holds another key under this name")
                    return signDigest(secret, digest)
                } finally {
                    secret.wipe()
                }
            }
        }
    }
}
