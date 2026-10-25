// What a program's call says (TCR-1.md): coins by name only when followed,
// hashes in full, and no value that can stop the screen.
package app.tingid.text

import app.tingid.chain.Coin
import app.tingid.chain.SlotValue
import app.tingid.chain.Template
import app.tingid.chain.filled
import app.tingid.chain.hex
import app.tingid.state.CoinInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class CallTextTest {
    private val daler = CoinInfo(
        filled(0x44),
        filled(0x44).hex(),
        Coin.Config("Daler", "DLR", 8, Coin.UNCAPPED, BigInteger.ZERO, 0, BigInteger.ZERO, 0),
        Coin.State(BigInteger.ZERO, BigInteger.ZERO, 0, Coin.ZERO_ACCOUNT),
    )
    private val followed = mapOf(daler.hex to daler)
    private val time: (Long) -> String = { java.time.Instant.ofEpochSecond(it).atZone(java.time.ZoneOffset.UTC).toString() }

    @Test
    fun `a coin goes by name only when the user follows it`() {
        assertEquals("25 DLR", showValue(SlotValue.Amount(daler.id, BigInteger.valueOf(2_500_000_000)), emptyMap(), followed, time))
        // Deployed again as "Daler"/"DLR", another coin still reads as its ID and smallest units.
        val lookalike = filled(0x45)
        assertEquals("2500000000 units of coin 454545…4545", showValue(SlotValue.Amount(lookalike, BigInteger.valueOf(2_500_000_000)), emptyMap(), followed, time))
        assertTrue(strange(SlotValue.Amount(lookalike, BigInteger.ONE), emptyMap(), followed))
        assertFalse(strange(SlotValue.Amount(daler.id, BigInteger.ONE), emptyMap(), followed))
        assertEquals("coin 454545…4545", showValue(SlotValue.CoinId(lookalike), emptyMap(), followed, time))
        assertEquals("7 units of coin 454545…4545", showAmount(lookalike, BigInteger.valueOf(7), followed))
    }

    @Test
    fun `a bytes32 shows in full, and a time past any date as its number`() {
        assertEquals("ab".repeat(32), showValue(SlotValue.Bytes32(ByteArray(32) { 0xab.toByte() }), emptyMap(), followed, time))
        val far = BigInteger.ONE.shiftLeft(60)
        assertEquals(far.toString(), showValue(SlotValue.Time(far), emptyMap(), followed, time))
        assertEquals("1970-01-01T00:01Z", showValue(SlotValue.Time(BigInteger.valueOf(60)), emptyMap(), followed, time))
    }

    @Test
    fun `the payment slot shows the payment as given`() {
        val t = Template.parse("buy(item:u64): Buy item {item} for {payment}")
        val parts = callSentence(t, listOf(SlotValue.Integer(BigInteger.valueOf(7))), CallPart.Value("25 DLR"), emptyMap(), followed, time)
        assertEquals("Buy item 7 for 25 DLR", callLine(parts))
    }
}
