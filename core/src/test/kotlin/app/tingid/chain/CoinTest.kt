// A coin's deployment input and its rules (wiki: programs/coin, deployment).
// The production network's Daler is the vector: its options, deployed with the
// canonical budget, give the ContractId the chain shows for it.
package app.tingid.chain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class CoinTest {
    private val production = unhex("15d6549cfacd816f835c632b4c67308d1c63e642f4cbf4049368430670705107")
    private val unit = BigInteger.valueOf(100_000_000)
    private fun big(n: Long): BigInteger = BigInteger.valueOf(n)

    /** Daler's options, with any changed. */
    private fun daler(
        name: String = "Daler",
        symbol: String = "DLR",
        decimals: Int = 8,
        maxSupply: BigInteger = big(210_000_000) * unit,
        initialReward: BigInteger = big(100) * unit,
        tailReward: BigInteger = BigInteger.ZERO,
        minter: ByteArray = Coin.ZERO_ACCOUNT,
        allocations: List<Coin.Allocation> = emptyList(),
    ) = Coin.Options(name, symbol, decimals, maxSupply, initialReward, 1_050_000, tailReward, minter, allocations)

    private fun le(v: BigInteger, n: Int): ByteArray = ByteArray(n) { i -> v.shiftRight(8 * i).and(big(0xff)).toByte() }

    private fun problem(o: Coin.Options): String = Coin.deployProblem(o) ?: "none"

    @Test
    fun `reproduces Daler on the production network`() {
        val tx = encodeDeploy(Contracts.code(ContractName.Coin), Coin.encodeDeployInput(daler()), Budgets.coinDeploy(0))
        assertEquals("305378d17f6af84a563d0f8a8455084948aff7330b62b4f2f98a44b82ba113c4", contractId(production, tx).hex())
    }

    @Test
    fun `lays the input out as the wiki says`() {
        val minter = filled(7)
        val to = filled(9)
        val bytes = Coin.encodeDeployInput(daler(name = "A", symbol = "B", decimals = 2, minter = minter, allocations = listOf(Coin.Allocation(to, big(5)))))
        val expected = "01" + // version
            "01000000" + "41" + // Bytes("A")
            "01000000" + "42" + // Bytes("B")
            "02" + // decimals
            le(big(210_000_000) * unit, 16).hex() +
            le(big(100) * unit, 16).hex() +
            le(big(1_050_000), 8).hex() +
            le(BigInteger.ZERO, 16).hex() +
            minter.hex() +
            le(BigInteger.ONE, 8).hex() +
            to.hex() +
            le(big(5), 16).hex()
        assertEquals(expected, bytes.hex())
    }

    @Test
    fun `finds what the coin would refuse`() {
        assertNull(Coin.deployProblem(daler()))
        assertTrue(problem(daler(name = "")).contains("name"))
        assertTrue(problem(daler(symbol = "")).contains("symbol"))
        // A right-to-left override would make the symbol read as another coin's.
        assertTrue(problem(daler(symbol = "‮RLD")).contains("invisible or direction"))
        assertEquals("D�LR�", Coin.visibleLabel("D​LR‮"))
        assertEquals("Daler · Ø", Coin.visibleLabel("Daler · Ø"))
        assertTrue(problem(daler(tailReward = big(100) * unit + BigInteger.ONE)).contains("tail"))
        assertTrue(problem(daler(minter = Coin.STATE_KEY)).contains("minter"))
        assertTrue(problem(daler(allocations = listOf(Coin.Allocation(Coin.CONFIG_KEY, BigInteger.ONE)))).contains("reserved"))
        assertTrue(problem(daler(maxSupply = big(10), allocations = listOf(Coin.Allocation(filled(3), big(11))))).contains("cap"))
        // An account named twice receives both amounts, so both count.
        val twice = Coin.Allocation(filled(3), big(6))
        assertTrue(problem(daler(maxSupply = big(11), allocations = listOf(twice, twice))).contains("cap"))
    }

    @Test
    fun `computes the block reward of each era`() {
        fun config(tailReward: BigInteger = BigInteger.ZERO) =
            Coin.Config("Daler", "DLR", 8, big(210_000_000) * unit, big(100) * unit, 1_050_000, tailReward, 443)
        val c = config()
        assertEquals(BigInteger.ZERO, Coin.blockReward(c, BigInteger.ZERO, 443))
        assertEquals(big(100) * unit, Coin.blockReward(c, BigInteger.ZERO, 444))
        assertEquals(big(100) * unit, Coin.blockReward(c, BigInteger.ZERO, 443 + 1_050_000))
        assertEquals(big(50) * unit, Coin.blockReward(c, BigInteger.ZERO, 444 + 1_050_000))
        assertEquals(big(70) * unit, Coin.blockReward(config(tailReward = big(70) * unit), BigInteger.ZERO, 444 + 1_050_000))
        // Never past the cap.
        assertEquals(big(3), Coin.blockReward(c, c.maxSupply - big(3), 444))
    }
}
