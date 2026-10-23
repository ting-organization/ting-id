// Transaction envelopes and contract identifiers (wiki: data/blocks#transactions, data/records#identifiers).
package app.tingid.chain

/** Resource allowance in C/S/D/G order. */
data class Resources(val c: Long, val s: Long, val d: Long, val g: Long) {
    fun toList() = listOf(c, s, d, g)
}

object Budgets {
    /** Canonical deployment budget (wiki: programs#canonical-deployment); every standard contract's id follows from it. */
    val DEPLOY = Resources(50_000_000, 64, 16_777_216, 16_777_216)

    /** A coin's deployment: the canonical budget, with two writes more for each allocation. */
    fun coinDeploy(allocations: Int) = DEPLOY.copy(s = DEPLOY.s + 2L * allocations)

    /** Budgets are upper bounds: blocks fill by consumption. Nested calls each hold back a reserve. */
    val EXECUTE = Resources(30_000_000, 512, 1_048_576, 1_048_576)
    val CALL = EXECUTE

    /** Unsigned coin operations, a reward claim or running a scheduled action: about 1.5 million C each, most of it the coin's code price. */
    val CLAIM = Resources(2_000_000, 64, 65_536, 65_536)

    /** An operation and the fee payment bound to it in one batch: room for two P-256 checks and the batch itself. */
    val BATCH = Resources(100_000_000, 1024, 2_097_152, 2_097_152)
}

private fun Writer.budget(r: Resources) = apply { for (n in r.toList()) u64(n) }

fun encodeCall(target: ByteArray, input: ByteArray, budget: Resources): ByteArray {
    require(target.size == 32) { "a call target is 32 bytes" }
    return Writer().u8(1).u8(0).budget(budget).put(target).bytes(input).finish()
}

fun encodeDeploy(code: ByteArray, input: ByteArray, budget: Resources): ByteArray =
    Writer().u8(1).u8(1).budget(budget).bytes(code).bytes(input).finish()

fun transactionId(chain: ByteArray, tx: ByteArray): ByteArray = taggedHash("transaction", concat(chain, tx))

/** ContractId of a deployment: chain, transaction id and the deployment ordinal. */
fun contractId(chain: ByteArray, tx: ByteArray, ordinal: Long = 0): ByteArray =
    taggedHash("contract", concat(chain, transactionId(chain, tx), u64le(ordinal)))

/** The id of a standard contract deployed canonically: empty input, standard budget. */
fun canonicalId(chain: ByteArray, code: ByteArray): ByteArray =
    contractId(chain, encodeDeploy(code, ByteArray(0), Budgets.DEPLOY))
