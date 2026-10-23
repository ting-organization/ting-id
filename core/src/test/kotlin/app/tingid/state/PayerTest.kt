// Who pays an operation's fee by default (state/Payer).
package app.tingid.state

import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigInteger

class PayerTest {
    private val dlr = "dlr"
    private val abc = "abc"
    private fun option(slot: Long, coin: String, available: Long?) = PayerCandidate(slot, coin, available?.let(BigInteger::valueOf))
    private val base = PayerContext(feeCoin = dlr, actingCoin = abc, actingSlot = 0, spendingSlot = 0, usual = BigInteger.TEN)

    @Test
    fun `is the acting account in the fee coin when it holds the usual fee`() {
        assertEquals(DefaultPayer(0, false), defaultPayer(listOf(option(0, dlr, 50), option(0, abc, 5)), base))
    }

    @Test
    fun `counts a balance not read yet as enough`() {
        assertEquals(DefaultPayer(1, false), defaultPayer(listOf(option(0, abc, null), option(0, dlr, null)), base))
    }

    @Test
    fun `takes Spending for a delayed or other account, before the other instant accounts`() {
        val options = listOf(option(2, dlr, 50), option(0, dlr, 50))
        assertEquals(1, defaultPayer(options, base.copy(actingSlot = null)).index)
        assertEquals(0, defaultPayer(options, base.copy(actingSlot = 2)).index)
    }

    @Test
    fun `passes over an account that holds too little`() {
        assertEquals(DefaultPayer(1, false), defaultPayer(listOf(option(0, dlr, 5), option(2, dlr, 50)), base))
    }

    @Test
    fun `falls back to the coin the operation moves when no account holds the usual fee`() {
        assertEquals(DefaultPayer(1, true), defaultPayer(listOf(option(0, dlr, 5), option(0, abc, 100)), base))
    }

    @Test
    fun `keeps the fee coin when the operation moves no coin of its own`() {
        assertEquals(DefaultPayer(0, true), defaultPayer(listOf(option(0, dlr, 5)), base.copy(actingCoin = null)))
    }

    @Test
    fun `is never short without a usual fee`() {
        assertEquals(DefaultPayer(0, false), defaultPayer(listOf(option(0, dlr, 0)), base.copy(usual = BigInteger.ZERO)))
    }

    @Test
    fun `is none without an instant account`() {
        assertEquals(DefaultPayer(-1, true), defaultPayer(emptyList(), base))
    }

    @Test
    fun `ranks the other accounts by slot, and is never short without a fee coin`() {
        val options = listOf(option(5, dlr, 50), option(3, dlr, 50), option(4, abc, 1))
        assertEquals(1, defaultPayer(options, base.copy(actingSlot = null, spendingSlot = null)).index)
        assertEquals(DefaultPayer(2, false), defaultPayer(options, base.copy(feeCoin = null)))
    }
}
