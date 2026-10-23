// Recovery phrases (RECOVERY.md): twelve BIP-39 English words for a new
// phrase, any valid 12 to 24 for a restore, and the one secp256k1 key the
// words give, at m/44'/1414090311'/0'/0/0 (BIP-32).
//
// The words are held as the UTF-8 bytes of their normalized text, never as a
// String, so that `close()` can overwrite them; so are the seed and every key
// on the way down the path. Showing the words makes Strings, which only the
// garbage collector removes: callers do that only while they are on screen.
package app.tingid.chain

import fr.acinq.secp256k1.Secp256k1

/** Why a text is not a recovery phrase. A word is named by its place, never repeated: the words are a secret. */
class PhraseProblem(message: String) : Exception(message)

class Phrase private constructor(private val text: ByteArray) : AutoCloseable {
    private var closed = false

    /** The number of words. */
    val size: Int get() = live().count { it == SPACE } + 1

    /** The words, in order, for showing them. */
    fun words(): List<String> = fromUtf8(live()).split(' ')

    /** The normalized words as UTF-8, for keeping them sealed; the caller wipes the copy. */
    fun utf8(): ByteArray = live().copyOf()

    /** Whether `other` is the same phrase, compared in constant time. */
    fun sameAs(other: Phrase): Boolean = java.security.MessageDigest.isEqual(live(), other.live())

    /**
     * The secret of the phrase's key (32 bytes, secp256k1); the caller wipes it.
     * Takes a moment: the words are stretched first.
     */
    fun secret(): ByteArray {
        val seed = seed("")
        try {
            return Bip32.derive(seed, KEY_PATH)
        } finally {
            seed.wipe()
        }
    }

    /** The BIP-39 seed: PBKDF2-HMAC-SHA512 of the words, 2,048 rounds, salt "mnemonic" ‖ passphrase. Ting uses no passphrase. */
    internal fun seed(passphrase: String): ByteArray = pbkdf2Sha512(live(), utf8("mnemonic$passphrase"), 2048)

    override fun close() {
        text.wipe()
        closed = true
    }

    private fun live(): ByteArray {
        check(!closed) { "the phrase was closed" }
        return text
    }

    override fun toString(): String = "Phrase(${if (closed) "closed" else "$size words"})"

    companion object {
        /** The coin type in the key path: "TING" read as a big-endian number. */
        const val COIN_TYPE = 1414090311L

        /** Where the phrase's key is: the first key of the first account. */
        const val KEY_PATH = "m/44'/1414090311'/0'/0/0"

        /** Words in a new phrase. */
        const val NEW_PHRASE_WORDS = 12

        /** Phrase lengths a restore accepts. */
        val LENGTHS = listOf(12, 15, 18, 21, 24)

        private const val SPACE = ' '.code.toByte()

        /** The 2,048 words a phrase is made of. */
        val WORDS: List<String> by lazy { Wordlist.english }

        private val index: Map<String, Int> by lazy { WORDS.withIndex().associate { (i, w) -> w to i } }

        /** A new phrase of twelve words, from 16 bytes of `entropy`. */
        fun generate(entropy: Entropy): Phrase {
            val bytes = entropy.bytes(16)
            try {
                return fromEntropy(bytes)
            } finally {
                bytes.wipe()
            }
        }

        /** BIP-39: the entropy, then the first `bits / 32` bits of its SHA-256, in 11-bit words. */
        internal fun fromEntropy(entropy: ByteArray): Phrase {
            require(entropy.size in 16..32 && entropy.size % 4 == 0) { "entropy is 16 to 32 bytes, in steps of 4" }
            val checksum = sha256(entropy)
            val bits = entropy.size * 8 + entropy.size / 4
            val count = bits / 11
            val out = java.io.ByteArrayOutputStream()
            for (w in 0 until count) {
                var value = 0
                for (b in 0 until 11) {
                    val at = w * 11 + b
                    val byte = if (at < entropy.size * 8) entropy[at / 8] else checksum[(at - entropy.size * 8) / 8]
                    value = (value shl 1) or ((byte.toInt() shr (7 - at % 8)) and 1)
                }
                if (w > 0) out.write(SPACE.toInt())
                out.write(WORDS[value].encodeToByteArray())
            }
            checksum.wipe()
            return Phrase(out.toByteArray())
        }

        /** The words as typed: lower case, one space between them. */
        fun normalize(text: CharSequence): String =
            text.toString().split(Regex("[$WHITE_SPACE]+")).filter { it.isNotEmpty() }.joinToString(" ") { it.lowercase() }

        /** The words of a typed text, in order. */
        fun wordsOf(text: CharSequence): List<String> = normalize(text).let { if (it.isEmpty()) emptyList() else it.split(' ') }

        /** Why `text` is not a recovery phrase, or null. */
        fun problem(text: CharSequence): String? = problemOf(wordsOf(text))

        private fun problemOf(words: List<String>): String? {
            if (words.size !in LENGTHS) return "a recovery phrase has 12 to 24 words; this has ${words.size}"
            val unknown = words.indexOfFirst { it !in index }
            if (unknown >= 0) return "word ${unknown + 1} is not one of the recovery phrase words"
            if (!checksumHolds(words)) return "the words do not check out: one of them may be wrong, or out of order"
            return null
        }

        private fun checksumHolds(words: List<String>): Boolean {
            val bits = words.size * 11
            val entropyBits = bits * 32 / 33
            val all = ByteArray((bits + 7) / 8)
            for ((w, word) in words.withIndex()) {
                val value = index.getValue(word)
                for (b in 0 until 11) {
                    if ((value shr (10 - b)) and 1 == 1) {
                        val at = w * 11 + b
                        all[at / 8] = (all[at / 8].toInt() or (1 shl (7 - at % 8))).toByte()
                    }
                }
            }
            val entropy = all.copyOf(entropyBits / 8)
            val checksum = sha256(entropy)
            var ok = true
            for (b in 0 until bits - entropyBits) {
                val at = entropyBits + b
                val given = (all[at / 8].toInt() shr (7 - at % 8)) and 1
                val wanted = (checksum[b / 8].toInt() shr (7 - b % 8)) and 1
                if (given != wanted) ok = false
            }
            all.wipe()
            entropy.wipe()
            checksum.wipe()
            return ok
        }

        /** The phrase in `text`, typed by a person; throws [PhraseProblem] when it is not one. */
        fun parse(text: CharSequence): Phrase {
            val words = wordsOf(text)
            problemOf(words)?.let { throw PhraseProblem(it) }
            return Phrase(words.joinToString(" ").encodeToByteArray())
        }

        /** A phrase kept as [utf8] gave it, checked again. The bytes are copied; the caller wipes its own. */
        fun fromBytes(bytes: ByteArray): Phrase {
            val words = fromUtf8(bytes).split(' ')
            problemOf(words)?.let { throw PhraseProblem(it) }
            return Phrase(bytes.copyOf())
        }

        /** Words of the list that start with `prefix`, for suggestions while typing. */
        fun suggest(prefix: String, max: Int = 4): List<String> {
            val p = prefix.trim().lowercase()
            if (p.isEmpty()) return emptyList()
            return WORDS.asSequence().filter { it.startsWith(p) }.take(max).toList()
        }
    }
}

/** The BIP-39 English list, checked against the hash every implementation knows it by. */
internal object Wordlist {
    private const val SHA256 = "2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda"

    val english: List<String> by lazy {
        // An absolute path: the release build moves classes to other packages, which a relative one would follow.
        val bytes = Wordlist::class.java.getResourceAsStream("/app/tingid/chain/bip39-english.txt")?.use { it.readBytes() }
            ?: error("the BIP-39 word list is missing from the app")
        check(sha256(bytes).hex() == SHA256) { "the BIP-39 word list is not the published one" }
        val words = fromUtf8(bytes).split('\n').filter { it.isNotEmpty() }
        check(words.size == 2048) { "the BIP-39 word list does not have 2,048 words" }
        words
    }
}

/** BIP-32 private derivation on secp256k1, through libsecp256k1. */
internal object Bip32 {
    private const val HARDENED = 0x80000000L
    private val MASTER_KEY = utf8("Bitcoin seed")

    /** The private key at `path` (`m/44'/0'/…`) below the master key of `seed`; the caller wipes it. */
    fun derive(seed: ByteArray, path: String): ByteArray {
        val (key, chain) = deriveExtended(seed, path)
        chain.wipe()
        return key
    }

    /** The key and chain code at `path`; the caller wipes both. */
    fun deriveExtended(seed: ByteArray, path: String): Pair<ByteArray, ByteArray> {
        val steps = parse(path)
        val master = HmacSha512.mac(MASTER_KEY, seed)
        var key = master.copyOfRange(0, 32)
        var chain = master.copyOfRange(32, 64)
        master.wipe()
        if (!Secp256k1.secKeyVerify(key)) {
            key.wipe()
            chain.wipe()
            throw IllegalStateException("the seed gives no master key")
        }
        for (index in steps) {
            val (k, c) = child(key, chain, index)
            key.wipe()
            chain.wipe()
            key = k
            chain = c
        }
        return key to chain
    }

    private fun child(key: ByteArray, chain: ByteArray, index: Long): Pair<ByteArray, ByteArray> {
        val ser32 = byteArrayOf((index shr 24).toByte(), (index shr 16).toByte(), (index shr 8).toByte(), index.toByte())
        val data = if (index >= HARDENED) {
            byteArrayOf(0) + key
        } else {
            Secp256k1.pubKeyCompress(Secp256k1.pubkeyCreate(key))
        }
        val i = HmacSha512.mac(chain, data, ser32)
        data.wipe()
        val il = i.copyOfRange(0, 32)
        val ir = i.copyOfRange(32, 64)
        i.wipe()
        try {
            // parse256(IL) must be below n, and the child key not zero: libsecp256k1 refuses both.
            if (!Secp256k1.secKeyVerify(il)) throw IllegalStateException("BIP-32 gives no key at this index")
            return Secp256k1.privKeyTweakAdd(key, il) to ir
        } catch (e: Exception) {
            ir.wipe()
            throw IllegalStateException("BIP-32 gives no key at this index")
        } finally {
            il.wipe()
        }
    }

    fun parse(path: String): List<Long> {
        val parts = path.split('/')
        require(parts.firstOrNull() == "m") { "a key path starts with m" }
        return parts.drop(1).map { part ->
            val hardened = part.endsWith("'")
            val n = part.removeSuffix("'").toLongOrNull()
            require(n != null && n in 0 until HARDENED && part.removeSuffix("'").all { it.isDigit() }) { "not a key path step: $part" }
            if (hardened) n + HARDENED else n
        }
    }
}
