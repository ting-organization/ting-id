// Recovery phrases: BIP-39 words, checked, and the key Ting's path gives,
// against the published BIP-39 (Trezor) and BIP-32 vectors.
package app.tingid.chain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

private const val ABANDON = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

private fun resource(name: String): JsonArray =
    Json.parseToJsonElement(PhraseTest::class.java.getResource("/$name")!!.readText()).jsonArray

/** An extended private key's chain code and key, from its Base58Check text. */
internal fun decodeXprv(text: String): Pair<ByteArray, ByteArray> {
    val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    var n = BigInteger.ZERO
    for (c in text) n = n.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(alphabet.indexOf(c).toLong()))
    val raw = n.toByteArray().let { if (it.size > 82) it.copyOfRange(it.size - 82, it.size) else ByteArray(82 - it.size) + it }
    val body = raw.copyOfRange(0, 78)
    assertArrayEquals("checksum", sha256(sha256(body)).copyOfRange(0, 4), raw.copyOfRange(78, 82))
    assertEquals(0, body[45].toInt())
    return body.copyOfRange(13, 45) to body.copyOfRange(46, 78)
}

class PhraseTest {
    @Test
    fun `the word list is the published one`() {
        assertEquals(2048, Phrase.WORDS.size)
        assertEquals("abandon", Phrase.WORDS.first())
        assertEquals("zoo", Phrase.WORDS.last())
    }

    @Test
    fun `entropy, words and seeds match the BIP-39 vectors`() {
        val vectors = resource("bip39-vectors.json")
        assertEquals(24, vectors.size)
        for (v in vectors) {
            val o = v.jsonObject
            val entropy = unhex(o.getValue("entropy").jsonPrimitive.content)
            val mnemonic = o.getValue("mnemonic").jsonPrimitive.content
            Phrase.fromEntropy(entropy).use { phrase ->
                assertEquals(mnemonic, phrase.words().joinToString(" "))
                assertEquals(o.getValue("seed").jsonPrimitive.content, phrase.seed("TREZOR").hex())
                // The master key the vector's xprv holds.
                val (key, chain) = Bip32.deriveExtended(phrase.seed("TREZOR"), "m")
                val (wantChain, wantKey) = decodeXprv(o.getValue("xprv").jsonPrimitive.content)
                assertArrayEquals(wantKey, key)
                assertArrayEquals(wantChain, chain)
            }
            assertNull(mnemonic, Phrase.problem(mnemonic))
            Phrase.parse(mnemonic).use { assertEquals(mnemonic, it.words().joinToString(" ")) }
        }
    }

    @Test
    fun `keys match the BIP-32 vectors, leading zeros included`() {
        for (v in resource("bip32-vectors.json")) {
            val seed = unhex(v.jsonObject.getValue("seed").jsonPrimitive.content)
            for (c in v.jsonObject.getValue("chains").jsonArray) {
                val path = c.jsonObject.getValue("path").jsonPrimitive.content
                val (key, chain) = Bip32.deriveExtended(seed, path)
                val (wantChain, wantKey) = decodeXprv(c.jsonObject.getValue("xprv").jsonPrimitive.content)
                assertArrayEquals(path, wantKey, key)
                assertArrayEquals(path, wantChain, chain)
            }
        }
    }

    @Test
    fun `give the key the published BIP-44 vector and RECOVERY_md give`() {
        // BIP-44's own vector for this phrase: m/44'/0'/0'/0/0.
        Phrase.parse(ABANDON).use { phrase ->
            val bitcoin = Bip32.derive(phrase.seed(""), "m/44'/0'/0'/0/0")
            assertEquals("03aaeb52dd7494c361049de67cc680e83ebcbbbdbeb13637d92cd845f70308af5e", fr.acinq.secp256k1.Secp256k1.pubKeyCompress(publicKey(bitcoin)).hex())
        }
        // Ting's path, pinned so it never changes under a phrase someone wrote down.
        assertEquals("m/44'/1414090311'/0'/0/0", Phrase.KEY_PATH)
        assertEquals(BigInteger(1, utf8("TING")).toLong(), Phrase.COIN_TYPE)
        val secret = Phrase.parse(ABANDON).use { it.secret() }
        assertEquals(
            "047388c4cccd1893555407977f70e303c242c3d3d6f992ea01583af171a2c02c67136ba4103689143e9d458d7891d2c0c1ea98db313a9aa72ea76bcbbb6ebde46b",
            publicKey(secret).hex(),
        )
        // However it was typed.
        val typed = "  ${ABANDON.uppercase().replace(" ", "  \n")} "
        assertArrayEquals(secret, Phrase.parse(typed).use { it.secret() })
    }

    @Test
    fun `are twelve words from sixteen random bytes`() {
        val phrase = Phrase.generate { n -> ByteArray(n) { 7 } }
        assertEquals(12, phrase.size)
        assertNull(Phrase.problem(phrase.words().joinToString(" ")))
        Phrase.fromBytes(phrase.utf8()).use { assertTrue(it.sameAs(phrase)) }
    }

    @Test
    fun `are refused with a reason that names no word`() {
        assertEquals("a recovery phrase has 12 to 24 words; this has 2", Phrase.problem("abandon abandon"))
        assertEquals("word 12 is not one of the recovery phrase words", Phrase.problem(ABANDON.replace("about", "aboutx")))
        assertTrue(Phrase.problem(ABANDON.replace("about", "abandon"))!!.contains("do not check out"))
        assertNull(Phrase.problem(ABANDON))
        val e = assertThrows(PhraseProblem::class.java) { Phrase.parse(ABANDON.replace("about", "zoo")) }
        assertFalse(e.message!!.contains("zoo"))
        assertThrows(PhraseProblem::class.java) { Phrase.fromBytes(utf8("abandon about")) }
    }

    @Test
    fun `are gone once closed`() {
        val phrase = Phrase.parse(ABANDON)
        phrase.close()
        assertThrows(IllegalStateException::class.java) { phrase.words() }
        assertEquals("Phrase(closed)", phrase.toString())
        Phrase.parse(ABANDON).use { assertFalse(it.toString().contains("abandon")) }
    }

    @Test
    fun `suggest words while typing`() {
        assertEquals(listOf("abandon"), Phrase.suggest("aba"))
        assertEquals(listOf("zone", "zoo"), Phrase.suggest("zo"))
        assertEquals(emptyList<String>(), Phrase.suggest(" "))
        assertEquals("zoo zoo zoo", Phrase.normalize(" Zoo  ZOO\nzoo "))
    }

    @Test
    fun `key paths are read strictly`() {
        assertEquals(listOf(0x8000002cL, 1L), Bip32.parse("m/44'/1"))
        for (bad in listOf("", "44'/0", "m/-1", "m/2147483648", "m/1''", "m/+1", "m/a")) {
            assertThrows(bad, IllegalArgumentException::class.java) { Bip32.parse(bad) }
        }
    }
}

class HmacTest {
    private fun check(key: ByteArray, message: String, want: String) =
        assertEquals(want, HmacSha512.mac(key, utf8(message)).hex())

    @Test
    fun `HMAC-SHA512 matches RFC 4231`() {
        check(ByteArray(20) { 0x0b }, "Hi There", "87aa7cdea5ef619d4ff0b4241a1d6cb02379f4e2ce4ec2787ad0b30545e17cdedaa833b7d6b8a702038b274eaea3f4e4be9d914eeb61f1702e696c203a126854")
        check(utf8("Jefe"), "what do ya want for nothing?", "164b7a7bfcf819e2e395fbe73b56e0a387bd64222e831fd610270cd7ea2505549758bf75c05a994a6d034f65f8f0e6fdcaeab1a34d4a6b4b636e070a38bce737")
        check(ByteArray(131) { 0xaa.toByte() }, "Test Using Larger Than Block-Size Key - Hash Key First", "80b24263c7c1a3ebb71493c1dd7be8b49b46d1f41b4aeec1121b013783f8f3526b56d037e05f2598bd0fd2215d6a1e5295e64f73f63f0aec8b915a985d786598")
        check(ByteArray(128) { 0xaa.toByte() }, "exactly one block of key", "d3c1106eb5acb4cfe529d4b4946b42b484f43b8bd7f240a87e22435dfcc2155a9b75342b8282fb58320a72b740f573c376f4f91d0e2a2d7e09a0cc23e41784a7")
    }

    @Test
    fun `one key serves many messages`() {
        val h = HmacSha512(utf8("Jefe"))
        val first = h.mac(utf8("what do ya want "), utf8("for nothing?"))
        assertArrayEquals(first, h.mac(utf8("what do ya want for nothing?")))
    }

    @Test
    fun `PBKDF2-HMAC-SHA512 matches a reference`() {
        assertEquals("867f70cf1ade02cff3752599a3a53dc4af34c7a669815ae5d513554e1c8cf252c02d470a285a0501bad999bfe943c08f050235d7d68b1da55e63f73b60a57fce", pbkdf2Sha512(utf8("password"), utf8("salt"), 1).hex())
        assertEquals("e1d9c16aa681708a45f5c7c4e215ceb66e011a2e9f0040713f18aefdb866d53cf76cab2868a39b9f7840edce4fef5a82be67335c77a6068e04112754f27ccf4e", pbkdf2Sha512(utf8("password"), utf8("salt"), 2).hex())
    }
}

class EntropyTest {
    @Test
    fun `gives different bytes each time`() {
        val e = SystemEntropy()
        val seen = HashSet<String>()
        repeat(1000) { assertTrue(seen.add(e.bytes(16).hex())) }
    }

    @Test
    fun `refuses a generator that is stuck`() {
        val stuck = object : java.security.SecureRandom() {
            override fun nextBytes(bytes: ByteArray) = bytes.fill(0)
        }
        assertThrows(EntropyFailure::class.java) { SystemEntropy(stuck).bytes(16) }
    }

    @Test
    fun `refuses a generator that repeats itself`() {
        var calls = 0
        val repeating = object : java.security.SecureRandom() {
            override fun nextBytes(bytes: ByteArray) {
                calls++
                for (i in bytes.indices) bytes[i] = i.toByte()
            }
        }
        val e = SystemEntropy(repeating)
        e.bytes(16)
        assertThrows(EntropyFailure::class.java) { e.bytes(16) }
        assertEquals(2, calls)
    }
}

class KeysTest {
    private val secret = unhex("c9afa9d845ba75166b5c215767b1d6934e50c3db36e89b127b8a622b120f6721")

    @Test
    fun `sign low-S and deterministically, and verify`() {
        val digest = sha256(utf8("sample"))
        val signature = signDigest(secret, digest)
        assertEquals(64, signature.size)
        assertArrayEquals(signature, signDigest(secret, digest))
        val s = BigInteger(1, signature.copyOfRange(32, 64))
        val n = BigInteger("fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141", 16)
        assertTrue(s <= n.shiftRight(1))
        assertTrue(verifyDigest(publicKey(secret), digest, signature))
        assertFalse(verifyDigest(publicKey(secret), sha256(utf8("other")), signature))
        // The high-S twin of a valid signature is refused, as the policy refuses it.
        val highS = n.subtract(s).toByteArray().let { b -> ByteArray(maxOf(0, 32 - b.size)) + b.takeLast(32) }
        val high = signature.copyOfRange(0, 32) + highS
        assertFalse(verifyDigest(publicKey(secret), digest, high))
    }

    @Test
    fun `refuse secrets outside the curve order`() {
        assertFalse(isValidSecret(ByteArray(32)))
        assertFalse(isValidSecret(unhex("fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141")))
        assertFalse(isValidSecret(ByteArray(31) { 1 }))
        assertThrows(IllegalArgumentException::class.java) { publicKey(ByteArray(32)) }
        var draws = 0
        val secret = randomSecret { n -> draws++; if (draws == 1) ByteArray(n) else ByteArray(n) { 1 } }
        assertEquals(2, draws)
        assertTrue(isValidSecret(secret))
    }

    @Test
    fun `check public keys on their curves`() {
        val pub = publicKey(secret)
        assertTrue(isPublicKey(Scheme.Secp256k1, pub))
        assertFalse(isPublicKey(Scheme.Secp256k1, pub.copyOf().also { it[64] = (it[64] + 1).toByte() }))
        assertFalse(isPublicKey(Scheme.Secp256k1, fr.acinq.secp256k1.Secp256k1.pubKeyCompress(pub)))
        // P-256's generator.
        val g = unhex("046b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c2964fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5")
        assertTrue(isPublicKey(Scheme.P256, g))
        assertFalse(isPublicKey(Scheme.P256, g.copyOf().also { it[64] = (it[64] + 1).toByte() }))
        assertFalse(isPublicKey(Scheme.P256, pub))
    }
}
