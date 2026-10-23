// The fee an operation pays: the coin, the account that pays it, the
// automatic amount and what is wrong with a typed one (state/Fees).
package app.tingid.state

import app.tingid.chain.Coin
import app.tingid.chain.FeeMarket
import app.tingid.chain.MemoryKey
import app.tingid.chain.Signer
import app.tingid.chain.filled
import app.tingid.chain.hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigInteger

class FeesTest {
    private fun coin(fill: Int, symbol: String, decimals: Int = 8) = CoinInfo(
        id = filled(fill),
        hex = filled(fill).hex(),
        config = Coin.Config(symbol.lowercase(), symbol, decimals, Coin.UNCAPPED, BigInteger.ZERO, 0, BigInteger.ZERO, 0),
        state = Coin.State(BigInteger.ZERO, BigInteger.ZERO, 0, Coin.ZERO_ACCOUNT),
    )

    private val dlr = coin(0x44, "DLR")
    private val abc = coin(0x55, "ABC", 2)
    private val spending = UserAccount(0, "Spending", filled(0x10), 0)
    private val savings = UserAccount(1, "Savings", filled(0x11), 604_800)
    private val other = UserAccount(2, "Account 2", filled(0x12), 0)
    private val accounts = listOf(spending, savings, other)

    private fun big(n: Long) = BigInteger.valueOf(n)

    /** Balances: each (account, coin) pair with what it has available. */
    private fun views(vararg held: Triple<UserAccount, CoinInfo, Long>): Map<String, List<AccountView>> =
        held.groupBy { it.second.hex }.mapValues { (_, list) -> list.map { (a, _, v) -> AccountView(a, Coin.AccountRecord(big(v), BigInteger.ZERO, 0), emptyList()) } }

    /** Recent transactions paying `perBlock` in `c`. */
    private fun rates(c: CoinInfo, perBlock: Double) = (1L..5L).map { FeeMarket.Rate(it, c.hex, perBlock) }

    private fun quote(
        acting: Acting? = null,
        views: Map<String, List<AccountView>> = views(Triple(spending, dlr, 1_000_000)),
        rates: List<FeeMarket.Rate>? = null,
        canSign: Boolean = true,
        choice: String? = null,
        typed: String? = null,
    ) = quoteFee(dlr, rates, acting, accounts, views, canSign, choice, typed)

    @Test
    fun `the fee coin is the one chosen while followed, else the chain's listed coin, else the first`() {
        val daler = coin(0x30, "DLR")
        val listed = KNOWN_COINS.entries.first()
        val known = CoinInfo(app.tingid.chain.unhex(listed.value[0].contract), listed.value[0].contract, daler.config, daler.state)
        assertSame(abc, feeCoinOf(abc.hex, listOf(dlr, abc), null))
        assertSame(known, feeCoinOf("ff".repeat(32), listOf(dlr, known), listed.key))
        assertSame(dlr, feeCoinOf(null, listOf(dlr, abc), "00".repeat(32)))
        assertNull(feeCoinOf(null, emptyList(), null))
    }

    @Test
    fun `an operation pays the smallest unit while no recent transaction paid, from Spending`() {
        val q = quote()
        assertSame(spending, q.payer?.account)
        assertEquals("0.00000001", q.amount)
        assertEquals(BigInteger.ONE, q.fee)
        assertEquals(BigInteger.ONE, q.usual)
        assertTrue(q.automatic)
        assertTrue(q.bundled)
        assertFalse(q.own)
        assertEquals("Fee 0.00000001 DLR from Spending", q.summary)
        assertNull(q.problem)
        assertNull(q.notice)
        assertEquals("Send 1 DLR · Fee 0.00000001 DLR from Spending", withFee("Send 1 DLR", source(q)))
    }

    @Test
    fun `an action of the paying account in the fee coin carries its fee, for less room`() {
        val r = rates(dlr, 1_000_000.0)
        // 104,000 to two digits is 100,000; a fee in a batch, 168,000, is 170,000.
        val own = quote(acting = Acting(dlr, spending), rates = r)
        assertTrue(own.own)
        assertFalse(own.bundled)
        assertEquals(big(100_000), own.fee)
        assertEquals("0.001", own.amount)
        val bundled = quote(acting = null, rates = r)
        assertEquals(big(170_000), bundled.fee)
        assertEquals(big(100_000), bundled.usual)
        assertEquals(big(9_900), bundled.runTip(dlr))
    }

    @Test
    fun `a delayed account never pays, Spending pays beside it`() {
        val q = quote(acting = Acting(dlr, savings), views = views(Triple(spending, dlr, 1_000_000), Triple(savings, dlr, 1_000_000)))
        assertSame(spending, q.payer?.account)
        assertFalse(q.own)
        assertTrue(q.bundled)
        assertEquals(listOf("0:${dlr.hex}", "2:${dlr.hex}"), q.options.map { it.key })
    }

    @Test
    fun `falls back to the coin the operation moves, with no amount, when no account holds the usual fee`() {
        val q = quote(acting = Acting(abc, spending), views = views(Triple(spending, dlr, 0), Triple(spending, abc, 500), Triple(other, dlr, 0)))
        assertSame(abc, q.payer?.coin)
        assertTrue(q.multiCoin)
        assertEquals("", q.amount)
        assertEquals(BigInteger.ZERO, q.fee)
        assertNull(q.summary)
        assertFalse(q.bundled)
        assertEquals("Spending has too little DLR for the fee; it can be paid in ABC instead.", q.notice)
        // A typed amount pays in that coin, inside the account's own action.
        val typed = quote(acting = Acting(abc, spending), views = views(Triple(spending, dlr, 0), Triple(spending, abc, 500), Triple(other, dlr, 0)), typed = "1.5")
        assertEquals(big(150), typed.fee)
        assertTrue(typed.own)
        assertEquals("Fee 1.5 ABC from Spending", typed.summary)
    }

    @Test
    fun `an account chosen in the fee coin with too little pays the automatic fee all the same`() {
        val v = views(Triple(spending, dlr, 0), Triple(other, dlr, 0))
        assertEquals("", quote(views = v).amount)
        val chosen = quote(views = v, choice = "2:${dlr.hex}")
        assertSame(other, chosen.payer?.account)
        assertNull(chosen.notice)
        assertEquals(BigInteger.ONE, chosen.fee)
        assertEquals("the fee needs 0.00000001 DLR; Account 2 has 0", chosen.problem)
    }

    @Test
    fun `a typed amount is checked`() {
        assertEquals("enter a fee with at most 8 decimals", quote(typed = "0.000000001").problem)
        assertEquals("enter a fee with at most 8 decimals", quote(typed = "abc").problem)
        val big = quote(typed = "1")
        assertFalse(big.automatic)
        assertEquals("the fee needs 1 DLR; Spending has 0.01", big.problem)
        assertEquals(BigInteger.ZERO, quote(typed = " ").fee)
        assertNull(quote(typed = " ").summary)
    }

    @Test
    fun `a phone that signs for no account pays none`() {
        val q = quote(canSign = false)
        assertFalse(q.canPay)
        assertEquals(BigInteger.ZERO, q.fee)
        assertNull(q.problem)
        assertEquals("no account here can pay a fee", quote(canSign = false, typed = "0.001").problem)
    }

    @Test
    fun `the payer to submit with`() {
        val signer = Signer(MemoryKey(ByteArray(32) { 1 }), null, ByteArray(0), ByteArray(0))
        assertNull(payerOf(quote(canSign = false)) { error("no signer needed") })
        val payer = payerOf(quote()) { slot ->
            assertEquals(0L, slot)
            signer
        }!!
        assertSame(signer, payer.signer)
        assertEquals(dlr.hex, payer.coinId.hex())
        assertEquals(BigInteger.ONE, payer.fee)
        try {
            payerOf(quote(typed = "1")) { signer }
            fail("a fee with a problem is not paid")
        } catch (e: IllegalStateException) {
            assertEquals("the fee needs 1 DLR; Spending has 0.01", e.message)
        }
    }

    @Test
    fun `a time-locked account pays the run's tip when it holds it beside its own fee`() {
        val view = AccountView(savings, Coin.AccountRecord(big(100), BigInteger.ZERO, 0), emptyList())
        assertEquals(big(60), runFeeOf(dlr, savings, view, big(60), big(40)))
        assertEquals(BigInteger.ZERO, runFeeOf(dlr, savings, view, big(60), big(41)))
        assertEquals(BigInteger.ZERO, runFeeOf(dlr, spending, view, big(60), BigInteger.ZERO))
        assertEquals(BigInteger.ZERO, runFeeOf(null, savings, view, big(60), BigInteger.ZERO))
        assertEquals(BigInteger.ZERO, runFeeOf(dlr, savings, null, big(60), BigInteger.ZERO))
    }

    @Test
    fun `scheduled actions are listed soonest first across coins`() {
        fun item(seq: Long, at: Long) = ScheduledItem(seq, Coin.Scheduled(at, BigInteger.ONE, BigInteger.ZERO, Coin.Effect.Burn(BigInteger.ONE)))
        val v = mapOf(
            dlr.hex to listOf(AccountView(spending, Coin.EMPTY_ACCOUNT, listOf(item(1, 300), item(2, 100)))),
            abc.hex to listOf(AccountView(savings, Coin.EMPTY_ACCOUNT, listOf(item(7, 200)))),
        )
        val all = scheduledOf(listOf(dlr, abc), v)
        assertEquals(listOf(2L, 7L, 1L), all.map { it.sequence })
        assertEquals("${abc.hex}-1-7", scheduledActionKey(all[1]))
    }

    private fun source(q: FeeQuote) = object : FeeSource {
        override val summary = q.summary
        override suspend fun resolve() = null
    }
}
