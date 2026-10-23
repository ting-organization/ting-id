// A user's recovery phrase on this phone (RECOVERY.md). The words sit in the
// vault apart from the key they give: signing reads only the key, so it asks
// for nothing, while showing the words always asks the phone to confirm its
// owner. Where the phone has a screen lock, the keystore itself refuses to
// hand out the words without that check; a new fingerprint or face then
// destroys them, and only the written copy is left. How the vault does this
// is the app's (vault/Vault.kt); this is what the rest of the app sees.
package app.tingid.state

import app.tingid.chain.Phrase
import app.tingid.chain.wipe
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class Phrases<in P>(
    private val secrets: Secrets<P>,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * Words just made, held in memory only, so the backup screen right after can
     * show them without asking the phone again. Gone once written down or when
     * the app closes.
     */
    private val fresh = HashMap<String, Phrase>()

    /** Holds a copy of `phrase` for the backup screen; the caller keeps its own. */
    fun rememberFresh(userId: String, phrase: Phrase) {
        val copy = copyOf(phrase)
        synchronized(fresh) { fresh.put(userId, copy) }?.close()
    }

    /** A copy of the words just made for `userId`, or null; the caller closes it. */
    fun freshWords(userId: String): Phrase? = synchronized(fresh) { fresh[userId]?.let(::copyOf) }

    fun forgetFresh(userId: String) {
        synchronized(fresh) { fresh.remove(userId) }?.close()
    }

    private fun copyOf(phrase: Phrase): Phrase {
        val bytes = phrase.utf8()
        try {
            return Phrase.fromBytes(bytes)
        } finally {
            bytes.wipe()
        }
    }

    /** How words saved now would be kept: without a screen lock, anyone holding the phone can see them. */
    fun phraseLock(): PhraseLock = secrets.phraseLock()

    /** Keeps the words of `userId` on this phone; says how they are locked. */
    suspend fun savePhrase(userId: String, phrase: Phrase, prompt: P): PhraseLock = secrets.savePhrase(userId, phrase, prompt)

    /**
     * The words of `user` once the phone confirmed its owner; null when this
     * phone keeps none. Throws, with a message for the person, when the check
     * fails or is cancelled, or when the phone can no longer read them. The
     * caller closes the phrase.
     */
    suspend fun readPhrase(user: User, prompt: P): Phrase? {
        if (user.phrase == null) return null
        return secrets.readPhrase(user.id, prompt)
    }

    /** Removes the words of user `userId` from this phone; `lock` is how they were kept, null when none were. */
    suspend fun deletePhrase(userId: String, lock: PhraseLock?) {
        if (lock == null) return
        withContext(io) { secrets.deletePhrase(userId) }
    }
}
