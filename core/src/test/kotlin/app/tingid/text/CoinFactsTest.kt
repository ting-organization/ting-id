// Telling an original coin from its copies (CoinFacts.kt): the same ticker
// and the same or a very similar name, the listed or earliest coin first.
package app.tingid.text

import app.tingid.chain.Coin
import app.tingid.chain.filled
import app.tingid.chain.hex
import app.tingid.chain.unhex
import app.tingid.state.CoinInfo
import app.tingid.state.UserAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class CoinFactsTest {
    private var next = 0

    private fun coinInfo(name: String, symbol: String, deployHeight: Long, minter: ByteArray = Coin.ZERO_ACCOUNT, initialReward: Long = 0): CoinInfo {
        val hex = (++next).toString(16).padStart(64, '0')
        return CoinInfo(
            id = unhex(hex),
            hex = hex,
            config = Coin.Config(name, symbol, 8, Coin.UNCAPPED, BigInteger.valueOf(initialReward), 0, BigInteger.ZERO, deployHeight),
            state = Coin.State(BigInteger.ZERO, BigInteger.ZERO, 0, minter),
        )
    }

    @Test
    fun `read names and tickers as people see them`() {
        assertEquals("dlr", reading(" D-L R "))
        assertEquals(reading("Dalor"), reading("DAL0R"))
        assertEquals("daler", reading("Dalér"))
    }

    @Test
    fun `count close names as similar, other names not`() {
        assertTrue(similarNames("Daler", "daler"))
        assertTrue(similarNames("Daler", "Dalers"))
        assertTrue(similarNames("Daler", "Daler Token"))
        assertTrue(similarNames("Daler", "Dalor"))
        assertFalse(similarNames("Daler", "Garden"))
        assertTrue(similarNames("Ting Daler", "Daler"))
        assertFalse(similarNames("Dollar", "Daler"))
        assertFalse(similarNames("Apple", "Maple"))
        assertFalse(similarNames("A", "Garden"))
    }

    @Test
    fun `flag a later coin with the same ticker and a similar name`() {
        val daler = coinInfo("Daler", "DLR", 51)
        val copy = coinInfo("Daler Token", "dlr", 90)
        val lookalike = coinInfo("Da1er", "D1R", 95)
        val all = listOf(daler, copy, lookalike)
        assertNull(originalOf(daler, all, emptyList()))
        assertSame(daler, originalOf(copy, all, emptyList()))
        assertSame(daler, originalOf(lookalike, all, emptyList()))
    }

    @Test
    fun `leave a coin alone when only the ticker or only the name matches`() {
        val daler = coinInfo("Daler", "DLR", 51)
        val sameTicker = coinInfo("Delivery Rewards", "DLR", 60)
        val sameName = coinInfo("Daler", "DAL", 61)
        val all = listOf(daler, sameTicker, sameName)
        assertNull(originalOf(sameTicker, all, emptyList()))
        assertNull(originalOf(sameName, all, emptyList()))
    }

    @Test
    fun `take the listed coin as the original, even when a copy came first`() {
        val early = coinInfo("Daler", "DLR", 10)
        val listed = coinInfo("Daler", "DLR", 51)
        val all = listOf(early, listed)
        assertNull(originalOf(listed, all, listOf(listed.hex)))
        assertSame(listed, originalOf(early, all, listOf(listed.hex)))
    }

    @Test
    fun `take the first deployed within one block`() {
        val a = coinInfo("Garden", "GRDN", 5)
        val b = coinInfo("Garden", "GRDN", 5)
        assertSame(a, originalOf(b, listOf(a, b), emptyList()))
        assertNull(originalOf(a, listOf(a, b), emptyList()))
    }

    @Test
    fun `say how a coin is issued and who mints it`() {
        val me = filled(0x11)
        val fixed = coinInfo("Fixed", "FIX", 1)
        val minted = coinInfo("Minted", "MNT", 1, minter = me)
        val both = coinInfo("Both", "BTH", 1, minter = filled(0x22), initialReward = 5)
        assertEquals("fixed supply", supplyModel(fixed))
        assertEquals("minted", supplyModel(minted))
        assertEquals("mined and minted", supplyModel(both))
        val accounts = listOf(UserAccount(1, "Savings", me, 0))
        assertEquals("no one", minterName(fixed, accounts, emptyMap()))
        assertEquals("you · Savings", minterName(minted, accounts, emptyMap()))
        assertEquals("222222…2222", minterName(both, accounts, emptyMap()))
        assertEquals("Bob", minterName(both, accounts, mapOf(filled(0x22).hex() to "Bob")))
        assertFalse(capped(fixed))
    }
}
