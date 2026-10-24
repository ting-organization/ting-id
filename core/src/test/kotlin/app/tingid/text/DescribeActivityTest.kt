// History rows name the other account, show its address beside a name, and say what a scheduled action did.
package app.tingid.text

import app.tingid.chain.Coin
import app.tingid.chain.Id
import app.tingid.chain.NodeHealth
import app.tingid.chain.NodeState
import app.tingid.chain.NodeStatus
import app.tingid.chain.Offchain
import app.tingid.chain.PoolNotice
import app.tingid.chain.filled
import app.tingid.chain.hex
import app.tingid.state.ActivityItem
import app.tingid.state.CoinInfo
import app.tingid.state.FeePaid
import app.tingid.state.Watch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/** A coin of four decimals, Daler (DLR), and the accounts the tests name. */
internal object Fixture {
    val me = filled(0x11)
    val bob = filled(0x22)
    val stranger = filled(0x33)
    val contract = filled(0x44)
    val daler = CoinInfo(
        id = contract,
        hex = contract.hex(),
        config = Coin.Config("Daler", "DLR", 4, Coin.UNCAPPED, BigInteger.ZERO, 0, BigInteger.ZERO, 0),
        state = Coin.State(BigInteger.ZERO, BigInteger.ZERO, 0, Coin.ZERO_ACCOUNT),
    )
    val coins = mapOf(daler.hex to daler)
    val names: Names = mapOf(bob.hex() to "Bob")
    fun big(n: Long): BigInteger = BigInteger.valueOf(n)
}

class DescribeActivityTest {
    private val f = Fixture
    private fun sent(to: ByteArray) =
        describeActivity(ActivityItem(1, 0, "01", f.contract, coin = Coin.Event.Transfer(f.me, to, f.big(50_000))), f.coins, f.names, listOf(f.me))

    @Test
    fun `a payment to a known name says only that in its short line, the address in its detail`() {
        val d = sent(f.bob)
        assertEquals("Bob", d.party?.name)
        assertEquals("Sent", d.short)
        assertEquals("to Bob · 222222…2222", d.detail)
        assertEquals("−5 DLR", d.amount)
    }

    @Test
    fun `a move between one identity's accounts names the accounts alone`() {
        assertEquals("Spending → Savings", movedBetween("Maja · Spending", "Maja · Savings"))
        assertEquals("Maja · Spending → Erik · Savings", movedBetween("Maja · Spending", "Erik · Savings"))
        assertEquals("Erik → 33333333…333333", movedBetween("Erik", "33333333…333333"))
    }

    @Test
    fun `a payment names an unknown account by its address alone`() {
        val d = sent(f.stranger)
        assertEquals("33333333…333333", d.party?.name)
        assertEquals(false, d.party?.known)
        assertEquals("Sent", d.short)
        assertEquals("to 33333333…333333", d.detail)
    }

    @Test
    fun `a miner gets one row per day for its payments, their amounts summed`() {
        fun pay(time: Long, amount: Long, transaction: String) =
            ActivityItem(time, time, transaction, f.contract, coin = Coin.Event.ProducerPayment(f.me, f.big(amount)))
        val day = { time: Long -> (time / 86_400).toString() }
        val rows = groupMinerPayments(listOf(pay(86_400 * 2 + 50, 30_000, "c"), pay(86_400 * 2 + 10, 20_000, "b"), pay(86_400 + 10, 10_000, "a")), day)
        assertEquals(2, rows.size)
        assertEquals(2, rows[0].count)
        val d = describeActivity(rows[0], f.coins, f.names, listOf(f.me))
        assertEquals("Mining fees", d.title)
        assertEquals("+5 DLR", d.amount)
        assertTrue(d.detail!!.contains("2 payments"))
        assertNull(rows[1].count)
    }

    @Test
    fun `a scheduled action that ran says what it did`() {
        val d = describeActivity(
            ActivityItem(9, 0, "09", f.contract, coin = Coin.Event.Executed(f.me, 3), plan = Coin.Effect.Transfer(f.bob, f.big(50_000))),
            f.coins,
            f.names,
            listOf(f.me),
        )
        assertEquals("Send 5 DLR to Bob", d.detail)
    }

    @Test
    fun `a fee its transaction paid joins the detail`() {
        val d = describeActivity(
            ActivityItem(1, 0, "01", f.contract, coin = Coin.Event.Transfer(f.me, f.bob, f.big(50_000)), fees = listOf(FeePaid(f.contract, f.big(1)))),
            f.coins,
            f.names,
            listOf(f.me),
        )
        assertEquals("0.0001 DLR", d.fee)
        assertEquals("to Bob · 222222…2222 · fee 0.0001 DLR", d.detail)
    }

    @Test
    fun `identity events read as the registry has them`() {
        val proposed = Id.Event.ChangeProposed(f.me, 4, 1_000 + 3 * 86_400, listOf(Id.Operation.SetSlot(1, 604_800), Id.Operation.RemoveKey(2)))
        val d = describeActivity(ActivityItem(1, 0, "01", f.contract, identity = proposed), f.coins, f.names, listOf(f.me), advanced = false, now = 1_000)
        assertEquals("Change #4 proposed", d.title)
        assertEquals("Savings: 7-day lock; Remove Device 3 — applies in 3 d", d.detail)
        // A slot number past any Int is never taken for one of the first two.
        assertEquals("Account 4294967296: instant", describeOperation(Id.Operation.SetSlot(1L shl 32, 0), f.names))
    }

    @Test
    fun `spans of time read in words, a fraction of an hour or day too`() {
        assertEquals("none", relativeDuration(0))
        assertEquals("45 minutes", relativeDuration(2_700))
        assertEquals("1 hour", relativeDuration(3_600))
        // The TypeScript threw here, counting 1.5 as a whole number.
        assertEquals("1.5 hours", relativeDuration(5_400))
        assertEquals("1 day", relativeDuration(90_000))
        assertEquals("2.5 days", relativeDuration(216_000))
        assertEquals("12-hour lock", lockOf(43_200))
        assertEquals("1.5-day lock", lockOf(129_600))
        assertEquals("Runs in 7 days, once the account's 7-day lock has passed", runsIn(0, 604_800))
        assertEquals("Runs in 7 days, the later of the chosen 1 hour and the account's 7-day lock", runsIn(3_600, 604_800))
    }

    @Test
    fun `messages become sentences, and statuses words`() {
        assertEquals("The node refused it.", sentence("  the node refused it "))
        assertEquals("Done!", sentence("done!"))
        assertEquals("", sentence(" "))
        assertEquals("it needed more than its budget", statusWords("Growth"))
        assertEquals("it failed (Odd)", statusWords("Odd"))
        assertEquals("1 guardian", count(1, "guardian"))
        assertEquals("3 guardians", count(3, "guardian"))
    }

    @Test
    fun `keys read as fingerprints`() {
        assertTrue(Regex("^[0-9a-f]{4} [0-9a-f]{4} [0-9a-f]{4} [0-9a-f]{4}$").matches(deviceFingerprint(filled(4, 65))))
        assertEquals("1 key: 04040404…0404", describeKeys(listOf(Id.Key(filled(1), filled(4, 65)))))
    }

    @Test
    fun `a site's request reads in the app's words`() {
        val time = { at: Long -> "T$at" }
        val signIn = describeSignRequest(Offchain.Request.SignIn(1_000 + 3_600, null), "Anna", now = 1_000, time = time)
        assertEquals("Sign in as Anna", signIn.headline)
        assertEquals("Signed in until T4600 (in 1 h).", signIn.detail)
        assertNull(signIn.warning)
        val grant = describeSignRequest(Offchain.Request.Grant(1_000 + 100 * 86_400, listOf("read"), filled(4, 65)), "Anna", now = 1_000, time = time)
        assertEquals("Until T8641000 (in 100 d), through a key the site holds.", grant.detail)
        assertEquals("This lasts 100 days.", grant.warning)
        assertEquals("already over", describeSignRequest(Offchain.Request.SignIn(900, null), "Anna", now = 1_000, time = time).detail!!.removePrefix("Signed in until ").removeSuffix("."))
        assertEquals("example.com", siteName("https://example.com"))
    }

    @Test
    fun `submissions and nodes read in words`() {
        assertEquals("Waiting for a block · sent 1 min ago", describeWatch(Watch.Waiting, 940, now = 1_000).detail)
        assertEquals(
            "Held by the node until T2000 · the node’s last try: “Revert”",
            describeWatch(Watch.Scheduled(2_000, "Revert"), 940, now = 1_000, time = { "T$it" }).detail,
        )
        assertEquals("Can't reach the node. Showing what it last reported, at block 7.", nodeNotice(7, "down", null))
        assertEquals("Your nodes follow different chains, and Ting ID can't tell which is right. Check them in Settings → Nodes.", nodeNotice(7, null, PoolNotice.Split))
        assertNull(nodeNotice(7, null, null))
        val status = NodeStatus(filled(1), 90, emptyList(), "00", BigInteger.ONE, 0, "")
        assertEquals(NodeLine("Catching up · block 90", NodeTone.Muted), describeNode(NodeHealth("u", NodeState.Ok, status), 100))
        assertEquals(NodeLine("In use · block 90", NodeTone.Success), describeNode(NodeHealth("u", NodeState.Ok, status, best = true), 100))
    }
}
