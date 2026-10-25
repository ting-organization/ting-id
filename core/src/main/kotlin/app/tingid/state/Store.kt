// Device state: settings, keys, users, contacts and submitted transactions,
// saved as one JSON file after every change. Key secrets are in the vault,
// never here.
package app.tingid.state

import app.tingid.chain.Id
import app.tingid.chain.hex
import app.tingid.chain.nodeAddress
import app.tingid.chain.unhex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.util.UUID

/** Where the app keeps its files: the app's own storage on the phone, a map in tests. */
interface TextStorage {
    fun read(name: String): String?

    /** Writes the whole file or nothing. */
    fun write(name: String, text: String)
    fun delete(name: String)
    fun names(): List<String>
}

class MemoryStorage : TextStorage {
    val files = java.util.concurrent.ConcurrentHashMap<String, String>()
    override fun read(name: String): String? = files[name]
    override fun write(name: String, text: String) {
        files[name] = text
    }
    override fun delete(name: String) {
        files.remove(name)
    }
    override fun names(): List<String> = files.keys.toList()
}

val USER_COLORS = listOf("#4F46E5", "#0EA5A4", "#D97706", "#DB2777", "#059669", "#7C3AED", "#2563EB", "#DC2626")

fun nextColor(users: List<User>): String = USER_COLORS[users.size % USER_COLORS.size]

/** A stable color for a coin, from its contract id. */
fun coinColor(id: String): String = USER_COLORS[(id.take(2).toIntOrNull(16) ?: 0) % USER_COLORS.size]

/** The nodes a new installation starts with: `named` (comma-separated), cleaned up, without repeats. */
fun defaultNodes(named: String): List<String> = named.split(',').mapNotNull { nodeAddress(it) }.distinct()

class Store(
    private val storage: TextStorage,
    /** The nodes a new installation starts with. */
    private val nodes: List<String>,
    /** The chain the app is on, which new contacts and submissions are noted with. */
    private val currentChain: () -> String? = { null },
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val _data = MutableStateFlow(empty())
    val data: StateFlow<AppData> = _data

    private val _loaded = MutableStateFlow(false)

    /** Whether the saved state was read; nothing shows before. */
    val loaded: StateFlow<Boolean> = _loaded

    private val _loadError = MutableStateFlow<String?>(null)

    /** Why the saved state could not be read; the app shows only this until the person starts fresh. */
    val loadError: StateFlow<String?> = _loadError

    private val _saveError = MutableStateFlow<String?>(null)

    /** Why the last change could not be saved, until one is: it is lost when the app closes. */
    val saveError: StateFlow<String?> = _saveError

    /**
     * Nothing is written before the saved state was read: a state that could not
     * be read is never overwritten, unless the person chooses to start fresh.
     */
    @Volatile
    private var writable = false

    private val writes = Channel<AppData>(Channel.CONFLATED)
    private val writing = Mutex()

    init {
        scope.launch {
            for (snapshot in writes) writing.withLock { write(snapshot) }
        }
    }

    private fun empty() = AppData(settings = Settings(nodeUrls = nodes))

    // ---- Loading and saving ----------------------------------------------------

    /** Reads the saved state; a state saved in a format this app does not know is reported, never overwritten. */
    fun load() {
        val text = try {
            storage.read(FILE)
        } catch (e: Exception) {
            _loadError.value = "the saved data could not be read"
            return
        }
        if (text == null) {
            writable = true
            _loaded.value = true
            return
        }
        try {
            val root = json.parseToJsonElement(text).jsonObject
            val format = (root["format"] as? JsonPrimitive)?.intOrNull
            if (format != FORMAT) throw IllegalStateException("the saved data is from another version of Ting ID (format $format)")
            _data.value = json.decodeFromJsonElement<AppData>(root.getValue("state"))
            writable = true
            _loaded.value = true
        } catch (e: Exception) {
            _loadError.value = e.message?.takeIf { it.startsWith("the saved data") } ?: "the saved data could not be read"
        }
    }

    private fun write(snapshot: AppData) {
        if (!writable) return
        try {
            storage.write(FILE, encode(snapshot))
            _saveError.value = null
        } catch (e: Exception) {
            _saveError.value = e.message ?: "the phone refused to save"
        }
    }

    /** Waits until every change so far is written; for leaving the app and for tests. */
    suspend fun flush() {
        writing.withLock { write(_data.value) }
    }

    private fun encode(d: AppData): String = buildJsonObject {
        put("format", JsonPrimitive(FORMAT))
        put("state", json.encodeToJsonElement(d))
    }.toString()

    /** Changes the state and saves it. */
    fun update(change: (AppData) -> AppData) {
        _data.update(change)
        writes.trySend(_data.value)
    }

    /**
     * After a load error, the person chose to start over: the saved data goes and
     * the app starts empty but for the keys and recovery phrases it still lists.
     */
    fun startFresh() {
        val listed = listed()
        storage.delete(FILE)
        writable = true
        _data.value = empty().copy(keys = listed.first, strayPhrases = listed.second)
        _loadError.value = null
        _loaded.value = true
        writes.trySend(_data.value)
    }

    /**
     * All saved data goes, and the app starts empty but for what is still in the
     * vault: `keep`, keys whose secret is, and `phrases`, words that are.
     */
    fun clearEverything(keep: List<KeyMeta>, phrases: List<StrayPhrase>) {
        for (name in storage.names()) storage.delete(name)
        _data.value = empty().copy(keys = keep, strayPhrases = phrases)
        writes.trySend(_data.value)
    }

    /** The keys and recovery phrases listed in saved data the app cannot load, where they still read as such. */
    private fun listed(): Pair<List<KeyMeta>, List<StrayPhrase>> {
        val state = try {
            storage.read(FILE)?.let { json.parseToJsonElement(it).jsonObject["state"] as? JsonObject }
        } catch (e: Exception) {
            null
        } ?: return emptyList<KeyMeta>() to emptyList()
        val keys = (state["keys"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull {
            runCatching { json.decodeFromJsonElement<KeyMeta>(it) }.getOrNull()
        } ?: emptyList()
        val users = (state["users"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull {
            runCatching { json.decodeFromJsonElement<User>(it) }.getOrNull()?.let { u -> u.phrase?.let { p -> StrayPhrase(u.id, u.name, p) } }
        } ?: emptyList()
        val stray = (state["strayPhrases"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull {
            runCatching { json.decodeFromJsonElement<StrayPhrase>(it) }.getOrNull()
        } ?: emptyList()
        return keys to (users + stray)
    }

    // ---- Changes -----------------------------------------------------------------

    fun setSettings(change: (Settings) -> Settings) = update { it.copy(settings = change(it.settings)) }

    fun addCoin(id: String) = update {
        it.copy(settings = it.settings.copy(coins = if (id in it.settings.coins) it.settings.coins else it.settings.coins + id, dismissed = it.settings.dismissed - id))
    }

    fun removeCoin(id: String) = update {
        it.copy(settings = it.settings.copy(coins = it.settings.coins - id), activeCoin = if (it.activeCoin == id) null else it.activeCoin)
    }

    fun dismissCoin(id: String) = update { it.copy(settings = it.settings.copy(dismissed = (it.settings.dismissed + id).distinct())) }
    fun setActiveCoin(id: String?) = update { it.copy(activeCoin = id) }
    fun addKey(key: KeyMeta) = update { it.copy(keys = it.keys + key) }
    fun renameKey(id: String, name: String) = update { d -> d.copy(keys = d.keys.map { if (it.id == id) it.copy(name = name) else it }) }
    fun removeKey(id: String) = update { d -> d.copy(keys = d.keys.filter { it.id != id }) }
    fun addUser(user: User) = update { it.copy(users = it.users + user, activeUserId = it.activeUserId ?: user.id) }
    fun updateUser(id: String, change: (User) -> User) = update { d -> d.copy(users = d.users.map { if (it.id == id) change(it) else it }) }

    fun removeUser(id: String) = update { d ->
        val users = d.users.filter { it.id != id }
        d.copy(users = users, activeUserId = if (d.activeUserId == id) users.firstOrNull()?.id else d.activeUserId, activeSlot = 0)
    }

    fun setActiveUser(id: String?) = update { it.copy(activeUserId = id, activeSlot = 0) }
    fun setActiveSlot(slot: Int) = update { it.copy(activeSlot = slot) }
    fun addContact(contact: Contact) = update { it.copy(contacts = it.contacts + contact.copy(chain = contact.chain ?: currentChain())) }
    fun removeContact(id: String) = update { d -> d.copy(contacts = d.contacts.filter { it.id != id }) }
    fun renameContact(id: String, name: String) = update { d -> d.copy(contacts = d.contacts.map { if (it.id == id) it.copy(name = name) else it }) }

    /**
     * Names identity `identity` (hex ID) on `registry`'s chain: the contact
     * with its Spending account gets the name and the identity, or a new one
     * is saved for them.
     */
    fun nameIdentity(name: String, identity: String, registry: ByteArray) = update { d ->
        val id = identity.lowercase()
        val account = Id.account(registry, unhex(id), 0).hex()
        val chain = currentChain()
        val same = d.contacts.firstOrNull { it.account == account && (it.chain == null || it.chain == chain) }
        val contacts = if (same != null) {
            d.contacts.map { if (it.id == same.id) it.copy(name = name, identity = id) else it }
        } else {
            d.contacts + Contact(UUID.randomUUID().toString(), name, account, chain, identity = id)
        }
        d.copy(contacts = contacts)
    }

    /** A new submission is followed until a block includes it, on the chain it was sent to. */
    fun addSubmitted(tx: SubmittedTx) = update { d ->
        val entry = tx.copy(chain = tx.chain ?: currentChain())
        d.copy(submitted = (listOf(entry) + d.submitted.filter { it.id != tx.id }).take(200))
    }

    fun updateSubmitted(id: String, change: (SubmittedTx) -> SubmittedTx) =
        update { d -> d.copy(submitted = d.submitted.map { if (it.id == id) change(it) else it }) }

    /**
     * The batch `tx` pays for `unpaid`, an operation sent alone before: the
     * submission that followed `unpaid` follows the batch from now on, with
     * the batch's id, time and summary, so the operation shows once; without
     * one still followed, `tx` is added. The batch's bytes name `unpaid` as
     * their twin ([pairOutbox]), so whichever goes in settles it.
     */
    fun followPaid(unpaid: String, tx: SubmittedTx) = update { d ->
        val old = d.submitted.firstOrNull { it.id == unpaid && it.userId == tx.userId && (it.state == TxState.Pending || it.state == TxState.Failed) }
        val entry = old?.copy(id = tx.id, at = tx.at, summary = tx.summary, state = TxState.Pending, height = null, problem = null)
            ?: tx.copy(chain = tx.chain ?: currentChain())
        d.copy(submitted = (listOf(entry) + d.submitted.filter { it !== old && it.id != tx.id }).take(200))
    }

    fun keepOutbox(id: String, entry: OutboxEntry) = update { d -> d.copy(outbox = d.outbox + (id to entry.copy(twin = d.outbox[id]?.twin))) }

    /** Notes that `unpaid` is the operation of the batch `paid` sent alone. */
    fun pairOutbox(paid: String, unpaid: String) = update { d ->
        val e = d.outbox[paid] ?: return@update d
        d.copy(outbox = d.outbox + (paid to e.copy(twin = unpaid)))
    }

    fun forgetOutbox(id: String) = update { d -> if (id in d.outbox) d.copy(outbox = d.outbox - id) else d }
    fun noteNonce(key: String, note: NonceNote) = update { it.copy(nonces = it.nonces + (key to note)) }

    /** The first configuration read of a coin stays: it never changes. */
    fun pinCoinConfig(coinHex: String, configHex: String) = update { d ->
        if (coinHex in d.coinConfigs) d else d.copy(coinConfigs = d.coinConfigs + (coinHex to configHex))
    }

    fun addSignature(note: SignatureNote) = update { it.copy(signatures = (listOf(note) + it.signatures).take(200)) }
    fun clearSignatures() = update { it.copy(signatures = emptyList()) }
    fun setStrayPhrases(phrases: List<StrayPhrase>) = update { it.copy(strayPhrases = phrases) }

    /** Every transaction a node accepts is kept until a block includes it (Pending). */
    fun keepSubmission(txId: String, tx: ByteArray, notBefore: Long?) =
        keepOutbox(txId, OutboxEntry(raw = tx.hex(), notBefore = notBefore, at = now()))

    /** The contacts saved on `chain`, and those saved before contacts carried theirs. */
    fun contactsOn(chain: String?): List<Contact> = data.value.contacts.filter { it.chain == null || it.chain == chain }

    companion object {
        const val FILE = "state.json"
        const val FORMAT = 1
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
            explicitNulls = false
        }
    }
}
