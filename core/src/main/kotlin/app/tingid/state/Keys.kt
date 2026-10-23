// Device keys as the app manages them: made when a user needs one, named
// after what they are for, and shown only to people who switch on the
// advanced settings. Signing picks the right key by itself.
//
// Every secret starts as bytes from the system's generator (SystemEntropy),
// goes to the vault and is wiped here at once: the store holds only public
// keys, and nothing about a secret or a word goes into a log or an error.
package app.tingid.state

import app.tingid.chain.Phrase
import app.tingid.chain.Scheme
import app.tingid.chain.SystemEntropy
import app.tingid.chain.hex
import app.tingid.chain.publicKey
import app.tingid.chain.randomSecret
import app.tingid.chain.wipe
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/** The scheme of the keys Ting ID makes: the cheapest to verify, and the only one it signs with. */
val DEFAULT_SCHEME: Scheme = Scheme.Secp256k1

/** What this device is called in key names. */
const val DEVICE_LABEL = "Android phone"

/** A key's name: "Anna · Android phone". */
fun keyNameFor(userName: String): String = "$userName · $DEVICE_LABEL"

/** The key this device made for the user, if it still exists. */
fun deviceKeyOf(user: User, keys: List<KeyMeta>): KeyMeta? = keys.firstOrNull { it.id == user.keyId }

class Keys<in P>(
    private val store: Store,
    private val caches: Caches,
    private val secrets: Secrets<P>,
    val phrases: Phrases<P>,
    /** Drops the activity scans (state/activity), so that those on the way save nothing after everything is deleted. */
    private val forgetScans: () -> Unit,
    /** Drops the coin indexes (state/coinIndex), likewise. */
    private val forgetCoinIndexes: () -> Unit,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val entropy = SystemEntropy()

    /**
     * "Delete everything": every key's secret and every recovery phrase leave the
     * vault, then all saved data and the chain caches go. A key or phrase the
     * vault did not delete stays listed, so that deleting can be tried again;
     * what stayed comes back by name. Scans and reads still on the way save
     * nothing after.
     */
    suspend fun deleteEverything(): List<String> {
        val state = store.data.value
        val phrases = state.users.mapNotNull { u -> u.phrase?.let { StrayPhrase(u.id, u.name, it) } } + state.strayPhrases
        for (user in state.users) this.phrases.forgetFresh(user.id)
        val left = ArrayList<StrayPhrase>()
        for (p in phrases) {
            try {
                this.phrases.deletePhrase(p.id, p.phrase)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                left.add(p)
            }
        }
        val kept = ArrayList<KeyMeta>()
        for (key in store.data.value.keys) {
            try {
                withContext(io) { secrets.deleteKey(key.id) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                kept.add(key)
            }
        }
        forgetScans()
        forgetCoinIndexes()
        caches.forget()
        store.clearEverything(kept, left)
        return left.map { "the recovery phrase of ${it.name}" } + kept.map { it.name }
    }

    /** Forgets a user on this device, the recovery phrase it keeps included; its keys stay listed. */
    suspend fun forgetUser(user: User) {
        phrases.forgetFresh(user.id)
        phrases.deletePhrase(user.id, user.phrase)
        store.removeUser(user.id)
    }

    /**
     * A new recovery phrase for `user`: its key made and kept, the words kept on
     * this phone in place of any before, and held for the backup screen. The
     * identity lists the key only once a change or a recovery adds it. The new
     * words are written over the old ones, so a save that fails or is cancelled
     * leaves the old words as they were.
     */
    suspend fun newUserPhrase(user: User, prompt: P): KeyMeta {
        val words = Phrase.generate(entropy)
        try {
            val key = phraseKey(keyNameFor(user.name), words)
            val lock = phrases.savePhrase(user.id, words, prompt)
            store.updateUser(user.id) { it.copy(phrase = lock, phraseKeyId = key.id, phraseBackedUp = false) }
            phrases.rememberFresh(user.id, words)
            return key
        } finally {
            words.close()
        }
    }

    /**
     * The key a recovery phrase gives, kept on this device: made and stored the
     * first time, the same key when this device has it already.
     */
    suspend fun phraseKey(name: String, phrase: Phrase): KeyMeta = withContext(io) {
        // The words are stretched first: a moment's work, off the main thread.
        val secret = phrase.secret()
        try {
            val pub = publicKey(secret).hex()
            val have = store.data.value.keys.firstOrNull { it.scheme == Scheme.Secp256k1 && it.pub == pub }
            if (have != null) {
                secrets.saveKey(have.id, secret)
                return@withContext have
            }
            val key = KeyMeta(id = UUID.randomUUID().toString(), name = name, scheme = Scheme.Secp256k1, pub = pub, createdAt = now())
            secrets.saveKey(key.id, secret)
            store.addKey(key)
            key
        } finally {
            secret.wipe()
        }
    }

    /** Makes a key on this device, stores its secret and registers it. */
    suspend fun createDeviceKey(name: String): KeyMeta = withContext(io) {
        val secret = randomSecret(entropy)
        try {
            val key = KeyMeta(id = UUID.randomUUID().toString(), name = name, scheme = DEFAULT_SCHEME, pub = publicKey(secret).hex(), createdAt = now())
            secrets.saveKey(key.id, secret)
            store.addKey(key)
            key
        } finally {
            secret.wipe()
        }
    }

    /** The user's device key, made on first use. */
    suspend fun ensureDeviceKey(user: User, keys: List<KeyMeta>): KeyMeta {
        deviceKeyOf(user, keys)?.let { return it }
        val key = createDeviceKey(keyNameFor(user.name))
        store.updateUser(user.id) { it.copy(keyId = key.id) }
        return key
    }

    /** Whether the vault still holds the secret of `key`. */
    suspend fun hasSecret(key: KeyMeta): Boolean = withContext(io) { secrets.hasKey(key.id) }
}
