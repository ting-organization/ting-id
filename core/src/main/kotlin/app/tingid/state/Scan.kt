// The user's activity as a scan of a range of blocks: which events in them
// touch the user's accounts or IDs. The range grows forward with each new block
// and backward when older history is asked for, so no block is read twice, and
// it is saved on the device between runs. Pure: no storage, no node.
package app.tingid.state

import app.tingid.chain.BlockEvents
import app.tingid.chain.ByContent
import app.tingid.chain.CallEvent
import app.tingid.chain.Coin
import app.tingid.chain.Id
import app.tingid.chain.Tcr
import app.tingid.chain.contractAccount
import app.tingid.chain.equal
import app.tingid.chain.hex
import app.tingid.chain.isHex
import app.tingid.chain.unhex
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** What a scan looks for: everything of one user on one chain, whichever screen shows it. */
class ScanScope(
    val chainId: ByteArray,
    val registry: ByteArray,
    val accounts: List<ByteArray>,
    val ids: List<ByteArray>,
) : ByContent() {
    override fun toString() =
        "ScanScope(chainId=${chainId.hex()}, registry=${registry.hex()}, accounts=${accounts.map { it.hex() }}, ids=${ids.map { it.hex() }})"
}

/** A found event as saved: height, transaction, contract, payload, whether it paid a fee or tip, and its block's time. */
data class SavedItem(
    val height: Long,
    val transaction: String,
    /** Hex. */
    val contract: String,
    /** Hex. */
    val payload: String,
    val fee: Boolean,
    val time: Long,
)

/** A scan as saved: blocks `low` to `high` are read, and `tip` is the id of block `high` when known. */
data class SavedScan(
    val low: Long,
    val high: Long,
    val tip: String,
    /** Oldest first. */
    val items: List<SavedItem>,
) {
    companion object {
        /** The saved form this version reads; an older one, saved without times or program calls, is read again from the chain. */
        const val VERSION = 3
    }
}

/** Blocks a reorganization is assumed to reach at most: read again when the tip changed. */
const val REORG_DEPTH = 20L

fun scopeKey(s: ScanScope): String {
    fun sorted(xs: List<ByteArray>) = xs.map { it.hex() }.sorted().joinToString(",")
    return "${s.chainId.hex()}:${s.registry.hex()}:${sorted(s.accounts)}:${sorted(s.ids)}"
}

private fun List<ByteArray>.has(x: ByteArray) = any { equal(it, x) }

fun touches(event: Coin.Event, accounts: List<ByteArray>, @Suppress("UNUSED_PARAMETER") ids: List<ByteArray>): Boolean = when (event) {
    is Coin.Event.Transfer -> accounts.has(event.from) || accounts.has(event.to)
    is Coin.Event.Scheduled -> accounts.has(event.account)
    is Coin.Event.Executed -> accounts.has(event.account)
    is Coin.Event.Cancelled -> accounts.has(event.account)
    is Coin.Event.ProducerPayment -> accounts.has(event.payee)
    is Coin.Event.Minter -> false
}

fun touches(event: Id.Event, @Suppress("UNUSED_PARAMETER") accounts: List<ByteArray>, ids: List<ByteArray>): Boolean = ids.has(event.id) || when (event) {
    is Id.Event.RecoveryApproved -> ids.has(event.guardian)
    is Id.Event.RecoveryWithdrawn -> ids.has(event.guardian)
    else -> false
}

/**
 * The events of some blocks that touch the scope: identity events of the
 * registry, and events of any contract that read as a program's call events
 * (TCR-1.md) or coin events, so a program or coin the user does not follow is
 * found too. A zero transfer of an account to
 * itself only carries a fee bound to a batch and is left out. Each keeps its
 * block's time.
 */
fun matchBlocks(blocks: List<BlockEvents>, scope: ScanScope): List<SavedItem> {
    val out = ArrayList<SavedItem>()
    for (b in blocks) {
        for ((i, e) in b.events.withIndex()) {
            fun item(fee: Boolean) = SavedItem(b.height, e.transaction ?: "", e.contract.hex(), e.payload.hex(), fee, b.time)
            if (equal(e.contract, scope.registry)) {
                val ev = Id.decodeEvent(e.payload)
                if (ev != null && touches(ev, scope.accounts, scope.ids)) out.add(item(false))
                continue
            }
            val call = Tcr.decodeEvent(e.payload)
            if (call != null) {
                if (scope.accounts.has(call.account)) out.add(item(false))
                continue
            }
            val ev = Coin.decodeEvent(e.payload) ?: continue
            if (!touches(ev, scope.accounts, scope.ids)) continue
            if (ev is Coin.Event.Transfer && ev.amount.signum() == 0 && equal(ev.from, ev.to)) continue
            // A fee or tip is a transfer followed by its producer payment.
            var fee = false
            val next = b.events.getOrNull(i + 1)
            if (ev is Coin.Event.Transfer && next != null && next.transaction == e.transaction && equal(next.contract, e.contract)) {
                val paid = Coin.decodeEvent(next.payload)
                fee = paid is Coin.Event.ProducerPayment && equal(paid.payee, ev.to) && paid.amount == ev.amount
            }
            out.add(item(fee))
        }
    }
    return out
}

/** A scan that has read nothing yet, about to read back from `height`. */
fun emptyScan(height: Long): SavedScan = SavedScan(low = height + 1, high = height, tip = "", items = emptyList())

/** Blocks read forward, `from` the block after `high` (or `high` itself, to see the tip is unchanged). */
fun addNewer(scan: SavedScan, blocks: List<BlockEvents>, scope: ScanScope): SavedScan {
    val fresh = blocks.filter { it.height > scan.high }
    if (fresh.isEmpty()) return scan
    val last = fresh.last()
    return scan.copy(high = last.height, tip = last.block, items = scan.items + matchBlocks(fresh, scope))
}

/** Blocks read backward: the ones just below `low`, ending at `low - 1`. */
fun addOlder(scan: SavedScan, blocks: List<BlockEvents>, scope: ScanScope): SavedScan {
    val older = blocks.filter { it.height < scan.low }
    if (older.isEmpty()) return scan
    val tipBlock = older.firstOrNull { it.height == scan.high }
    return scan.copy(low = older[0].height, tip = tipBlock?.block ?: scan.tip, items = matchBlocks(older, scope) + scan.items)
}

/** Forgets the blocks above `height`, to read them again after a reorganization. */
fun rollBack(scan: SavedScan, height: Long): SavedScan {
    val high = maxOf(height, scan.low - 1)
    return scan.copy(high = high, tip = "", items = scan.items.filter { it.height <= high })
}

/**
 * The scan as kept on the device: at most `keep` items, the newest, cut at a
 * block boundary so that the blocks below `low` hold everything left out.
 */
fun trimmed(scan: SavedScan, keep: Int): SavedScan {
    if (scan.items.size <= keep) return scan
    val cut = scan.items[scan.items.size - keep - 1].height
    return scan.copy(low = cut + 1, items = scan.items.filter { it.height > cut })
}

/**
 * Saved items as activity, newest first, with each scheduled action marked
 * when a later event ran or cancelled it. A program call takes in what its
 * account burned or paid into the program in its transaction, which shows as
 * part of the call rather than as payments of their own. A fee joins the other
 * event of its transaction, the one it paid for; only a fee paid for nothing
 * else shown stays an item of its own.
 */
fun activityOf(items: List<SavedItem>, registry: ByteArray): List<ActivityItem> {
    val out = ArrayList<ActivityItem>()
    // Items are values: a later event marks an earlier one by its place in `out`.
    val scheduled = HashMap<String, Int>()
    val registryHex = registry.hex()
    for (saved in items) {
        val contract = unhex(saved.contract)
        val payload = unhex(saved.payload)
        if (saved.contract == registryHex) {
            val identity = Id.decodeEvent(payload)
            if (identity != null) out.add(ActivityItem(saved.height, saved.time, saved.transaction, contract, identity = identity))
            continue
        }
        val call = Tcr.decodeEvent(payload)
        val coin = if (call == null) Coin.decodeEvent(payload) ?: continue else null
        var item = ActivityItem(saved.height, saved.time, saved.transaction, contract, coin = coin, call = call, fee = saved.fee)
        val (key, settled) = settling(saved.contract, coin, call) ?: (null to null)
        if (key != null && settled == null) scheduled[key] = out.size
        val at = key?.let { scheduled[it] }
        if (settled != null && at != null) {
            val origin = out[at]
            out[at] = origin.copy(settled = settled)
            (origin.coin as? Coin.Event.Scheduled)?.let { s -> item = item.copy(plan = s.effect) }
            if (origin.call != null) item = item.copy(run = settled == Settled.Executed)
        }
        out.add(item)
    }
    // Each transfer joins the call of its transaction it was for: its account's, into its program, or any of its account's for a burn.
    val calls = HashMap<String, MutableList<Int>>()
    for ((i, item) in out.withIndex()) if (item.call != null) calls.getOrPut(item.transaction) { ArrayList() }.add(i)
    val paid = HashMap<Int, MutableList<Spent>>()
    val folded = HashSet<Int>()
    for ((i, item) in out.withIndex()) {
        val transfer = item.coin as? Coin.Event.Transfer ?: continue
        if (item.fee) continue
        val burn = equal(transfer.to, Coin.ZERO_ACCOUNT)
        val at = calls[item.transaction]?.firstOrNull { c ->
            equal(out[c].call!!.account, transfer.from) && (burn || equal(transfer.to, contractAccount(out[c].contract)))
        } ?: continue
        // A fee payment moves nothing: it only carries the fee, which joins the call below.
        if (transfer.amount.signum() > 0) paid.getOrPut(at) { ArrayList() }.add(Spent(item.contract, transfer.from, transfer.amount))
        folded.add(i)
    }
    val paidFor = HashMap<String, Int>()
    for ((i, item) in out.withIndex()) if (!item.fee && i !in folded && item.transaction !in paidFor) paidFor[item.transaction] = i
    val fees = HashMap<Int, MutableList<Spent>>()
    val kept = ArrayList<Int>()
    for ((i, item) in out.withIndex()) {
        if (i in folded) continue
        val transfer = item.coin as? Coin.Event.Transfer
        val owner = if (item.fee && transfer != null) paidFor[item.transaction] else null
        if (owner == null || transfer == null) {
            kept.add(i)
            continue
        }
        fees.getOrPut(owner) { ArrayList() }.add(Spent(item.contract, transfer.from, transfer.amount))
    }
    return kept.reversed().map { i ->
        var item = out[i]
        paid[i]?.let { item = item.copy(paid = it) }
        fees[i]?.let { item = item.copy(fees = item.fees + it) }
        item
    }
}

/**
 * For an event that schedules an action or operation, or later runs or cancels
 * it: the key the two share, and which of the later ones it is (null for the
 * scheduling). Null for any other event. A program's operations are kept apart
 * from coin actions, should one contract emit both.
 */
private fun settling(contract: String, coin: Coin.Event?, call: CallEvent?): Pair<String, Settled?>? {
    fun key(account: ByteArray, sequence: Long) = "$contract:${account.hex()}:$sequence"
    return when {
        call != null -> "call:${key(call.account, call.sequence)}" to when (call) {
            is CallEvent.Scheduled -> null
            is CallEvent.Executed -> Settled.Executed
            is CallEvent.Cancelled -> Settled.Cancelled
        }
        coin is Coin.Event.Scheduled -> key(coin.account, coin.sequence) to null
        coin is Coin.Event.Executed -> key(coin.account, coin.sequence) to Settled.Executed
        coin is Coin.Event.Cancelled -> key(coin.account, coin.sequence) to Settled.Cancelled
        else -> null
    }
}

// ---- Saved form ----------------------------------------------------------------
//
// `{"v":3,"low":…,"high":…,"tip":"…","items":[[height,transaction,contract,payload,fee,time],…]}`:
// items as arrays, as a scan keeps up to a thousand of them.

fun encodeScan(scan: SavedScan): String = buildJsonObject {
    put("v", JsonPrimitive(SavedScan.VERSION))
    put("low", JsonPrimitive(scan.low))
    put("high", JsonPrimitive(scan.high))
    put("tip", JsonPrimitive(scan.tip))
    put("items", buildJsonArray {
        for (item in scan.items) add(buildJsonArray {
            add(JsonPrimitive(item.height))
            add(JsonPrimitive(item.transaction))
            add(JsonPrimitive(item.contract))
            add(JsonPrimitive(item.payload))
            add(JsonPrimitive(if (item.fee) 1 else 0))
            add(JsonPrimitive(item.time))
        })
    })
}.toString()

/**
 * A saved scan, if it is one this version reads: an older one, saved without
 * times, or a damaged one is read again from the chain.
 */
fun decodeScan(text: String?): SavedScan? = try {
    val root = text?.let { Store.json.parseToJsonElement(it) } as? JsonObject
    if (root == null || root.int("v") != SavedScan.VERSION) {
        null
    } else {
        val low = root.long("low")
        val high = root.long("high")
        val tip = root.string("tip")
        val items = (root["items"] as? JsonArray)?.map { savedItem(it as? JsonArray) }
        if (low == null || high == null || tip == null || items == null || items.any { it == null }) null
        else SavedScan(low, high, tip, items.map { it!! })
    }
} catch (e: Exception) {
    null
}

private fun savedItem(parts: JsonArray?): SavedItem? {
    if (parts == null || parts.size != 6) return null
    val height = (parts[0] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: return null
    val transaction = (parts[1] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    val contract = (parts[2] as? JsonPrimitive)?.takeIf { it.isString && isHex(it.content, 32) }?.content ?: return null
    val payload = (parts[3] as? JsonPrimitive)?.takeIf { it.isString && isHex(it.content) }?.content ?: return null
    val fee = (parts[4] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it == 0 || it == 1 } ?: return null
    val time = (parts[5] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: return null
    return SavedItem(height, transaction, contract, payload, fee == 1, time)
}

private fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
