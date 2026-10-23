// The batch reference program (wiki: programs/batch): several calls in one
// transaction, in order, all or none. This is how an operation that pays no
// miner itself, such as a cancel from a time-locked account or a registry
// operation, travels with a fee paid by another account. A payment that
// sponsors the calls before it is bound to them with the coin's `within`
// condition and the progress prefix of those calls, so a miner cannot take it
// out of the batch.
package app.tingid.chain

const val BATCH_PREFIX_TAG = "app/batch/prefix"

/** Input `00`: a query for the progress prefix of the batch running now. */
val BATCH_QUERY: ByteArray get() = byteArrayOf(0)

class BatchCall(val target: ByteArray, val input: ByteArray)

/** `call*`, each `target(32) ‖ Bytes(input)`, back to back. */
fun batchInput(calls: List<BatchCall>): ByteArray {
    val w = Writer()
    for (c in calls) {
        require(c.target.size == 32) { "a call target is 32 bytes" }
        w.put(c.target).bytes(c.input)
    }
    return w.finish()
}

/** The result of a successful batch: each call's result, in order. */
fun decodeBatchResult(bytes: ByteArray): List<ByteArray>? = tryDecode {
    val r = Reader(bytes)
    val out = ArrayList<ByteArray>()
    while (r.remaining > 0) out.add(r.bytes())
    out
}

/** The prefix before the first call: `H("app/batch/prefix", "")`. */
fun batchPrefixStart(): ByteArray = taggedHash(BATCH_PREFIX_TAG)

/** The prefix after one more call: `H("app/batch/prefix", prefix ‖ target ‖ Bytes(input))`. */
fun batchPrefixNext(prefix: ByteArray, call: BatchCall): ByteArray =
    taggedHash(BATCH_PREFIX_TAG, prefix, call.target, u32le(call.input.size), call.input)

/** The prefix after `calls`: what a sponsoring payment placed right after them names. */
fun batchPrefix(calls: List<BatchCall>): ByteArray = calls.fold(batchPrefixStart(), ::batchPrefixNext)
