// The vault on a real keystore: a key comes back as it went in and signs as
// the reference does; a sealed record that was changed, or moved to another
// key's place, is refused; deleting everything leaves nothing to open.
package app.tingid.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.tingid.chain.MemoryKey
import app.tingid.chain.Phrase
import app.tingid.chain.SystemEntropy
import app.tingid.chain.filled
import app.tingid.chain.publicKey
import app.tingid.chain.randomSecret
import app.tingid.chain.sha256
import app.tingid.chain.utf8
import app.tingid.state.PhraseLock
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore

@RunWith(AndroidJUnit4::class)
class VaultTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File
    private lateinit var vault: Vault

    @Before
    fun setUp() {
        dir = File(context.cacheDir, "vault-test-${System.nanoTime()}")
        // The test phone has no screen lock: phrases are sealed like keys.
        vault = Vault(dir) { false }
    }

    @After
    fun tearDown() {
        vault.deleteEverything()
        dir.deleteRecursively()
    }

    private val noPrompt = OwnerPrompt { _, _, _ -> throw NotConfirmed("not asked in this test") }

    @Test
    fun keysComeBackAndSignAsTheReference() {
        val secret = randomSecret(SystemEntropy())
        val pub = publicKey(secret)
        vault.saveKey("k1", secret.copyOf())
        assertTrue(vault.hasKey("k1"))
        val digest = sha256(utf8("a message"))
        // RFC 6979: the same key signs the same digest the same way, in the vault or not.
        assertArrayEquals(MemoryKey(secret).sign(digest), vault.signingKey("k1", pub).sign(digest))
        // Under another public key, the vault refuses to sign.
        val other = publicKey(randomSecret(SystemEntropy()))
        assertThrows(VaultError::class.java) { vault.signingKey("k1", other).sign(digest) }
        vault.deleteKey("k1")
        assertFalse(vault.hasKey("k1"))
        assertThrows(VaultError::class.java) { vault.signingKey("k1", pub).sign(digest) }
    }

    @Test
    fun theSecretIsNotOnDiskInTheClear() {
        val secret = filled(0x5a)
        vault.saveKey("k2", secret.copyOf())
        val bytes = File(File(dir, "vault"), "key-k2").readBytes()
        assertFalse(bytes.toList().windowed(secret.size).any { it.toByteArray().contentEquals(secret) })
    }

    @Test
    fun aChangedOrMovedRecordIsRefused() {
        val a = randomSecret(SystemEntropy())
        val b = randomSecret(SystemEntropy())
        vault.saveKey("ka", a.copyOf())
        vault.saveKey("kb", b.copyOf())
        val records = File(dir, "vault")
        // One key's record in another's place: bound to its own id, it does not open there.
        File(records, "key-ka").copyTo(File(records, "key-kb"), overwrite = true)
        assertThrows(VaultError::class.java) { vault.signingKey("kb", publicKey(b)).sign(filled(1)) }
        // A flipped bit in the ciphertext fails its tag.
        vault.saveKey("kb", b.copyOf())
        val f = File(records, "key-kb")
        val bytes = f.readBytes()
        bytes[bytes.size - 20] = (bytes[bytes.size - 20].toInt() xor 1).toByte()
        f.writeBytes(bytes)
        assertThrows(VaultError::class.java) { vault.signingKey("kb", publicKey(b)).sign(filled(1)) }
    }

    @Test
    fun phrasesWithoutAScreenLockAreSealedAndComeBack() = runBlocking {
        val phrase = Phrase.generate(SystemEntropy())
        assertEquals(PhraseLock.Sealed, vault.savePhrase("u1", phrase, noPrompt))
        val back = vault.readPhrase("u1", noPrompt)!!
        assertTrue(back.sameAs(phrase))
        assertArrayEquals(phrase.secret(), back.secret())
        vault.deletePhrase("u1")
        assertEquals(null, vault.readPhrase("u1", noPrompt))
    }

    @Test
    fun ownerLockedPhrasesNeedTheOwner() = runBlocking {
        // A phone with a screen lock: saving asks the owner, and a refusal saves nothing.
        val locked = Vault(dir) { true }
        val refuse = OwnerPrompt { _, _, _ -> throw NotConfirmed("cancelled") }
        val e = runCatching { locked.savePhrase("u2", Phrase.generate(SystemEntropy()), refuse) }.exceptionOrNull()
        // Without a screen lock on the test phone the keystore cannot make such a key at all.
        assertTrue(e is NotConfirmed || e is java.security.GeneralSecurityException || e is IllegalStateException)
        assertFalse(locked.hasPhrase("u2"))
    }

    @Test
    fun deletingEverythingLeavesNoKeyToOpen() {
        vault.saveKey("k3", randomSecret(SystemEntropy()))
        vault.deleteEverything()
        assertFalse(vault.hasKey("k3"))
        val keystore = KeyStore.getInstance(Vault.ANDROID_KEYSTORE).apply { load(null) }
        assertFalse(keystore.aliases().toList().any { it.startsWith(Vault.PREFIX) })
    }

    @Test
    fun saysWhereItsKeysLive() {
        vault.saveKey("k4", randomSecret(SystemEntropy()))
        assertNotEquals(KeyHome.Unknown, vault.keyHome())
    }
}
