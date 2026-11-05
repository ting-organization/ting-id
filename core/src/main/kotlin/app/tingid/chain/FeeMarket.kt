// What recent transactions paid for their room in a block, and the fee an
// operation pays from that. Miners take transactions by what they pay the
// block's producer per share of the block, a transaction's share being its
// largest fraction of any capacity (wiki: programs/conventions, producer
// payments). So a fee is a rate per whole block times the share an operation
// takes: the median rate of recent paying transactions, times the share of a
// block that operations of its kind take, measured by the same rule against
// the same capacity as the rates.
package app.tingid.chain

import java.math.BigInteger
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong

object FeeMarket {
    /**
     * What an operation of a kind uses of a block, C / S / D / G: compute as
     * measured on the network, the others as the reference programs' cost
     * measurements give them. Its share of a block is taken by [shareOf], as
     * a paying transaction's is in [ratesOf]. No margin: what one operation
     * pays is the next one's rate, so anything added would compound.
     */
    enum class Share(val used: Resources) {
        /** A coin action carrying its own fee, such as a payment. */
        Own(Resources(15_600_000, 17, 841, 76)),

        /** An operation with its fee in a batch beside it, such as a payment in another coin or an identity change. */
        Bundled(Resources(25_200_000, 44, 1_333, 68)),

        /** The run of a scheduled action, which pays its own tip. */
        Run(Resources(1_480_000, 16, 712, 0)),
        ;

        /** Its share of a block of capacity `limits`. */
        fun of(limits: List<Long>): Double = shareOf(used.toList(), limits)
    }

    /** How many recent paying transactions the median is taken over. */
    const val SAMPLE = 50

    /** A transaction's share of a block: its largest fraction of any capacity. */
    fun shareOf(used: List<Long>, limits: List<Long>): Double {
        var share = 0.0
        limits.forEachIndexed { i, limit ->
            // A node's numbers are not taken on trust: anything not a positive count counts nothing.
            val u = used.getOrNull(i) ?: 0
            if (limit > 0 && u >= 0) share = maxOf(share, u.toDouble() / limit)
        }
        return share
    }

    /** The payout account in producer data: after "OCP1", the first entry of type 1 that is 32 bytes. */
    fun payoutAccount(producer: ByteArray): ByteArray? {
        if (producer.size < 4 || !producer.copyOfRange(0, 4).contentEquals(utf8("OCP1"))) return null
        var at = 4
        fun u32(i: Int): Long = (0..3).fold(0L) { v, k -> v or ((producer[i + k].toLong() and 0xff) shl (8 * k)) }
        while (at + 8 <= producer.size) {
            val kind = u32(at)
            val length = u32(at + 4)
            if (at + 8 + length > producer.size) return null
            if (kind == 1L && length == 32L) return producer.copyOfRange(at + 8, at + 40)
            at += 8 + length.toInt()
        }
        return null
    }

    /** One paying transaction: what it paid per whole block, in base units of `coin` (hex), and that block's capacity. */
    data class Rate(val height: Long, val coin: String, val perBlock: Double, val limits: List<Long>)

    /**
     * What each succeeding transaction of `blocks` paid the block's producer per
     * whole block, by the paying program (a coin); only producer payments to the
     * payout account count, and a block without one counts nothing. A block's
     * reward claim pays the producer too, from coins it issues: that is the
     * reward, not a fee, and is left out.
     */
    fun ratesOf(blocks: List<BlockReceipts>): List<Rate> {
        val out = ArrayList<Rate>()
        for (b in blocks) {
            val payee = payoutAccount(b.producer) ?: continue
            for (r in b.receipts) {
                if (r.status != 0) continue
                val paid = LinkedHashMap<String, BigInteger>()
                var issued = false
                for (e in r.events) {
                    val c = e.contract.hex()
                    when (val ev = Coin.decodeEvent(e.payload)) {
                        is Coin.Event.Transfer -> if (equal(ev.from, Coin.ZERO_ACCOUNT)) issued = true
                        is Coin.Event.ProducerPayment -> if (equal(ev.payee, payee)) paid[c] = (paid[c] ?: BigInteger.ZERO) + ev.amount
                        else -> {}
                    }
                }
                if (issued) continue
                val share = shareOf(r.used, b.limits)
                if (!(share > 0)) continue
                for ((c, amount) in paid) {
                    val perBlock = amount.toDouble() / share
                    if (perBlock.isFinite()) out.add(Rate(b.height, c, perBlock, b.limits))
                }
            }
        }
        return out
    }

    /** The newest [SAMPLE] rates in `coin`, newest first. */
    private fun sample(rates: List<Rate>, coinHex: String): List<Rate> = rates.filter { it.coin == coinHex }.sortedByDescending { it.height }.take(SAMPLE)

    /** The median of the newest [SAMPLE] rates in `coin`, or null when none paid in it. */
    fun medianRate(rates: List<Rate>, coinHex: String): Double? {
        val recent = sample(rates, coinHex).map { it.perBlock }.sorted()
        if (recent.isEmpty()) return null
        val mid = recent.size shr 1
        return if (recent.size % 2 == 1) recent[mid] else (recent[mid - 1] + recent[mid]) / 2
    }

    /**
     * The automatic fee in `coinHex` for an operation of kind `share`: the
     * median rate times the operation's share of a block of the capacity of
     * the newest paying transaction's, which the rates were measured against.
     */
    fun fee(rates: List<Rate>, coinHex: String, share: Share): BigInteger {
        val newest = sample(rates, coinHex).firstOrNull() ?: return feeFor(null, 0.0)
        return feeFor(medianRate(rates, coinHex), share.of(newest.limits))
    }

    /** How many times the lower quartile of recent rates the median may be before an automatic fee needs the person's word. */
    const val SPREAD = 10

    /**
     * Whether the median of the newest rates in `coinHex` is more than
     * [SPREAD] times their lower quartile: what a quarter of the recent paying
     * transactions paid at most. A few that paid far more set it then, such as
     * a miner's own paying itself, which costs it nothing.
     */
    fun lopsided(rates: List<Rate>, coinHex: String): Boolean {
        val recent = sample(rates, coinHex).map { it.perBlock }.sorted()
        val median = medianRate(rates, coinHex) ?: return false
        return median > SPREAD * recent[(recent.size - 1) / 4]
    }

    /** `v` to two significant digits, the nearest: 9,995 is 10,000 and 16,146 is 16,000. */
    private fun significant(v: Double): Double {
        val step = 10.0.pow(floor(log10(v)) - 1)
        return Math.round(v / step) * step
    }

    /**
     * The fee for an operation taking `share` of a block at `perBlock`, to two
     * significant digits, the nearest (rounding up would raise the rate a little
     * with every round), and never less than the coin's smallest unit: with no
     * recent paying transaction, that still ranks it above those that pay nothing.
     */
    fun feeFor(perBlock: Double?, share: Double): BigInteger {
        if (perBlock == null) return BigInteger.ONE
        val v = perBlock * share
        if (!v.isFinite() || !(v >= 1)) return BigInteger.ONE
        val s = significant(v)
        return if (s >= Long.MAX_VALUE.toDouble()) java.math.BigDecimal(s).toBigInteger() else BigInteger.valueOf(maxOf(1L, s.roundToLong()))
    }
}
