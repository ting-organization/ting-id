// What the app last read of a chain, shared by every screen and kept on the
// device: a screen shows the last values at once, and the node's answers for the
// current block replace them, so screens neither wait nor jump. Each chain has
// its own cache, whichever node it was read from; the app starts with the one
// of the chain it last saw. Nothing here goes into a transaction: what one
// needs, such as a nonce, is read from the node when it is built.
package app.tingid.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** A cached value and the chain tip it was read at. */
class Cached<T>(val value: T, val version: String)

class ChainCache(
    /** The chain, as hex. */
    val chain: String,
    private val storage: TextStorage,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Entry(val json: JsonElement, var version: String, val text: String, var at: Long) {
        /** The value as last decoded, with the serializer it was decoded with. */
        var decoded: Pair<KSerializer<*>, Any?>? = null
    }

    private val entries = HashMap<String, Entry>()

    /** The version each key is being read at: a read that a newer one overtook is dropped. */
    private val wanted = HashMap<String, String>()

    private val _revision = MutableStateFlow(0L)

    /** Counts changes, for screens to notice them. */
    val revision: StateFlow<Long> = _revision

    private val _epoch = MutableStateFlow(0L)

    /** Counts the times values were to be read again; watchers then read their stale keys. */
    val epoch: StateFlow<Long> = _epoch

    @Volatile
    var loaded = false
        private set

    private var saveJob: Job? = null

    @Synchronized
    fun has(key: String): Boolean = entries.containsKey(key)

    /** The value of `key`, decoded with `serializer`; the same object while a read changes nothing. */
    @Synchronized
    fun <T> get(key: String, serializer: KSerializer<T>): Cached<T>? {
        val e = entries[key] ?: return null
        val d = e.decoded
        @Suppress("UNCHECKED_CAST")
        if (d != null && d.first == serializer) return Cached(d.second as T, e.version)
        val value = try {
            Store.json.decodeFromJsonElement(serializer, e.json)
        } catch (x: Exception) {
            // A value saved by an older build that no longer reads: read it again.
            entries.remove(key)
            return null
        }
        e.decoded = serializer to value
        return Cached(value, e.version)
    }

    /** Whether `key` wants a read at `version`: not read at it, and no read at it on the way. */
    @Synchronized
    fun stale(key: String, version: String): Boolean = entries[key]?.version != version && wanted[key] != version

    /** Notes that reads of `keys` at `version` started. */
    @Synchronized
    fun begin(keys: List<String>, version: String) {
        for (key in keys) wanted[key] = version
    }

    /**
     * The value of `key` read at `version`; one that changed nothing keeps the
     * value screens already have. A read that a newer one overtook is dropped,
     * and so is one that began before the values were cleared or invalidated.
     */
    fun <T> put(key: String, value: T, serializer: KSerializer<T>, version: String) {
        val changed = synchronized(this) {
            val w = wanted[key]
            if (w != null && w != version) return
            wanted.remove(key)
            val json = Store.json.encodeToJsonElement(serializer, value)
            val text = json.toString()
            val old = entries[key]
            if (old != null && old.text == text) {
                old.version = version
                old.at = clock()
                false
            } else {
                entries[key] = Entry(json, version, text, clock()).also { it.decoded = serializer to value }
                true
            }
        }
        if (changed) changed()
        scheduleSave()
    }

    /** A read that failed: the key may be read again at the same version. */
    @Synchronized
    fun fail(key: String, version: String) {
        if (wanted[key] == version) wanted.remove(key)
    }

    /** Everything is read again, as the user asked to refresh. */
    fun invalidate() {
        synchronized(this) {
            for (e in entries.values) e.version = ""
            cancelReads()
        }
        retry()
    }

    /**
     * Drops every value, and a save on the way: the saved cache is being
     * deleted. A read still on the way is dropped when it ends, as it may be of
     * what was deleted.
     */
    fun clear() {
        synchronized(this) {
            saveJob?.cancel()
            saveJob = null
            entries.clear()
            cancelReads()
        }
        retry()
    }

    /** Reads on the way count for nothing: their keys are read again, and what they bring is dropped. */
    private fun cancelReads() {
        for (key in wanted.keys.toList()) wanted[key] = CANCELLED
    }

    /** Values that are not up to date are read again, as the node answers again. */
    fun retry() {
        _epoch.value++
        changed()
    }

    private fun changed() {
        _revision.value++
    }

    /** Reads the saved cache once; values read before it finished stay. */
    fun load() {
        if (loaded) return
        try {
            val text = storage.read(fileOf(chain))
            val root = text?.let { Store.json.parseToJsonElement(it) as? JsonObject }
            if (root != null && (root["format"] as? JsonPrimitive)?.intOrNull == FORMAT) {
                synchronized(this) {
                    for (item in root["entries"] as? JsonArray ?: JsonArray(emptyList())) {
                        val parts = item as? JsonArray ?: continue
                        if (parts.size != 4) continue
                        val key = parts[0].jsonPrimitive.content
                        val version = parts[1].jsonPrimitive.content
                        val at = parts[2].jsonPrimitive.longOrNull ?: continue
                        if (!entries.containsKey(key)) entries[key] = Entry(parts[3], version, parts[3].toString(), at)
                    }
                }
            }
        } catch (e: Exception) {
            // An unreadable cache is read again from the node.
        }
        loaded = true
        changed()
    }

    private fun scheduleSave() {
        synchronized(this) {
            saveJob?.cancel()
            saveJob = scope.launch {
                delay(SAVE_DELAY_MS)
                save()
            }
        }
    }

    /** Writes the cache, leaving out values no screen read for a month. */
    fun save() {
        // Not before the saved cache was read: that would overwrite it with less.
        if (!loaded) return
        val since = clock() - KEEP_MS
        val text = synchronized(this) {
            buildJsonObject {
                put("format", JsonPrimitive(FORMAT))
                put("entries", buildJsonArray {
                    for ((key, e) in entries) if (e.at >= since) add(buildJsonArray {
                        add(JsonPrimitive(key))
                        add(JsonPrimitive(e.version))
                        add(JsonPrimitive(e.at))
                        add(e.json)
                    })
                })
            }.toString()
        }
        try {
            storage.write(fileOf(chain), text)
        } catch (e: Exception) {
            // The cache is a convenience: values not saved are read again next time.
        }
    }

    companion object {
        /** The saved cache's format; one saved in another is dropped and read again. */
        const val FORMAT = 1

        /** The version a cancelled read is noted at: no read is at it, as versions are block ids. */
        private const val CANCELLED = "(cancelled)"

        /** A change is saved once this long passed without another. */
        private const val SAVE_DELAY_MS = 2_000L

        /** Values no screen read for this long are not saved again. */
        private const val KEEP_MS = 30 * 86_400_000L

        fun fileOf(chain: String) = "cache-$chain.json"
    }
}

/** Every chain's cache, and which is current: the chain the nodes are on, or the one the app last saw. */
class Caches(private val storage: TextStorage, private val scope: CoroutineScope) {
    private val caches = HashMap<String, ChainCache>()
    private val _current = MutableStateFlow<ChainCache?>(null)
    val current: StateFlow<ChainCache?> = _current

    @Synchronized
    private fun cacheFor(chain: String): ChainCache = caches.getOrPut(chain) { ChainCache(chain, storage, scope).also { it.load() } }

    /** A node answered from `chain`: its cache is the one screens use from now. */
    fun select(chain: String): ChainCache {
        val now = _current.value
        if (now?.chain == chain) return now
        val cache = cacheFor(chain)
        _current.value = cache
        try {
            storage.write(LAST_CHAIN, chain)
        } catch (e: Exception) {
            // Only where the app starts next time.
        }
        return cache
    }

    /** The chain the app is on (hex), or null before any node answered. */
    fun currentChain(): String? = _current.value?.chain

    /** Everything of the current chain is read again: what came from a node found to give wrong answers. */
    fun invalidateCurrent() {
        _current.value?.invalidate()
    }

    /** Every chain's values are dropped, as all saved data is deleted. */
    @Synchronized
    fun forget() {
        for (c in caches.values) c.clear()
    }

    /** The chain the app last saw (hex), as saved, or null. */
    fun lastChainSeen(): String? = try {
        storage.read(LAST_CHAIN)?.takeIf { app.tingid.chain.isHex(it, 32) }
    } catch (e: Exception) {
        null
    }

    /** Opens the cache of the chain the app last saw, before the first screen shows, so the app starts with the last values. */
    fun openLast() {
        val chain = lastChainSeen() ?: return
        if (_current.value == null) _current.value = cacheFor(chain)
    }

    companion object {
        const val LAST_CHAIN = "cache-chain.txt"
    }
}
