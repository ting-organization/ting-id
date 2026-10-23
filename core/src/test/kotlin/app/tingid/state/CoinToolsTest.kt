// The coin tools (state/CoinTools): the browser's order, the forms to create,
// mint and move minting with what they say is wrong, and their submissions,
// a scheduled one with its run handed to the node.
package app.tingid.state

import app.tingid.chain.Coin
import app.tingid.chain.Contracts
import app.tingid.chain.FeePayer
import app.tingid.chain.HttpFailure
import app.tingid.chain.NodeStatus
import app.tingid.chain.Scheme
import app.tingid.chain.filled
import app.tingid.chain.hex
import app.tingid.chain.publicKey
import app.tingid.chain.transactionId
import app.tingid.chain.unhex
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class CoinToolsTest {
    private val spending = UserAccount(0, "Spending", filled(0x10), 0)
    private val savings = UserAccount(1, "Savings", filled(0x11), 604_800)
    private val accounts = listOf(spending, savings)

    private fun coin(n: Int, name: String, symbol: String, height: Long, minter: ByteArray = Coin.ZERO_ACCOUNT, max: BigInteger = Coin.UNCAPPED, issued: Long = 0) =
        CoinInfo(
            filled(n),
            filled(n).hex(),
            Coin.Config(name, symbol, 2, max, BigInteger.ZERO, 0, BigInteger.ZERO, height),
            Coin.State(BigInteger.valueOf(issued), BigInteger.ZERO, 0, minter),
        )

    private fun units(n: Long) = BigInteger.valueOf(n)

    @Test
    fun `the browser lists listed coins first, then followed ones, then the newest`() {
        val listed = coin(1, "Daler", "DLR", 5)
        val followed = coin(2, "Garden", "GRDN", 10)
        val old = coin(3, "Old", "OLD", 20)
        val new = coin(4, "New", "NEW", 30, minter = savings.account)
        val all = listOf(old, new, followed, listed)
        val rows = coinRows(all, CoinFilter.All, "", listOf(followed.hex), listOf(listed.hex), accounts)
        assertEquals(listOf(listed, followed, new, old), rows)
        assertEquals(listOf(followed), coinRows(all, CoinFilter.Following, "", listOf(followed.hex), listOf(listed.hex), accounts))
        assertEquals(listOf(new), coinRows(all, CoinFilter.Yours, "", emptyList(), emptyList(), accounts))
        // By name, by symbol, by the start of the contract ID.
        assertEquals(listOf(followed), coinRows(all, CoinFilter.All, " gard ", emptyList(), emptyList(), accounts))
        assertEquals(listOf(listed), coinRows(all, CoinFilter.All, "dlr", emptyList(), emptyList(), accounts))
        assertEquals(listOf(old), coinRows(all, CoinFilter.All, "0303", emptyList(), emptyList(), accounts))
    }

    @Test
    fun `a fixed supply goes to the account chosen`() {
        val form = CoinForm(name = "Garden Tokens", symbol = " GRDN ", supply = "1000000", toSlot = 1)
        val d = coinDraft(form, accounts, advanced = false)
        assertTrue(d.ready)
        assertEquals(emptyList<String>(), d.problems)
        val o = d.options!!
        assertEquals("GRDN", o.symbol)
        assertEquals(units(100_000_000_000_000), o.maxSupply)
        assertTrue(o.allocations.single().account.contentEquals(savings.account))
        assertEquals("1 000 000 GRDN go to your Savings. No one can ever make more.", d.review)
    }

    @Test
    fun `a form says what is wrong once something is typed`() {
        val empty = coinDraft(CoinForm(), accounts, advanced = false)
        assertFalse(empty.ready)
        assertFalse(CoinForm().started)
        assertEquals(emptyList<String>(), empty.problems)
        assertEquals(listOf("decimals must be a whole number from 0 to 18"), coinDraft(CoinForm(decimals = "19"), accounts, false).problems)
        assertEquals(listOf("enter the supply with at most 2 decimals"), coinDraft(CoinForm(decimals = "2", supply = "1.234"), accounts, false).problems)
        // Hidden characters could pass one coin off as another.
        val hidden = coinDraft(CoinForm(name = "Dal​er", symbol = "DLR", supply = "5"), accounts, false)
        assertFalse(hidden.ready)
        assertEquals(listOf("the name and symbol cannot hold invisible or direction characters"), hidden.problems)
        // Not said before both the name and symbol are there.
        assertEquals(emptyList<String>(), coinDraft(CoinForm(supply = "5"), accounts, false).problems)
    }

    @Test
    fun `a mined coin halves to a cap, unless a lowest reward keeps paying`() {
        val mined = CoinForm(name = "Ore", symbol = "ORE", kind = CoinKind.Mined, reward = "50")
        val d = coinDraft(mined, accounts, advanced = false)
        assertTrue(d.ready)
        assertEquals(units(2 * 50 * 210_000).multiply(units(100_000_000)), d.options!!.maxSupply)
        assertEquals("Miners earn 50 ORE per block, halving every 210 000 blocks (about 145.8 days); at most 21 000 000 ORE in all.", d.review)
        // Without the advanced settings the lowest reward is not asked, and not used.
        assertEquals(BigInteger.ZERO, coinDraft(mined.copy(tail = "1"), accounts, advanced = false).options!!.tailReward)
        val tail = coinDraft(mined.copy(tail = "1"), accounts, advanced = true)
        assertEquals(Coin.UNCAPPED, tail.options!!.maxSupply)
        assertEquals("Miners earn 50 ORE per block, halving every 210 000 blocks (about 145.8 days) and never below 1 ORE; no cap.", tail.review)
        assertEquals("Miners earn 50 ORE per block, the same in every block; no cap.", coinDraft(mined.copy(halving = "0"), accounts, false).review)
        assertEquals(listOf("the halving interval is a whole number of blocks"), coinDraft(mined.copy(halving = "1e5"), accounts, false).problems)
        assertEquals(listOf("the reward is too large"), coinDraft(mined.copy(reward = "1" + "0".repeat(32)), accounts, false).problems)
    }

    @Test
    fun `a minted coin is minted from Savings unless another account is picked`() {
        val form = CoinForm(name = "Points", symbol = "PTS", kind = CoinKind.Minted)
        val d = coinDraft(form, accounts, advanced = false)
        assertSame(savings, d.minter)
        assertSame(savings, d.savings)
        assertTrue(d.options!!.minter.contentEquals(savings.account))
        assertEquals("Your Savings mints; each mint waits 7 days and can be cancelled until then. No cap.", d.review)
        val picked = coinDraft(form.copy(minterSlot = 0, cap = "500"), accounts, false)
        assertEquals("Your Spending mints; mints happen at once. At most 500 PTS in all.", picked.review)
        assertEquals(listOf("enter the cap with at most 8 decimals"), coinDraft(form.copy(cap = "x"), accounts, false).problems)
    }

    @Test
    fun `a mint goes to an account of the list or the one typed, within the cap`() {
        val c = coin(5, "Points", "PTS", 1, minter = spending.account, max = units(10_000), issued = 9_000)
        val other = filled(0x55).hex()
        val names = mapOf(other to "Anna")
        val own = mintDraft(c, accounts, names, 1, "", "5", null)
        assertTrue(own.ready)
        assertEquals(savings.account.hex(), own.to)
        assertEquals("Savings", own.toLabel)
        assertEquals(1L, own.ownSlot)
        assertEquals(units(500), own.amount)
        assertEquals(units(1_000), own.left)
        val typed = mintDraft(c, accounts, names, 1, " $other ", "", null)
        assertFalse(typed.ready)
        assertEquals(other, typed.to)
        assertEquals("Anna", typed.toLabel)
        assertNull(typed.ownSlot)
        assertEquals(listOf("the cap leaves 10 PTS to mint"), mintDraft(c, accounts, names, 0, "", "10.01", null).problems)
        assertEquals(listOf("enter an amount with at most 2 decimals"), mintDraft(c, accounts, names, 0, "", "1.001", null).problems)
        assertEquals(
            listOf("the account is 64 hex digits or a pay link", "the fee needs more"),
            mintDraft(c, accounts, names, 0, "anna", "1", "the fee needs more").problems,
        )
        assertEquals(listOf("that account is reserved"), mintDraft(c, accounts, names, 0, Coin.STATE_KEY.hex(), "1", null).problems)
    }

    @Test
    fun `minting moves to Savings by default, to someone else, or to no one`() {
        val third = UserAccount(2, "Account 2", filled(0x12), 0)
        val mine = minterDraft(spending, accounts + third, emptyMap(), MinterTarget.Mine, null, "", null)
        assertEquals(listOf(savings, third), mine.others)
        assertSame(savings, mine.chosen)
        assertEquals(savings.account.hex(), mine.next)
        assertTrue(mine.ready)
        assertSame(third, minterDraft(spending, accounts + third, emptyMap(), MinterTarget.Mine, 2, "", null).chosen)
        val none = minterDraft(spending, accounts, emptyMap(), MinterTarget.None, null, "", null)
        assertEquals(Coin.ZERO_ACCOUNT.hex(), none.next)
        assertEquals("no one", none.label)
        val blank = minterDraft(spending, accounts, emptyMap(), MinterTarget.Other, null, "", null)
        assertFalse(blank.ready)
        assertEquals(emptyList<String>(), blank.problems)
        assertEquals(listOf("that account is reserved"), minterDraft(spending, accounts, emptyMap(), MinterTarget.Other, null, Coin.STATE_KEY.hex(), null).problems)
    }

    // ---- Submissions ------------------------------------------------------------

    private val node = FakeRestNode(filled(0x27))
    private val app = TestApp(node)
    private val secret = filled(1)
    private val key = KeyMeta("k1", "Phone", Scheme.Secp256k1, publicKey(secret).hex(), 0)
    private val user = User("u1", "Bo", "#000", UserKind.Key, keyId = key.id)
    private val c = Contracts.canonical(node.chainId)
    private val account = userAccounts(user, c, listOf(key), null)[0]

    private fun workspace() = WorkspaceState(
        node = app.chain.node,
        info = ChainInfo(NodeStatus(node.chainId, 10, listOf(1L, 1, 1, 1), "00".repeat(32), BigInteger.ONE, 0, "test"), c),
        coins = emptyList(),
        coinById = emptyMap(),
        coinsLoading = false,
        missingCoins = emptyList(),
        user = user,
        otherChain = false,
        keys = listOf(key),
        advanced = false,
        identity = null,
        identityLoading = false,
        accounts = listOf(account),
        scope = null,
        names = emptyMap(),
        nodeError = null,
    )

    private val fee = object : FeeSource {
        override val summary = "Fee 0.01 PTS from Account"
        override suspend fun resolve(): FeePayer? = null
    }

    init {
        app.secrets.saveKey(key.id, secret)
        app.store.addKey(key)
        app.store.addUser(user)
    }

    private fun mint(minter: UserAccount, paid: BigInteger = BigInteger.ZERO): Sent = runBlocking {
        val pts = coin(6, "Points", "PTS", 1, minter = minter.account)
        // The nonce is given: the fake node proves no account records.
        submitMinterAction(app.chain, workspace(), pts, minter, Coin.Effect.Mint(filled(0x55), units(500)), BigInteger.ONE, fee, "Mint 5 PTS to Anna", paid, now = { app.clock }) { s, a, p ->
            app.chain.submitCoinAction(node.chainId, pts.id, s, a, nonceOverride = 7, payer = p)
        }
    }

    @Test
    fun `a mint from an instant account goes at once, noted with its fee`() {
        val sent = mint(account, paid = units(1))
        assertFalse(sent.unsure)
        assertEquals(1, node.posted.size)
        val noted = app.store.data.value.submitted.single()
        assertEquals(sent.txId, noted.id)
        assertEquals("Mint 5 PTS to Anna · Fee 0.01 PTS from Account", noted.summary)
        // What the account pays itself, for its "on the way".
        assertEquals(filled(6).hex(), noted.coin)
        assertEquals("1", noted.amount)
    }

    @Test
    fun `a mint from a time-locked account hands its run to the node for when the lock has passed`() {
        val locked = UserAccount(account.slot, account.label, account.account, 3_600)
        mint(locked)
        assertEquals(listOf(null, app.clock + 3_600), node.posted.map { it.second })
        val noted = app.store.data.value.submitted.single()
        assertEquals("Mint 5 PTS to Anna · Fee 0.01 PTS from Account", noted.summary)
        assertNull(noted.coin)
    }

    @Test
    fun `a run the node does not take is left to the wallet, and a lost answer still queues it`() {
        val locked = UserAccount(account.slot, account.label, account.account, 3_600)
        // The 1st post (the mint) gets no answer; the 4th (the second mint's run) is refused.
        node.accept = { tx ->
            when (node.posted.size) {
                1 -> throw HttpFailure("could not be reached")
                4 -> jsonResponse(buildJsonObject { put("error", "no room") }, 400)
                else -> jsonResponse(buildJsonObject { put("id", transactionId(node.chainId, tx).hex()) })
            }
        }
        val sent = mint(locked)
        assertTrue(sent.unsure)
        assertEquals(listOf(null, app.clock + 3_600), node.posted.map { it.second })
        assertEquals("Mint 5 PTS to Anna · Fee 0.01 PTS from Account", app.store.data.value.submitted[0].summary)
        mint(locked)
        assertEquals(4, node.posted.size)
        assertEquals("Mint 5 PTS to Anna · run it from the wallet when due · Fee 0.01 PTS from Account", app.store.data.value.submitted[0].summary)
    }

    @Test
    fun `a new coin is deployed, followed and noted`() = runBlocking {
        val d = coinDraft(CoinForm(name = "Garden Tokens", symbol = "GRDN", supply = "100"), listOf(account), advanced = false)
        val txId = createCoin(app.chain, workspace(), d.options!!, now = { app.clock })
        assertEquals(1, node.posted.size)
        assertEquals(txId, transactionId(node.chainId, unhex(node.posted[0].first)).hex())
        assertEquals(1, app.store.data.value.settings.coins.size)
        assertEquals("Create Garden Tokens (GRDN)", app.store.data.value.submitted.single().summary)
    }
}
