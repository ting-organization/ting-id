// Amounts as shown: exactly where they are confirmed or signed, shortened
// where a balance is only glanced at.
package app.tingid.chain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigInteger

class FormatTest {
    private fun units(text: String, decimals: Int = 8): BigInteger = parseAmount(text, decimals)

    @Test
    fun `shows an amount exactly, grouped, without trailing zeros`() {
        assertEquals("239 756.16479997", formatAmount(units("239756.16479997"), 8))
        assertEquals("100", formatAmount(units("100"), 8))
        assertEquals("0.00000001", formatAmount(BigInteger.ONE, 8))
        assertEquals("1 234 567", formatAmount(BigInteger.valueOf(1_234_567), 0))
    }

    @Test
    fun `shortens to six significant digits, keeping every whole unit and two decimals`() {
        assertEquals("239 756.16", shortAmount(units("239756.16479997"), 8))
        assertEquals("1 234 567 890.12", shortAmount(units("1234567890.12345678"), 8))
        assertEquals("123.456", shortAmount(units("123.45678912"), 8))
        assertEquals("1.23456", shortAmount(units("1.23456789"), 8))
        assertEquals("1.23456", shortAmount(units("1.234567891234567891", 18), 18))
        assertEquals("239 756.1", shortAmount(units("239756.10000001"), 8))
        assertEquals("100", shortAmount(units("100.00000001"), 8))
    }

    @Test
    fun `keeps six digits after the leading zeros of a small amount, so it never shows as zero`() {
        assertEquals("0.0123456", shortAmount(units("0.01234567"), 8))
        assertEquals("0.00012345", shortAmount(units("0.00012345"), 8))
        assertEquals("0.00000001", shortAmount(BigInteger.ONE, 8))
        assertEquals("0.000000000000000015", shortAmount(BigInteger.valueOf(15), 18))
        assertEquals("0", shortAmount(BigInteger.ZERO, 8))
    }

    @Test
    fun `cuts rather than rounds, so a balance never shows more than there is`() {
        assertEquals("0.999999", shortAmount(units("0.99999999"), 8))
        assertEquals("99 999.99", shortAmount(units("99999.99999999"), 8))
    }

    @Test
    fun `leaves coins with few decimals as they are`() {
        assertEquals("0.05", shortAmount(BigInteger.valueOf(5), 2))
        assertEquals("239 756.1", shortAmount(units("239756.1", 1), 1))
        assertEquals("1 234 567", shortAmount(BigInteger.valueOf(1_234_567), 0))
    }
}
