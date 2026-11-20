// What a transaction this device sent says about itself, read from its own
// bytes: the nonce it takes in each account's queue, and until when a block
// can take it. The pending list, the next nonce and the stuck warning all go
// by this one reading (state/Watch.kt, Chain.nextNonce), so they cannot
// disagree, and what an earlier version sent is read as what this one sends.
package app.tingid.state

import app.tingid.chain.BatchCall
import app.tingid.chain.Canonical
import app.tingid.chain.Coin
import app.tingid.chain.DecodeError
import app.tingid.chain.Reader
import app.tingid.chain.Tcr
import app.tingid.chain.decodeCall
import app.tingid.chain.equal
import app.tingid.chain.hex
import app.tingid.chain.policyAccount
import app.tingid.chain.tryDecode
import app.tingid.chain.unhex

/** A nonce a transaction takes in an account's queue: [coinQueue] for a coin, [callQueue] for a program's calls. */
data class Taken(val queue: String, val nonce: Long)

/**
 * What a transaction's bytes say it signed: the nonces it takes, and when it
 * expires, which is the earliest expiry of what it signed, since a block takes
 * all of it or none. Null `expires`: nothing in it expires, as with an
 * identity's change or a deployment.
 */
data class Signed(val takes: List<Taken>, val expires: Long?)

/** One operation of a transaction: the nonce it takes, if any, and its expiry (0 for none). */
private class Part(val take: Taken?, val validUntil: Long)

private val NOTHING = emptyList<Part>()

/** Readings by chain and bytes: the same bytes are read on every round and every screen. */
private val readings = object : LinkedHashMap<String, Signed?>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Signed?>) = size > 512
}

/**
 * What the transaction `raw` (hex) signed, from its bytes, on the chain whose
 * contracts are `c`; null when they cannot be read as a transaction this app
 * sends.
 */
fun readSigned(c: Canonical, raw: String): Signed? {
    val key = c.batch.hex() + raw
    synchronized(readings) { if (readings.containsKey(key)) return readings[key] }
    val signed = try {
        read(c, unhex(raw))
    } catch (e: IllegalArgumentException) {
        null
    }
    synchronized(readings) { readings[key] = signed }
    return signed
}

/**
 * What a submission signed, from the bytes kept for it: the transaction it
 * follows and, for a batch that pays for a call, the call sent alone, whichever
 * goes in. Null when the app has not got them or cannot read them, or does not
 * know the chain's contracts yet.
 */
fun readSubmission(c: Canonical?, outbox: Map<String, OutboxEntry>, id: String): List<Signed>? {
    if (c == null) return null
    val kept = outbox[id] ?: return null
    val own = readSigned(c, kept.raw) ?: return null
    val twin = kept.twin?.let { outbox[it] }?.let { readSigned(c, it.raw) ?: return null }
    return listOfNotNull(own, twin)
}

/** Until when a block could take any of `parts`: the latest of their expiries; null when one of them has none. */
fun expiryOf(parts: List<Signed>): Long? = if (parts.any { it.expires == null }) null else parts.maxOfOrNull { it.expires!! }

private fun read(c: Canonical, raw: ByteArray): Signed? {
    // A deployment is signed by no one and never expires.
    if (raw.size >= 2 && raw[0].toInt() == 1 && raw[1].toInt() == 1) return Signed(emptyList(), null)
    val call = decodeCall(raw) ?: return null
    val calls = if (equal(call.target, c.batch)) readBatch(call.input) ?: return null else listOf(BatchCall(call.target, call.input))
    val parts = calls.flatMap { readPart(c, it) ?: return null }
    return Signed(parts.mapNotNull { it.take }, parts.map { it.validUntil }.filter { it > 0 }.minOrNull())
}

/** The calls of a batch (wiki: programs/batch): `target(32) ‖ Bytes(input)`, back to back. */
private fun readBatch(input: ByteArray): List<BatchCall>? = tryDecode {
    val r = Reader(input)
    val calls = ArrayList<BatchCall>()
    while (r.remaining > 0) calls.add(BatchCall(r.array32(), r.bytes()))
    if (calls.isEmpty()) throw DecodeError("an empty batch")
    calls
}

private fun readPart(c: Canonical, call: BatchCall): List<Part>? {
    // An identity's operation takes no nonce of a coin or a program, and carries no expiry.
    if (equal(call.target, c.registry)) return NOTHING
    // A coin's input and a program's are told apart by reading them. What a site
    // chose, a call's values or a payment's recipient, can be made to read as the
    // other kind as well; the true reading always succeeds, so what reads as both
    // is no reading, and is never read wrong.
    val coin = readCoin(call.target, call.input)
    val program = readProgram(c, call.target, call.input)
    return if (coin != null && program != null) null else coin ?: program
}

/** A policy account's authorization of a coin operation: `00 ‖ policy ‖ Bytes(params) ‖ (u64 nonce) ‖ Bytes(witness)`, or `01` for the calling contract's. */
private class CoinAuth(val policy: ByteArray, val params: ByteArray, val nonce: Long)

private fun readCoinAuth(r: Reader, withNonce: Boolean): CoinAuth? = when (r.u8()) {
    Coin.AUTH_POLICY -> {
        val policy = r.array32()
        val params = r.bytes()
        val nonce = if (withNonce) r.u64() else 0
        r.bytes()
        CoinAuth(policy, params, nonce)
    }
    Coin.AUTH_CONTRACT -> null
    else -> throw DecodeError("unknown authorization")
}

/** A coin's input, read exactly: an action (op 10), a cancel (op 11) or a run (op 04); null for anything else. */
private fun readCoin(coin: ByteArray, input: ByteArray): List<Part>? = tryDecode {
    val r = Reader(input)
    val parts = when (r.u8()) {
        Coin.OP_EXECUTE -> {
            val auth = readCoinAuth(r, withNonce = true)
            val action = Coin.readAction(r)
            listOf(Part(auth?.let { Taken(coinQueue(coin.hex(), policyAccount(it.policy, it.params).hex()), it.nonce) }, action.timing.validUntil))
        }
        // A cancel takes no nonce, but expires.
        Coin.OP_CANCEL -> {
            readCoinAuth(r, withNonce = false)
            listOf(Part(null, Coin.readCancel(r).validUntil))
        }
        Coin.OP_RUN -> {
            r.array32()
            r.u64()
            NOTHING
        }
        else -> throw DecodeError("not a coin input this app sends")
    }
    r.end()
    parts
}

/**
 * A program's input (TCR-1): a run, a cancel, or a call. A call is
 * `T ‖ values ‖ 00 ‖ policy ‖ Bytes(params) ‖ u64 nonce ‖ u64 valid_until ‖
 * Bytes(witness) ‖ payments`; its values are laid out by its template, which
 * the bytes do not carry, so its approval is found as the one place where
 * reading from a 00 takes exactly the rest, by a policy this app signs with.
 * Two such places are no reading.
 */
private fun readProgram(c: Canonical, program: ByteArray, input: ByteArray): List<Part>? {
    if (input.size < 32) return null
    val head = input.copyOfRange(0, 32)
    if (equal(head, Tcr.RUN)) return tryDecode { Reader(input).run { array32(); array32(); u64(); end() }; NOTHING }
    if (equal(head, Tcr.CANCEL)) return tryDecode {
        val r = Reader(input)
        r.array32()
        r.array32()
        r.bytes()
        val validUntil = r.u64()
        r.bytes()
        val count = r.u64()
        if (count < 0 || count > r.remaining / 8) throw DecodeError("truncated")
        repeat(count.toInt()) { r.u64() }
        r.end()
        listOf(Part(null, validUntil))
    }
    val policies = listOf(c.policySecp256k1, c.policyP256, c.multisig, c.registry, c.policyWebauthn)
    var found: List<Part>? = null
    for (k in 32 until input.size) {
        // An approval starts only where a 00 is followed by one of the chain's policies, looked for in
        // place: values of zeros, up to the link's 64 KiB, cost a pass, not a copy of the rest at each byte.
        if (input[k].toInt() != 0 || !policyAt(policies, input, k + 1)) continue
        val parts = readApproval(c, program, input.copyOfRange(k, input.size)) ?: continue
        if (found != null) return null
        found = parts
    }
    return found
}

/** Whether `input` holds one of `policies` (32 bytes each) at `at`. */
private fun policyAt(policies: List<ByteArray>, input: ByteArray, at: Int): Boolean {
    if (at + 32 > input.size) return false
    for (p in policies) {
        var i = 0
        while (i < 32 && input[at + i] == p[i]) i++
        if (i == 32) return true
    }
    return false
}

/**
 * A call's approval and payment, from its `00`, to the end exactly. Its policy
 * is one of the chain's own, as every account this app signs for has (a key's,
 * or an identity's): runs of zeros, as a coin action's fee and tip often are,
 * would read as an approval otherwise.
 */
private fun readApproval(c: Canonical, program: ByteArray, tail: ByteArray): List<Part>? = tryDecode {
    val r = Reader(tail)
    if (r.u8() != 0) throw DecodeError("not a policy account's approval")
    val policy = r.array32()
    if (c.kindOf(policy) == null) throw DecodeError("not a policy this app signs with")
    val params = r.bytes()
    val nonce = r.u64()
    val validUntil = r.u64()
    r.bytes()
    val account = policyAccount(policy, params)
    val call = Part(Taken(callQueue(program, account), nonce), validUntil)
    when (r.u8()) {
        0 -> {
            r.end()
            listOf(call)
        }
        1 -> {
            val coin = r.array32()
            val payment = r.bytes()
            r.end()
            listOf(call, readPayment(coin, payment, policy, params))
        }
        else -> throw DecodeError("unknown payments")
    }
}

/** A call's payment: an action of the acting account itself (TCR-1), anything else is no reading of it. */
private fun readPayment(coin: ByteArray, input: ByteArray, policy: ByteArray, params: ByteArray): Part {
    val r = Reader(input)
    if (r.u8() != Coin.OP_EXECUTE) throw DecodeError("a payment is an action")
    val auth = readCoinAuth(r, withNonce = true) ?: throw DecodeError("a payment is a policy account's")
    if (!equal(auth.policy, policy) || !equal(auth.params, params)) throw DecodeError("a payment from another account")
    val action = Coin.readAction(r)
    r.end()
    return Part(Taken(coinQueue(coin.hex(), policyAccount(policy, params).hex()), auth.nonce), action.timing.validUntil)
}
