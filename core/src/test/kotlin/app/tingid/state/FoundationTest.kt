// The state's foundation: the chain cache keeps values and their identity, a
// late read never overwrites a newer one; the store is never overwritten when
// it cannot be read; identity reads come from one block; and a transaction
// whose answer was lost is kept, never counted as refused.
package app.tingid.state

import app.tingid.chain.BlockEvents
import app.tingid.chain.BlockReceipts
import app.tingid.chain.ChainApi
import app.tingid.chain.Coin
import app.tingid.chain.ContractManifest
import app.tingid.chain.EventPage
import app.tingid.chain.Http
import app.tingid.chain.HttpFailure
import app.tingid.chain.HttpResponse
import app.tingid.chain.Id
import app.tingid.chain.MaybeSent
import app.tingid.chain.NodeClient
import app.tingid.chain.NodeError
import app.tingid.chain.NodeStatus
import app.tingid.chain.Scheme
import app.tingid.chain.StorageRead
import app.tingid.chain.TransactionLookup
import app.tingid.chain.concat
import app.tingid.chain.filled
import app.tingid.chain.hex
import app.tingid.chain.transactionId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

class CacheTest {
    private val DAY = 86_400_000L
    private var clock = 1_800_000_000_000L
    private fun loaded(storage: TextStorage = MemoryStorage()) = ChainCache("aa", storage, scope) { clock }.also { it.load() }

    @Test
    fun `keeps the value screens have when a read changes nothing`() {
        val cache = loaded()
        val ser = Coin.AccountRecord.serializer()
        cache.put("coin:c1", Coin.AccountRecord(BigInteger.valueOf(5), BigInteger.ZERO, 1), ser, "t1")
        val first = cache.get("coin:c1", ser)!!.value
        val revision = cache.revision.value
        cache.put("coin:c1", Coin.AccountRecord(BigInteger.valueOf(5), BigInteger.ZERO, 1), ser, "t2")
        assertSame(first, cache.get("coin:c1", ser)!!.value)
        assertEquals("t2", cache.get("coin:c1", ser)!!.version)
        assertEquals(revision, cache.revision.value)
        cache.put("coin:c1", Coin.AccountRecord(BigInteger.valueOf(6), BigInteger.ZERO, 1), ser, "t3")
        assertEquals(BigInteger.valueOf(6), cache.get("coin:c1", ser)!!.value.balance)
        assertEquals(revision + 1, cache.revision.value)
    }

    @Test
    fun `drops a read that a newer one overtook`() {
        val cache = loaded()
        val ser = String.serializer()
        cache.begin(listOf("view:a"), "t1")
        assertFalse(cache.stale("view:a", "t1"))
        cache.begin(listOf("view:a"), "t2")
        cache.put("view:a", "old", ser, "t1")
        assertNull(cache.get("view:a", ser))
        cache.put("view:a", "new", ser, "t2")
        assertEquals("new", cache.get("view:a", ser)!!.value)
        assertFalse(cache.stale("view:a", "t2"))
        assertTrue(cache.stale("view:a", "t3"))
    }

    @Test
    fun `reads again after a failed read, and everything after a refresh`() {
        val cache = loaded()
        val ser = String.serializer()
        cache.begin(listOf("run:x"), "t1")
        cache.fail("run:x", "t1")
        assertTrue(cache.stale("run:x", "t1"))
        cache.put("run:x", "queued", ser, "t1")
        val epoch = cache.epoch.value
        cache.invalidate()
        assertEquals(epoch + 1, cache.epoch.value)
        assertTrue(cache.stale("run:x", "t1"))
        assertEquals("queued", cache.get("run:x", ser)!!.value)
    }

    @Test
    fun `comes back from the device as it was saved`() {
        val storage = MemoryStorage()
        val cache = loaded(storage)
        val info = IdentityInfo(filled(7), filled(8), Id.Core(0, 0, 0, 1, 1, 1, 0, 2, 0, 9, 8, 0), listOf(IdentityKey(0, Id.Key(filled(3), filled(4, 65)))), listOf(IdentitySlot(0, 0)), emptyList())
        cache.put("identity:r:i", info, IdentityInfo.serializer(), "t9")
        cache.put("run:y", null, RunStatus.serializer().nullable, "t9")
        cache.save()
        val again = loaded(storage)
        val back = again.get("identity:r:i", IdentityInfo.serializer())!!
        assertEquals("t9", back.version)
        assertEquals(info.id.hex(), back.value.id.hex())
        assertEquals(info.core, back.value.core)
        assertEquals(info.keys, back.value.keys)
        assertTrue(again.has("run:y"))
        // Another chain has a cache of its own.
        val other = ChainCache("bb", storage, scope).also { it.load() }
        assertFalse(other.has("identity:r:i"))
    }

    @Test
    fun `saves nothing before its saved copy was read, and leaves out values long unused`() {
        val storage = MemoryStorage()
        val early = ChainCache("aa", storage, scope) { clock }
        early.put("coin:c1", 1, Int.serializer(), "t1")
        early.save()
        assertTrue(storage.files.isEmpty())

        val cache = loaded(storage)
        cache.put("coin:old", 1, Int.serializer(), "t1")
        clock += 31 * DAY
        cache.put("coin:new", 2, Int.serializer(), "t2")
        cache.save()
        val again = loaded(storage)
        assertFalse(again.has("coin:old"))
        assertEquals(2, again.get("coin:new", Int.serializer())!!.value)
    }

    @Test
    fun `drops a copy saved in another format`() {
        val storage = MemoryStorage()
        storage.write(ChainCache.fileOf("aa"), """{"format":0,"entries":[["coin:c1","t1",0,1]]}""")
        assertFalse(loaded(storage).has("coin:c1"))
    }

    @Test
    fun `the current chain is the one the app last saw, until a node answers from another`() {
        val storage = MemoryStorage()
        val first = Caches(storage, scope)
        val cache = first.select("aa".repeat(32))
        cache.put("status", "five", String.serializer(), "")
        cache.save()
        val next = Caches(storage, scope)
        next.openLast()
        assertEquals("five", next.current.value!!.get("status", String.serializer())!!.value)
        assertFalse(next.select("bb".repeat(32)).has("status"))
        assertEquals("bb".repeat(32), storage.read(Caches.LAST_CHAIN))
    }
}

class StoreTest {
    private fun saved(state: String, format: Int = 1) = """{"format":$format,"state":$state}"""

    @Test
    fun `loads, and is written again on a change`() = runBlocking {
        val storage = MemoryStorage()
        storage.write(Store.FILE, saved("""{"settings":{"nodeUrls":["https://n"]},"users":[{"id":"u1","name":"Anna","color":"#4F46E5","kind":"key"}],"activeUserId":"u1"}"""))
        val store = Store(storage, listOf("https://default"))
        store.load()
        assertNull(store.loadError.value)
        assertEquals(listOf("Anna"), store.data.value.users.map { it.name })
        assertEquals(listOf("https://n"), store.data.value.settings.nodeUrls)
        store.addContact(Contact("c1", "Kari", "ab".repeat(32)))
        store.flush()
        assertTrue(storage.read(Store.FILE)!!.contains("Kari"))
    }

    @Test
    fun `that cannot be read is reported and never overwritten`() = runBlocking {
        val storage = MemoryStorage()
        storage.write(Store.FILE, """{"state": {"users": [""")
        val store = Store(storage, listOf("https://default"))
        store.load()
        assertTrue(store.loadError.value != null)
        assertFalse(store.loaded.value)
        store.addContact(Contact("c1", "Kari", "ab".repeat(32)))
        store.flush()
        assertEquals("""{"state": {"users": [""", storage.read(Store.FILE))
    }

    @Test
    fun `of another format is reported, not converted`() {
        val storage = MemoryStorage()
        storage.write(Store.FILE, saved("""{"settings":{"nodeUrls":[]}}""", format = 8))
        val store = Store(storage, emptyList())
        store.load()
        assertEquals("the saved data is from another version of Ting ID (format 8)", store.loadError.value)
    }

    @Test
    fun `starting fresh keeps the keys and phrases the unreadable state still lists`() = runBlocking {
        val storage = MemoryStorage()
        storage.write(Store.FILE, saved("""{"settings":{"nodeUrls":[]},"keys":[{"id":"k1","name":"Anna · Android phone","scheme":"secp256k1","pub":"04","createdAt":1}],"users":[{"id":"u1","name":"Anna","color":"#4F46E5","kind":"identity","phrase":"owner"},{"broken":true}],"contacts":7}"""))
        val store = Store(storage, listOf("https://default"))
        store.load()
        assertTrue(store.loadError.value != null)
        store.startFresh()
        store.flush()
        assertNull(store.loadError.value)
        assertEquals(listOf("k1"), store.data.value.keys.map { it.id })
        assertEquals(Scheme.Secp256k1, store.data.value.keys[0].scheme)
        assertEquals(listOf(StrayPhrase("u1", "Anna", PhraseLock.Owner)), store.data.value.strayPhrases)
        assertEquals(listOf("https://default"), store.data.value.settings.nodeUrls)
    }

    @Test
    fun `keeps the active user and the newest submissions`() {
        val store = Store(MemoryStorage(), emptyList(), currentChain = { "cc" })
        store.load()
        store.addUser(User("a", "A", "#000", UserKind.Key))
        store.addUser(User("b", "B", "#000", UserKind.Key))
        assertEquals("a", store.data.value.activeUserId)
        store.removeUser("a")
        assertEquals("b", store.data.value.activeUserId)
        for (i in 0 until 210) store.addSubmitted(SubmittedTx("t$i", i.toLong(), "x", "b"))
        assertEquals(200, store.data.value.submitted.size)
        assertEquals("t209", store.data.value.submitted.first().id)
        assertEquals("cc", store.data.value.submitted.first().chain)
        store.keepOutbox("p", OutboxEntry("00", at = 1))
        store.pairOutbox("p", "u")
        store.keepOutbox("p", OutboxEntry("01", at = 2))
        assertEquals("u", store.data.value.outbox["p"]!!.twin)
    }
}

/** A chain answering storage reads from a map; anything else is not asked here. */
private open class RecordChain(val get: (ByteArray) -> ByteArray?) : ChainApi {
    override val url = "test"
    override suspend fun status(): NodeStatus = error("not asked")
    override suspend fun storage(contract: ByteArray, key: ByteArray): ByteArray? = get(key)
    override suspend fun storageMany(reads: List<StorageRead>): List<ByteArray?> = reads.map { get(it.key) }
    override suspend fun storageFixed(reads: List<StorageRead>): List<ByteArray?> = storageMany(reads)
    override suspend fun contract(id: ByteArray): ContractManifest? = null
    override suspend fun contractExists(id: ByteArray): Boolean = false
    override suspend fun submit(tx: ByteArray, notBefore: Long?): String = error("not asked")
    override suspend fun transaction(id: String): TransactionLookup? = null
    override suspend fun blocks(from: Long, limit: Int): List<BlockReceipts> = emptyList()
    override suspend fun events(from: Long, limit: Int): List<BlockEvents> = emptyList()
    override suspend fun contractEvents(contract: ByteArray, topic: ByteArray?, from: Long, limit: Int): EventPage = EventPage(emptyList(), from, from)
}

class IdentityReadTest {
    private val registry = filled(0x1d)
    private val id = filled(0x2e)
    private val policy = filled(0x3f)
    private fun core(nextKeyId: Long) = Id.Core(0, 0, 0, 0, nextKeyId, nextKeyId, 0, 1, 0, 0, 0, 0)
    private fun keyRecord(n: Int) = concat(policy, filled(n, 40))

    @Test
    fun `read again from a core that changed between the reads`() = runBlocking {
        val records = HashMap(mapOf(Id.coreKey(id).hex() to Id.encodeCore(core(1)), Id.keyKey(id, 0, 0).hex() to keyRecord(1)))
        val node = object : RecordChain({ records[it.hex()] }) {
            override suspend fun storage(contract: ByteArray, key: ByteArray): ByteArray? {
                val value = records[key.hex()]
                // The identity gains a key right after the first read of its core.
                records[Id.coreKey(id).hex()] = Id.encodeCore(core(2))
                records[Id.keyKey(id, 0, 1).hex()] = keyRecord(2)
                return value
            }
        }
        val identity = fetchIdentity(node, registry, id)!!
        assertEquals(2L, identity.core.nextKeyId)
        assertEquals(listOf(0L, 1L), identity.keys.map { it.keyId })
    }

    @Test
    fun `is null when the registry has none`() = runBlocking {
        assertNull(fetchIdentity(RecordChain { null }, registry, id))
    }
}

class SubmitTest {
    private val chainId = filled(0x26)
    private val tx = byteArrayOf(1, 2, 3, 4)
    private val status = """{"chain_id":"${chainId.hex()}","height":10,"limits":[1,1,1,1],"tip":"00","work":"1"}"""
    private fun json(body: String, code: Int = 200) = HttpResponse(code, body.encodeToByteArray())

    private fun client(post: () -> HttpResponse, kept: MutableList<String>) = NodeClient(
        "http://node",
        Http { r -> if (r.method == "POST") post() else json(status) },
    ) { app.tingid.chain.SubmitListener { id, bytes, _ -> kept.add("$id:${bytes.hex()}") } }

    @Test
    fun `without an answer, keeps it under its own id and says it may be in`() = runBlocking {
        val kept = ArrayList<String>()
        val id = transactionId(chainId, tx).hex()
        val e = runCatching { client({ throw HttpFailure("could not be reached") }, kept).submit(tx) }.exceptionOrNull()
        assertTrue(e is MaybeSent)
        assertEquals(id, (e as MaybeSent).txId)
        assertEquals(listOf("$id:${tx.hex()}"), kept)
    }

    @Test
    fun `refused by the node, keeps nothing and says why`() = runBlocking {
        val kept = ArrayList<String>()
        val e = runCatching { client({ json("""{"error":"invalid signature"}""", 400) }, kept).submit(tx) }.exceptionOrNull()
        assertTrue(e is NodeError && e !is MaybeSent)
        assertEquals("invalid signature", e!!.message)
        assertEquals(emptyList<String>(), kept)
    }

    @Test
    fun `accepted, keeps it as submitted`() = runBlocking {
        val kept = ArrayList<String>()
        val id = transactionId(chainId, tx).hex()
        assertEquals(id, client({ json("""{"id":"$id"}""") }, kept).submit(tx))
        assertEquals(1, kept.size)
    }

    @Test
    fun `a node naming another id is refused`() = runBlocking {
        val e = runCatching { client({ json("""{"id":"${"00".repeat(32)}"}""") }, ArrayList()).submit(tx) }.exceptionOrNull()
        assertTrue(e is NodeError)
    }
}
