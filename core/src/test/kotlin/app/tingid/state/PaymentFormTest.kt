// What the Send screen checks of a payment, and whom it offers to pay
// (state/PaymentForm).
package app.tingid.state

import app.tingid.chain.Coin
import app.tingid.chain.Contracts
import app.tingid.chain.Id
import app.tingid.chain.Scheme
import app.tingid.chain.filled
import app.tingid.chain.hex
import app.tingid.chain.policyAccount
import app.tingid.chain.unhex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class PaymentFormTest {
    private fun coin(fill: Int, symbol: String, decimals: Int = 2) = CoinInfo(
        id = filled(fill),
        hex = filled(fill).hex(),
        config = Coin.Config(symbol.lowercase(), symbol, decimals, Coin.UNCAPPED, BigInteger.ZERO, 0, BigInteger.ZERO, 0),
        state = Coin.State(BigInteger.ZERO, BigInteger.ZERO, 0, Coin.ZERO_ACCOUNT),
    )

    private val dlr = coin(0x44, "DLR")
    private val abc = coin(0x55, "ABC")
    private val spending = UserAccount(0, "Spending", filled(0x10), 0)
    private val savings = UserAccount(1, "Savings", filled(0x11), 604_800)
    private val bob = filled(0x77).hex()
    private val now = 1_800_000_000L

    private fun big(n: Long) = BigInteger.valueOf(n)

    private fun view(a: UserAccount, available: Long, locked: Long = 0) = AccountView(a, Coin.AccountRecord(big(available + locked), big(locked), 0), emptyList())

    private fun fee(
        fee: Long = 0,
        own: Boolean = false,
        problem: String? = null,
        usual: Long = 0,
        feeCoin: CoinInfo? = dlr,
        options: List<PayerOption> = emptyList(),
        payer: PayerOption? = null,
        runTip: Long = 0,
    ) = PaymentFee(big(fee), own, problem, big(usual), feeCoin, options, payer, big(runTip))

    private fun input(
        amount: String = "10",
        to: String = bob,
        coin: CoinInfo? = dlr,
        from: AccountView? = view(spending, 10_000),
        outgoing: Long = 0,
        fee: PaymentFee = fee(),
        later: Boolean = false,
        at: Long = now + 86_400,
        toIsIdentity: Boolean = false,
        codeCoin: String? = null,
    ) = PaymentInput(amount, to, coin, from, big(outgoing), fee, later, at, now, toIsIdentity, codeCoin)

    @Test
    fun `a payment within what the account has is ready`() {
        val c = checkPayment(input())
        assertTrue(c.ready)
        assertEquals(big(1000), c.parsed)
        assertEquals(big(1000), c.needed)
        assertEquals(emptyList<String>(), c.problems)
        assertFalse(c.willSchedule)
    }

    @Test
    fun `nothing typed is no problem but not ready`() {
        val c = checkPayment(input(amount = "", to = ""))
        assertEquals(BigInteger.ZERO, c.parsed)
        assertEquals(emptyList<String>(), c.problems)
        assertFalse(c.ready)
    }

    @Test
    fun `the own fee and payments on their way count against what is available`() {
        // 100.00 held, 30.00 on its way: 70.00 left, of which the fee takes 0.05.
        val c = checkPayment(input(amount = "69.96", from = view(spending, 10_000), outgoing = 3_000, fee = fee(fee = 5, own = true)))
        assertEquals(big(7_000), c.availableNow)
        assertEquals(big(5), c.ownFee)
        assertEquals(big(7_001), c.needed)
        assertEquals(listOf("only 70 DLR available"), c.problems)
        assertEquals(big(6_995), c.maxAmount)
        assertTrue(checkPayment(input(amount = "69.95", outgoing = 3_000, fee = fee(fee = 5, own = true))).ready)
    }

    @Test
    fun `a fee paid from another account does not leave the sending one`() {
        val c = checkPayment(input(amount = "100", fee = fee(fee = 5, own = false)))
        assertEquals(big(10_000), c.needed)
        assertTrue(c.ready)
    }

    @Test
    fun `a scheduled payment reserves its run fee, and a time-locked account always schedules`() {
        val later = checkPayment(input(amount = "1", later = true, fee = fee(runTip = 7)))
        assertTrue(later.willSchedule)
        assertEquals(big(7), later.runFee)
        assertEquals(big(107), later.needed)
        val locked = checkPayment(input(amount = "1", from = view(savings, 10_000), fee = fee(runTip = 7)))
        assertTrue(locked.willSchedule)
        assertEquals(604_800L, locked.lock)
        assertEquals(big(7), locked.runFee)
        assertEquals(BigInteger.ZERO, checkPayment(input(amount = "1", fee = fee(runTip = 7))).runFee)
    }

    @Test
    fun `a later time must be ahead and within a year`() {
        assertEquals(listOf("choose a time in the future"), checkPayment(input(later = true, at = now + 30)).problems)
        assertEquals(listOf("choose a time within a year"), checkPayment(input(later = true, at = now + When.LATEST + 1)).problems)
        assertTrue(checkPayment(input(later = false, at = now)).ready)
    }

    @Test
    fun `recipients nobody can spend from, IDs and malformed ones are refused`() {
        assertEquals(listOf("the recipient must be a 64-digit hex account"), checkPayment(input(to = "abc")).problems)
        assertEquals(listOf("nobody can spend from that account: check the recipient"), checkPayment(input(to = Coin.ZERO_ACCOUNT.hex())).problems)
        assertEquals(listOf("nobody can spend from that account: check the recipient"), checkPayment(input(to = Coin.STATE_KEY.hex())).problems)
        assertEquals(listOf("that is an identity ID, not an account: send to its Spending account"), checkPayment(input(toIsIdentity = true)).problems)
    }

    @Test
    fun `amounts that are none or nothing are refused`() {
        assertEquals(listOf("enter an amount like 12.5"), checkPayment(input(amount = "1.2.3")).problems)
        assertEquals(listOf("at most 2 decimal places"), checkPayment(input(amount = "1.234")).problems)
        assertEquals(listOf("amount must be above zero"), checkPayment(input(amount = "0")).problems)
        assertNull(checkPayment(input(amount = "x")).parsed)
    }

    @Test
    fun `without a coin or an account it says so`() {
        val c = checkPayment(input(coin = null, from = null))
        assertEquals(listOf("follow a coin in Settings", "no account to send from"), c.problems)
        assertFalse(c.ready)
    }

    @Test
    fun `a fee problem stops the payment`() {
        assertEquals(listOf("no account here can pay a fee"), checkPayment(input(fee = fee(problem = "no account here can pay a fee"))).problems)
    }

    @Test
    fun `a pay link's coin must be the one sent`() {
        val c = checkPayment(input(codeCoin = abc.hex))
        assertTrue(c.coinMismatch)
        assertFalse(c.ready)
        assertTrue(checkPayment(input(codeCoin = dlr.hex)).ready)
    }

    @Test
    fun `a dot and three figures says what it reads as`() {
        val three = coin(0x66, "TRI", 3)
        assertEquals("Read as 1.5 TRI: the dot marks decimals.", checkPayment(input(amount = "1.500", coin = three)).dotCaution)
        assertNull(checkPayment(input(amount = "1.50", coin = three)).dotCaution)
        assertNull(checkPayment(input(amount = "1500", coin = three)).dotCaution)
    }

    @Test
    fun `little left in the fee coin warns, once the payment is ready`() {
        val options = listOf(PayerOption(spending, dlr, big(10_000)))
        // Sending 99.00 DLR from Spending leaves 1.00, below three usual fees of 0.50.
        val warned = checkPayment(input(amount = "99", fee = fee(usual = 50, options = options)))
        assertEquals("After this, only 1 DLR is left for fees. Each later payment or change pays one, about 0.5 DLR, so keep a little DLR in Spending.", warned.feeWarning)
        val all = checkPayment(input(amount = "100", fee = fee(usual = 50, options = options)))
        assertEquals("After this, nothing is left for fees. Each later payment or change pays one, about 0.5 DLR, so keep a little DLR in Spending.", all.feeWarning)
        assertNull(checkPayment(input(amount = "10", fee = fee(usual = 50, options = options))).feeWarning)
        // Not ready: no warning yet.
        assertNull(checkPayment(input(amount = "99", to = "", fee = fee(usual = 50, options = options))).feeWarning)
    }

    @Test
    fun `what is left for fees counts a fee paid beside the payment, and nothing while balances load`() {
        val payer = PayerOption(spending, dlr, big(1_000))
        val i = input(coin = abc, from = view(spending, 10_000), fee = fee(fee = 20, own = false, options = listOf(payer), payer = payer))
        // Sending another coin: only the fee leaves the fee coin.
        assertEquals(big(980), feesLeft(i, big(1_000)))
        assertNull(feesLeft(input(fee = fee(options = listOf(PayerOption(spending, dlr, null)))), big(1)))
        assertNull(feesLeft(input(fee = fee(options = emptyList())), big(1)))
    }

    @Test
    fun `recipients are the other accounts of this phone's users and the contacts`() {
        val registry = filled(0x20)
        val id = filled(0x21)
        val anna = User("u1", "Anna", "#111111", UserKind.Identity, identityId = id.hex(), registry = registry.hex())
        val keyMeta = KeyMeta("k1", "key", Scheme.Secp256k1, "04" + "ab".repeat(64), 0)
        val ben = User("u2", "Ben", "#222222", UserKind.Key, keyId = "k1")
        val contact = Contact("c1", "Carl", "cc".repeat(32))
        val c = Contracts.canonical(filled(0x01))
        val list = recipients(listOf(anna, ben), listOf(keyMeta), listOf(contact), c, activeUserId = "u1", fromSlot = 0)
        assertEquals(listOf("Anna · Savings", "Ben", "Carl"), list.map { it.label })
        assertEquals(listOf("7-day lock", "simple account", "contact"), list.map { it.sub })
        assertEquals(Id.account(registry, id, 1).hex(), list[0].account)
        assertEquals(policyAccount(c.policyFor(Scheme.Secp256k1), unhex(keyMeta.pub)).hex(), list[1].account)
        assertEquals(CONTACT_COLOR, list[2].color)
        // Sending as Ben: Anna's Spending is offered too, and Ben is not.
        val asBen = recipients(listOf(anna, ben), listOf(keyMeta), emptyList(), c, activeUserId = "u2", fromSlot = 0)
        assertEquals(listOf("Anna · Spending", "Anna · Savings"), asBen.map { it.label })
        assertEquals("instant", asBen[0].sub)
        // Without a chain, key users have no account to show.
        assertEquals(listOf("Anna · Savings"), recipients(listOf(anna, ben), listOf(keyMeta), emptyList(), null, "u1", 0).map { it.label })
    }
}
