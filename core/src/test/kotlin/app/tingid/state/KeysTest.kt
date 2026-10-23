// Which users a device key serves: a key is only offered for deletion when no
// user, and no identity on chain, may still sign with it. And the keys and
// recovery phrases themselves: made from the system's generator, kept in the
// vault, wiped here, never in the store.
package app.tingid.state

import app.tingid.chain.Contracts
import app.tingid.chain.Id
import app.tingid.chain.Phrase
import app.tingid.chain.Scheme
import app.tingid.chain.filled
import app.tingid.chain.hex
import app.tingid.chain.publicKey
import app.tingid.chain.sha256
import app.tingid.chain.unhex
import app.tingid.chain.verifyDigest
import app.tingid.chain.wipe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class KeyUsersTest {
    private val c = Contracts.canonical(filled(7))
    private val pub = filled(4, 65)
    private val key = KeyMeta("k", "Backup", Scheme.Secp256k1, pub.hex(), 0)
    private val listed = IdentityKey(3, Id.Key(c.policyFor(Scheme.Secp256k1), pub))
    private val core = Id.Core(0, 0, 0, 0, 4, 1, 0, 1, 0, 0, 0, 0)
    private fun identity(keys: List<IdentityKey>, pending: List<PendingChange> = emptyList()) =
        IdentityInfo(filled(0x11), filled(0x22), core, keys, emptyList(), pending)
    private val alice = User("a", "Alice", "", UserKind.Identity, identityId = "11".repeat(32), registry = "22".repeat(32), keyId = "other")

    @Test
    fun `count the user a key was made for`() {
        val simple = User("s", "Savings", "", UserKind.Key, keyId = "k")
        assertEquals(KeyUsers(listOf("Savings"), emptyList()), keyUsers(key, listOf(simple), emptyMap(), c))
        val phrase = alice.copy(phraseKeyId = "k")
        assertEquals(KeyUsers(listOf("Alice"), emptyList()), keyUsers(key, listOf(phrase), emptyMap(), null))
    }

    @Test
    fun `count an identity that lists the key on chain, though the key was not made for it`() {
        assertEquals(KeyUsers(listOf("Alice"), emptyList()), keyUsers(key, listOf(alice), mapOf("a" to identity(listOf(listed))), c))
        assertEquals(KeyUsers(emptyList(), emptyList()), keyUsers(key, listOf(alice), mapOf("a" to identity(emptyList())), c))
    }

    @Test
    fun `count an identity whose pending change adds the key`() {
        val adding = PendingChange(4, Id.ChangeRecord(0, 0, 0, 0, listOf(Id.Operation.AddKey(listed.key))))
        assertEquals(KeyUsers(listOf("Alice"), emptyList()), keyUsers(key, listOf(alice), mapOf("a" to identity(emptyList(), listOf(adding))), c))
    }

    @Test
    fun `count an identity not on chain yet by its creation`() {
        val creation = Id.encodeCreation(Id.Settings(listOf(listed.key), emptyList(), 0, 0, 0, listOf(0L), ByteArray(32)))
        assertEquals(KeyUsers(listOf("Alice"), emptyList()), keyUsers(key, listOf(alice.copy(creation = creation.hex())), mapOf("a" to null), c))
        assertEquals(KeyUsers(emptyList(), emptyList()), keyUsers(key, listOf(alice), mapOf("a" to null), c))
    }

    @Test
    fun `leave a key undecided while an identity is not read, or no chain answered`() {
        assertEquals(KeyUsers(emptyList(), listOf("Alice")), keyUsers(key, listOf(alice), emptyMap(), c))
        assertEquals(KeyUsers(emptyList(), listOf("Alice")), keyUsers(key, listOf(alice), mapOf("a" to identity(listOf(listed))), null))
    }

    @Test
    fun `know which key of an identity a device key is`() {
        val other = key.copy(id = "o", pub = filled(5, 65).hex())
        val known = identity(listOf(listed))
        assertEquals(3L, linkedKeyId(c, known, key))
        assertNull(linkedKeyId(c, known, other))
        // The same public key under another scheme's policy is another key.
        assertNull(linkedKeyId(c, known, key.copy(scheme = Scheme.P256)))
        assertEquals(listOf("k" to 3L), linkedDeviceKeys(c, known, listOf(other, key)).map { it.key.id to it.keyId })
    }

    @Test
    fun `read the identity of an identity user only`() {
        val me = userIdentity(alice)!!
        assertEquals("11".repeat(32), me.id.hex())
        assertEquals("22".repeat(32), me.registry.hex())
        assertNull(userIdentity(User("s", "Savings", "", UserKind.Key, keyId = "k")))
        assertNull(userIdentity(alice.copy(registry = null)))
    }
}

/** Secrets in memory that note what they were handed, and refuse to delete what the test says. */
private class Noting : MemorySecrets<Unit>() {
    val handed: MutableList<ByteArray> = Collections.synchronizedList(ArrayList())
    val refuse: MutableSet<String> = Collections.synchronizedSet(HashSet())
    var cancelSave = false

    override fun saveKey(id: String, secret: ByteArray) {
        handed.add(secret)
        super.saveKey(id, secret)
    }

    override fun deleteKey(id: String) {
        if (id in refuse) throw IllegalStateException("the keystore refused")
        super.deleteKey(id)
    }

    override suspend fun savePhrase(userId: String, phrase: Phrase, prompt: Unit): PhraseLock {
        if (cancelSave) throw IllegalStateException("cancelled")
        return super.savePhrase(userId, phrase, prompt)
    }

    override fun deletePhrase(userId: String) {
        if (userId in refuse) throw IllegalStateException("the keystore refused")
        super.deletePhrase(userId)
    }
}

class KeysTest {
    private val secrets = Noting()
    private val storage = MemoryStorage()
    private val store = Store(storage, listOf("https://node.test"), now = { NOW }, scope = testScope).also { it.load() }
    private val caches = Caches(storage, testScope)
    private val forgotten = Collections.synchronizedList(ArrayList<String>())
    private val keys = Keys(store, caches, secrets, Phrases(secrets), { forgotten.add("scans") }, { forgotten.add("coin indexes") }, now = { NOW })
    private val anna = User("u1", "Anna", "#4F46E5", UserKind.Identity, identityId = "11".repeat(32), registry = "22".repeat(32))

    /** The secret held for `key`, checked by a signature under its public key. */
    private fun signs(key: KeyMeta): Boolean {
        val digest = sha256(key.id.encodeToByteArray())
        return verifyDigest(unhex(key.pub), digest, secrets.signingKey(key).sign(digest))
    }

    private fun handedWiped() = secrets.handed.isNotEmpty() && secrets.handed.all { s -> s.all { it == 0.toByte() } }

    private suspend fun saved(): String {
        store.flush()
        return storage.read(Store.FILE)!!
    }

    @Test
    fun `a device key is made fresh, kept in the vault and wiped here`() = runBlocking {
        val a = keys.createDeviceKey("Anna · Android phone")
        val b = keys.createDeviceKey("Backup")
        assertNotEquals(a.pub, b.pub)
        assertEquals(listOf(a, b), store.data.value.keys)
        assertEquals(Scheme.Secp256k1, a.scheme)
        assertEquals(NOW, a.createdAt)
        assertEquals(a.id, java.util.UUID.fromString(a.id).toString())
        assertTrue(signs(a) && signs(b))
        assertTrue(keys.hasSecret(a))
        assertTrue(handedWiped())
    }

    @Test
    fun `a phrase gives the same key every time, listed once`() = runBlocking {
        val phrase = Phrase.parse(WORDS)
        val first = keys.phraseKey("Anna · Android phone", phrase)
        val again = keys.phraseKey("Another name", phrase)
        assertEquals(first, again)
        assertEquals(listOf(first), store.data.value.keys)
        val secret = phrase.secret()
        assertEquals(publicKey(secret).hex(), first.pub)
        secret.wipe()
        assertTrue(signs(first))
        assertTrue(handedWiped())
        // Words are never kept with the state.
        assertFalse(saved().contains("abandon"))
    }

    @Test
    fun `a new phrase is kept, held for the backup screen, and its key noted on the user`() = runBlocking {
        store.addUser(anna)
        val key = keys.newUserPhrase(anna, Unit)
        val user = store.data.value.users.single()
        assertEquals(PhraseLock.Owner, user.phrase)
        assertEquals(key.id, user.phraseKeyId)
        assertFalse(user.phraseBackedUp)
        assertEquals(keyNameFor("Anna"), key.name)
        assertEquals("Anna · Android phone", key.name)
        val fresh = keys.phrases.freshWords(user.id)!!
        val stored = keys.phrases.readPhrase(user, Unit)!!
        assertTrue(fresh.sameAs(stored))
        assertEquals(12, fresh.size)
        // The phrase's own key, which signs.
        val secret = fresh.secret()
        assertEquals(publicKey(secret).hex(), key.pub)
        secret.wipe()
        assertTrue(signs(key))
        // The backup screen gets a copy: closing it leaves the words held.
        fresh.close()
        assertEquals(12, keys.phrases.freshWords(user.id)!!.size)
        keys.phrases.forgetFresh(user.id)
        assertNull(keys.phrases.freshWords(user.id))
        // Words are never kept with the state.
        val words = stored.words()
        val text = saved()
        assertFalse(text.contains(words.joinToString(" ")))
        assertFalse(words.zipWithNext().any { (a, b) -> text.contains("$a $b") })
        stored.close()
    }

    @Test
    fun `a phrase save that is cancelled leaves the user as it was`() = runBlocking {
        store.addUser(anna.copy(phrase = PhraseLock.Owner, phraseKeyId = "old", phraseBackedUp = true))
        secrets.cancelSave = true
        assertEquals("cancelled", runCatching { keys.newUserPhrase(anna, Unit) }.exceptionOrNull()?.message)
        val user = store.data.value.users.single()
        assertEquals("old", user.phraseKeyId)
        assertTrue(user.phraseBackedUp)
        assertNull(keys.phrases.freshWords(user.id))
    }

    @Test
    fun `the words are only read for a user that keeps some`() = runBlocking {
        secrets.savePhrase(anna.id, Phrase.parse(WORDS), Unit)
        assertNull(keys.phrases.readPhrase(anna, Unit))
        assertEquals(12, keys.phrases.readPhrase(anna.copy(phrase = PhraseLock.Sealed), Unit)!!.size)
    }

    @Test
    fun `a device key is made on first use and noted on the user`() = runBlocking {
        store.addUser(anna)
        val key = keys.ensureDeviceKey(anna, emptyList())
        assertEquals(key.id, store.data.value.users.single().keyId)
        assertEquals("Anna · Android phone", key.name)
        val user = store.data.value.users.single()
        assertSame(key, keys.ensureDeviceKey(user, store.data.value.keys))
        assertEquals(1, store.data.value.keys.size)
        assertSame(key, deviceKeyOf(user, store.data.value.keys))
    }

    @Test
    fun `forgetting a user deletes its words, and its keys stay listed`() = runBlocking {
        store.addUser(anna)
        val key = keys.newUserPhrase(anna, Unit)
        val user = store.data.value.users.single()
        keys.forgetUser(user)
        assertTrue(store.data.value.users.isEmpty())
        assertFalse(secrets.hasPhrase(user.id))
        assertNull(keys.phrases.freshWords(user.id))
        assertEquals(listOf(key), store.data.value.keys)
        assertTrue(secrets.hasKey(key.id))
    }

    @Test
    fun `deleting everything empties the vault and the state, and keeps listed what stayed`() = runBlocking {
        store.addUser(anna)
        val bob = User("u2", "Bob", "#0EA5A4", UserKind.Key)
        store.addUser(bob)
        val annaKey = keys.newUserPhrase(anna, Unit)
        keys.phrases.savePhrase(bob.id, Phrase.parse(WORDS), Unit)
        store.updateUser(bob.id) { it.copy(phrase = PhraseLock.Sealed) }
        store.setStrayPhrases(listOf(StrayPhrase("u0", "Carl", PhraseLock.Owner)))
        val backup = keys.createDeviceKey("Backup")
        caches.select("aa".repeat(32)).put("status", "five", String.serializer(), "")
        secrets.refuse += listOf(backup.id, bob.id)

        val left = keys.deleteEverything()

        assertEquals(listOf("the recovery phrase of Bob", "Backup"), left)
        assertEquals(setOf(backup.id), secrets.keyIds())
        assertEquals(setOf(bob.id), secrets.phraseIds())
        assertFalse(secrets.hasKey(annaKey.id))
        val d = store.data.value
        assertEquals(listOf(backup), d.keys)
        assertEquals(listOf(StrayPhrase("u2", "Bob", PhraseLock.Sealed)), d.strayPhrases)
        assertTrue(d.users.isEmpty())
        assertNull(keys.phrases.freshWords(anna.id))
        assertEquals(listOf("scans", "coin indexes"), forgotten)
        assertFalse(caches.current.value!!.has("status"))
        // Deleting again goes for what stayed.
        secrets.refuse.clear()
        assertEquals(emptyList<String>(), keys.deleteEverything())
        assertTrue(secrets.keyIds().isEmpty() && secrets.phraseIds().isEmpty())
        assertTrue(store.data.value.keys.isEmpty() && store.data.value.strayPhrases.isEmpty())
    }

    @Test
    fun `signing refuses a secret that is not the key's`() {
        val a = runBlocking { keys.createDeviceKey("A") }
        val forged = a.copy(pub = filled(4, 65).hex())
        val e = runCatching { secrets.signingKey(forged).sign(ByteArray(32)) }.exceptionOrNull()
        assertEquals("the phone holds another key under this name", e?.message)
        assertEquals("the secret of this key is missing from the phone", runCatching { secrets.signingKey(a.copy(id = "gone")).sign(ByteArray(32)) }.exceptionOrNull()?.message)
    }

    companion object {
        const val NOW = 1_800_000_000L
        const val WORDS = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    }
}
