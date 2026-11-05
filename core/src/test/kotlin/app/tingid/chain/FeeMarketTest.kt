// The automatic fee: what recent transactions paid per share of a block,
// times the share an operation takes.
package app.tingid.chain

import app.tingid.chain.FeeMarket.SAMPLE
import app.tingid.chain.FeeMarket.Share
import app.tingid.chain.FeeMarket.fee
import app.tingid.chain.FeeMarket.feeFor
import app.tingid.chain.FeeMarket.lopsided
import app.tingid.chain.FeeMarket.medianRate
import app.tingid.chain.FeeMarket.payoutAccount
import app.tingid.chain.FeeMarket.ratesOf
import app.tingid.chain.FeeMarket.shareOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import kotlin.math.roundToLong

class FeeMarketTest {
    private val limits = listOf(150_000_000L, 1024, 262_144, 131_072)
    private val miner = filled(0x4d)
    private val other = filled(0x0e)
    private val daler = filled(0xd1)
    private val otherCoin = filled(0xc2)

    /** Producer data naming `account` as the payout account, as ting-miner writes it. */
    private fun producerData(account: ByteArray) = concat(utf8("OCP1"), Writer().u32(1).u32(32).put(account).finish())

    private fun payment(contract: ByteArray, payee: ByteArray, amount: Long) =
        ReceiptEvent(contract, Writer().put(Coin.PRODUCER_PAYMENT_TOPIC).put(payee).u128(BigInteger.valueOf(amount)).finish())

    /** A payment that took a tenth of the block's compute and paid `fee`. */
    private fun paidTx(fee: Long, payee: ByteArray = miner, contract: ByteArray = daler, status: Int = 0) =
        BlockReceipt(status, ByteArray(0), listOf(15_000_000L, 20, 829, 0), listOf(payment(contract, payee, fee)))

    private fun block(height: Long, receipts: List<BlockReceipt>, producer: ByteArray = producerData(miner), limits: List<Long> = this.limits) =
        BlockReceipts("", height, producer, limits, receipts)

    private fun close(expected: Double, actual: Double?) = assertEquals(expected, actual!!, 0.005)

    // ---- The share of a block ---------------------------------------------------

    @Test
    fun `the share is the largest fraction of any capacity`() {
        close(0.1, shareOf(listOf(15_000_000L, 20, 829, 0), limits))
        close(0.5, shareOf(listOf(1_000_000L, 512, 0, 0), limits))
    }

    // ---- The payout account in producer data ------------------------------------

    @Test
    fun `the payout account is the first 32-byte entry of type 1 after OCP1`() {
        assertEquals(miner.hex(), payoutAccount(producerData(miner))!!.hex())
        val two = concat(utf8("OCP1"), Writer().u32(7).u32(2).put(ByteArray(2)).u32(1).u32(32).put(other).finish())
        assertEquals(other.hex(), payoutAccount(two)!!.hex())
    }

    @Test
    fun `there is no payout account without the prefix or with a cut entry`() {
        assertNull(payoutAccount(ByteArray(44)))
        assertNull(payoutAccount(producerData(miner).copyOfRange(0, 30)))
    }

    // ---- Recent rates -----------------------------------------------------------

    @Test
    fun `rates count what paying transactions gave the payout account, per whole block`() {
        val rates = ratesOf(listOf(block(10, listOf(paidTx(1), paidTx(3)))))
        assertEquals(listOf(daler.hex() to 10L, daler.hex() to 30L), rates.map { it.coin to it.perBlock.roundToLong() })
    }

    @Test
    fun `rates leave out payments to others, failed transactions and blocks without a payout account`() {
        assertEquals(emptyList<FeeMarket.Rate>(), ratesOf(listOf(block(10, listOf(paidTx(5, other), paidTx(5, status = 1))))))
        assertEquals(emptyList<FeeMarket.Rate>(), ratesOf(listOf(block(10, listOf(paidTx(5)), producer = ByteArray(0)))))
    }

    @Test
    fun `rates take the median of the newest, per coin`() {
        val blocks = listOf(block(1, listOf(paidTx(100)))) +
            (0 until FeeMarket.SAMPLE).map { i -> block(2L + i, listOf(paidTx(1L + i % 3))) } +
            block(99, listOf(paidTx(9, miner, otherCoin)))
        val rates = ratesOf(blocks)
        // The oldest, 100 per tenth of a block, has fallen out of the sample.
        close(20.0, medianRate(rates, daler.hex()))
        close(90.0, medianRate(rates, otherCoin.hex()))
        assertNull(medianRate(rates, filled(0).hex()))
    }

    // ---- The fee of an operation ------------------------------------------------

    @Test
    fun `the fee is the rate times its share, to two significant digits`() {
        // As on the network: payments paid 0.0001 DLR (10,000 units) for 15.6M of 150M compute.
        val rate = 10_000 / (15_600_000.0 / 150_000_000)
        assertEquals(BigInteger.valueOf(10_000), feeFor(rate, Share.Own.of(limits)))
        assertEquals(BigInteger.valueOf(16_000), feeFor(rate, Share.Bundled.of(limits)))
        // A run's share is its storage: 16 of the block's 1,024.
        assertEquals(BigInteger.valueOf(1_500), feeFor(rate, Share.Run.of(limits)))
        assertEquals(BigInteger.valueOf(2), feeFor(10.0, Share.Bundled.of(limits)))
    }

    @Test
    fun `the share of an operation is its largest fraction of the block's capacity, as a paying transaction's`() {
        val production = listOf(450_000_000L, 2048, 262_144, 131_072)
        close(15_600_000.0 / 450_000_000, Share.Own.of(production))
        close(25_200_000.0 / 450_000_000, Share.Bundled.of(production))
        assertEquals(16.0 / 2048, Share.Run.of(production), 1e-9)
        assertEquals(shareOf(Share.Own.used.toList(), production), Share.Own.of(production), 0.0)
    }

    @Test
    fun `transactions paying the automatic fee leave it where it was, whatever the block's capacity`() {
        for (capacity in listOf(limits, listOf(450_000_000L, 2048, 262_144, 131_072))) {
            fun paying(height: Long, fee: Long) = block(height, listOf(BlockReceipt(0, ByteArray(0), Share.Own.used.toList(), listOf(payment(daler, miner, fee)))), limits = capacity)
            val blocks = arrayListOf(paying(1, 1_000))
            repeat(5) {
                val fee = fee(ratesOf(blocks), daler.hex(), Share.Own)
                assertEquals(BigInteger.valueOf(1_000), fee)
                // The whole sample turns over, each paying the automatic fee.
                repeat(SAMPLE) { blocks.add(paying(blocks.size + 1L, fee.toLong())) }
            }
        }
    }

    @Test
    fun `the median is lopsided when it is more than ten times what a quarter of the sample paid at most`() {
        val even = (1L..50L).map { block(it, listOf(paidTx(1_000))) }
        assertFalse(lopsided(ratesOf(even), daler.hex()))
        // Twelve of fifty paid far less: under a quarter.
        val few = (1L..12L).map { block(it, listOf(paidTx(1))) } + (13L..50L).map { block(it, listOf(paidTx(1_000))) }
        assertFalse(lopsided(ratesOf(few), daler.hex()))
        // A miner's own paying itself, 26 of 50.
        val own = (1L..24L).map { block(it, listOf(paidTx(1_000))) } + (25L..50L).map { block(it, listOf(paidTx(1_000_000))) }
        assertTrue(lopsided(ratesOf(own), daler.hex()))
        assertFalse(lopsided(emptyList(), daler.hex()))
    }

    @Test
    fun `the fee pays what the last ones paid, so it does not creep up`() {
        for (start in listOf(1L, 7L, 10_000L, 16_000L)) {
            var fee = BigInteger.valueOf(start)
            repeat(10) { fee = feeFor(fee.toDouble() / Share.Own.of(limits), Share.Own.of(limits)) }
            assertEquals(BigInteger.valueOf(start), fee)
        }
    }

    @Test
    fun `the fee takes no number from a node on trust`() {
        // The largest limit a node can name in a Long, where JavaScript had Infinity.
        close(20.0 / 1024, shareOf(listOf(15_000_000L, 20, 829, 0), listOf(Long.MAX_VALUE, 1024, 262_144, 131_072)))
        assertEquals(emptyList<FeeMarket.Rate>(), ratesOf(listOf(block(10, listOf(paidTx(1)), limits = listOf(0L, 0, 0, 0)))))
        assertEquals(BigInteger.ONE, feeFor(Double.POSITIVE_INFINITY, Share.Own.of(limits)))
    }

    @Test
    fun `negative counts and missing limits count nothing`() {
        close(20.0 / 1024, shareOf(listOf(-15_000_000L, 20), listOf(-1L, 1024)))
        close(0.0, shareOf(listOf(15_000_000L), emptyList()))
        // Limits past the used numbers listed count no use.
        close(0.1, shareOf(listOf(15_000_000L), limits))
        assertEquals(BigInteger.ONE, feeFor(Double.NaN, Share.Own.of(limits)))
        assertEquals(BigInteger.ONE, feeFor(-5.0, Share.Own.of(limits)))
    }

    @Test
    fun `the fee is the smallest unit with no recent paying transaction, or a rate that rounds to nothing`() {
        assertEquals(BigInteger.ONE, feeFor(null, Share.Own.of(limits)))
        assertEquals(BigInteger.ONE, feeFor(10.0, Share.Run.of(limits)))
    }

    @Test
    fun `a fee too large for a Long is not cut to one`() {
        val fee = feeFor(1.2e22 / Share.Own.of(limits), Share.Own.of(limits))
        assertTrue(fee > BigInteger.valueOf(Long.MAX_VALUE))
        assertEquals(1.2e22, fee.toDouble(), 1e8)
    }

    // ---- A block reward claim ---------------------------------------------------

    @Test
    fun `a block reward claim is no fee - it pays the producer from coins it issues`() {
        fun transfer(from: ByteArray, to: ByteArray, amount: Long) =
            ReceiptEvent(daler, Writer().put(Coin.TRANSFER_TOPIC).put(from).put(to).u128(BigInteger.valueOf(amount)).finish())
        val claim = BlockReceipt(0, ByteArray(0), listOf(1_422_091L, 9, 432, 0), listOf(transfer(Coin.ZERO_ACCOUNT, miner, 1_000_000), payment(daler, miner, 1_000_000)))
        assertEquals(listOf(10L), ratesOf(listOf(block(10, listOf(claim, paidTx(1))))).map { it.perBlock.roundToLong() })
    }
}
