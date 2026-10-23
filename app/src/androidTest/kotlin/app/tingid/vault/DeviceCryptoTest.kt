// The phrase code on the phone's own crypto providers (Conscrypt, BoringSSL),
// which differ from the JVM's: the BIP-39 vectors, the random generator's
// health checks, and libsecp256k1's Android build.
package app.tingid.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tingid.chain.Phrase
import app.tingid.chain.SystemEntropy
import app.tingid.chain.hex
import app.tingid.chain.publicKey
import app.tingid.chain.unhex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceCryptoTest {
    @Test
    fun phrasesMatchTheBip39VectorsOnThePhone() {
        val text = DeviceCryptoTest::class.java.getResource("/bip39-vectors.json")!!.readText()
        val vectors = Json.parseToJsonElement(text).jsonArray
        for (v in vectors) {
            val o = v.jsonObject
            val mnemonic = o.getValue("mnemonic").jsonPrimitive.content
            Phrase.parse(mnemonic).use { assertEquals(mnemonic, it.words().joinToString(" ")) }
            assertEquals(null, Phrase.problem(mnemonic))
            assertEquals(o.getValue("entropy").jsonPrimitive.content.length / 2 * 3 / 4, mnemonic.split(' ').size)
        }
    }

    @Test
    fun theTingPathGivesTheRecoveryMdKey() {
        val abandon = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        val secret = Phrase.parse(abandon).use { it.secret() }
        assertEquals(
            "047388c4cccd1893555407977f70e303c242c3d3d6f992ea01583af171a2c02c67136ba4103689143e9d458d7891d2c0c1ea98db313a9aa72ea76bcbbb6ebde46b",
            publicKey(secret).hex(),
        )
    }

    @Test
    fun newPhrasesAreTwelveCheckedWordsAndNeverRepeat() {
        val entropy = SystemEntropy()
        val seen = HashSet<String>()
        repeat(200) {
            Phrase.generate(entropy).use { p ->
                assertEquals(12, p.size)
                assertTrue(seen.add(p.words().joinToString(" ")))
            }
        }
        assertEquals(32, unhex("00".repeat(32)).size)
    }
}
