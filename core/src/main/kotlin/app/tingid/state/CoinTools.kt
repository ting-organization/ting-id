// The coin tools (Settings → Coin tools): the coin browser's rows, and the
// forms to create a coin, mint one and move its minting as what was typed
// makes them, each with what is wrong in words, and their submissions. The
// screens show the problems as one banner and send what the forms make.
package app.tingid.state

import app.tingid.chain.Coin
import app.tingid.chain.FeePayer
import app.tingid.chain.Links
import app.tingid.chain.MaybeSent
import app.tingid.chain.Signer
import app.tingid.chain.formatAmount
import app.tingid.chain.hex
import app.tingid.chain.nowSeconds
import app.tingid.chain.parseAmount
import app.tingid.chain.trimWhiteSpace
import app.tingid.chain.unhex
import app.tingid.text.Names
import app.tingid.text.amountOf
import app.tingid.text.capped
import app.tingid.text.minterAccount
import app.tingid.text.relativeDuration
import kotlinx.coroutines.CancellationException
import java.math.BigInteger

/** Seconds per block the chain aims for. */
const val BLOCK_SECONDS = 60L

/** About how long `blocks` blocks take, in seconds; a count too large to say is as long as can be. */
fun blocksTime(blocks: Long): Long = if (blocks > Long.MAX_VALUE / BLOCK_SECONDS) Long.MAX_VALUE else blocks * BLOCK_SECONDS

// ---- The coin browser -----------------------------------------------------------

/** Which coins the browser lists: all, the followed ones, or those one of the user's accounts mints. */
enum class CoinFilter { All, Following, Yours }

/**
 * The browser's rows: the coins of `infos` that pass `filter` and `query` (in
 * the name or symbol, or the start of the contract ID), the listed ones
 * first, then the followed ones, then the newest.
 */
fun coinRows(
    infos: Collection<CoinInfo>,
    filter: CoinFilter,
    query: String,
    followed: List<String>,
    listedIds: List<String>,
    accounts: List<UserAccount>,
): List<CoinInfo> {
    val q = trimWhiteSpace(query).lowercase()
    fun rank(c: CoinInfo) = if (c.hex in listedIds) 0 else if (c.hex in followed) 1 else 2
    return infos
        .filter {
            when (filter) {
                CoinFilter.All -> true
                CoinFilter.Following -> it.hex in followed
                CoinFilter.Yours -> minterAccount(it, accounts) != null
            }
        }
        .filter { q.isEmpty() || it.config.name.lowercase().contains(q) || it.config.symbol.lowercase().contains(q) || it.hex.startsWith(q) }
        .sortedWith(compareBy<CoinInfo> { rank(it) }.thenByDescending { it.config.deployHeight })
}

// ---- Creating a coin ------------------------------------------------------------

/** Where a new coin's coins come from: all at once, mining rewards, or a minter. */
enum class CoinKind { Fixed, Mined, Minted }

/**
 * The new coin form as typed. `toSlot`: the account a fixed supply goes to;
 * `minterSlot`: the minter picked, null for the recommended one.
 */
data class CoinForm(
    val name: String = "",
    val symbol: String = "",
    val decimals: String = "8",
    val kind: CoinKind = CoinKind.Fixed,
    val supply: String = "",
    val toSlot: Long = 0,
    val reward: String = "",
    val halving: String = "210000",
    val tail: String = "",
    val minterSlot: Long? = null,
    val cap: String = "",
) {
    /** Whether anything that makes the coin was typed yet: until then its problems are not news. */
    val started: Boolean get() = name.isNotEmpty() || symbol.isNotEmpty() || supply.isNotEmpty() || reward.isNotEmpty() || cap.isNotEmpty()
}

class CoinDraft(
    /** The deployment, once the form is complete. */
    val options: Coin.Options?,
    /** What is wrong, each a phrase. */
    val problems: List<String>,
    /** The options are complete and the coin would deploy. */
    val ready: Boolean,
    /** What creating the coin does, in words, once the options are complete. */
    val review: String?,
    /** The account a fixed supply goes to. */
    val to: UserAccount?,
    /** The account that mints a minted coin. */
    val minter: UserAccount?,
    /** The first time-locked account, the minter recommended. */
    val savings: UserAccount?,
)

/** An amount typed in a coin with `decimals`; null when it does not read as one. */
private fun amountIn(text: String, decimals: Int): BigInteger? = try {
    parseAmount(trimWhiteSpace(text), decimals)
} catch (e: Exception) {
    null
}

private val DECIMALS = Regex("^[0-9]{1,2}$")
private val WHOLE = Regex("^[0-9]+$")

/**
 * A new coin from the form `f`: its options, what is wrong, and what it does.
 * A fixed supply goes to one of the user's `accounts` at once; a mined coin
 * pays block rewards that halve; a minted coin has one of the accounts mint,
 * Savings unless another is picked, so that a stolen key cannot mint at
 * once. `advanced`: a mined coin may have a lowest reward.
 */
fun coinDraft(f: CoinForm, accounts: List<UserAccount>, advanced: Boolean): CoinDraft {
    val savings = accounts.firstOrNull { it.delay > 0 }
    val minter = accounts.firstOrNull { it.slot == (f.minterSlot ?: savings?.slot ?: 0L) } ?: accounts.firstOrNull()
    val to = accounts.firstOrNull { it.slot == f.toSlot } ?: accounts.firstOrNull()
    val typed = trimWhiteSpace(f.decimals)
    val decimals = if (DECIMALS.matches(typed) && typed.toInt() <= 18) typed.toInt() else null
    val problems = ArrayList<String>()
    if (decimals == null) problems.add("decimals must be a whole number from 0 to 18")
    val name = trimWhiteSpace(f.name)
    val symbol = trimWhiteSpace(f.symbol)

    fun options(): Coin.Options? {
        if (decimals == null || minter == null || to == null) return null
        fun base(maxSupply: BigInteger, initialReward: BigInteger = BigInteger.ZERO, halvingInterval: Long = 0, tailReward: BigInteger = BigInteger.ZERO, minter: ByteArray = Coin.ZERO_ACCOUNT, allocations: List<Coin.Allocation> = emptyList()) =
            Coin.Options(name, symbol, decimals, maxSupply, initialReward, halvingInterval, tailReward, minter, allocations)
        when (f.kind) {
            CoinKind.Fixed -> {
                val total = amountIn(f.supply, decimals)
                if (f.supply.isNotEmpty() && total == null) problems.add("enter the supply with at most $decimals decimals")
                if (total == null || total.signum() == 0) return null
                return base(total, allocations = listOf(Coin.Allocation(to.account, total)))
            }
            CoinKind.Mined -> {
                val reward = amountIn(f.reward, decimals)
                val tail = if (advanced && f.tail.isNotEmpty()) amountIn(f.tail, decimals) else BigInteger.ZERO
                // A count of blocks the chain cannot hold is no count at all.
                val halving = trimWhiteSpace(f.halving).takeIf { WHOLE.matches(it) }?.toLongOrNull()
                if (f.reward.isNotEmpty() && reward == null) problems.add("enter the reward with at most $decimals decimals")
                if (tail == null) problems.add("enter the least reward with at most $decimals decimals")
                if (halving == null) problems.add("the halving interval is a whole number of blocks")
                if (reward == null || reward.signum() == 0 || tail == null || halving == null) return null
                // Halving rewards add up to just under twice the first era: the cap. A
                // constant reward or a least reward keeps paying, so those have none.
                val maxSupply = if (halving > 0 && tail.signum() == 0) BigInteger.TWO * reward * BigInteger.valueOf(halving) else Coin.UNCAPPED
                if (maxSupply > Coin.UNCAPPED) {
                    problems.add("the reward is too large")
                    return null
                }
                return base(maxSupply, initialReward = reward, halvingInterval = halving, tailReward = tail)
            }
            CoinKind.Minted -> {
                val cap = if (trimWhiteSpace(f.cap).isNotEmpty()) amountIn(f.cap, decimals) else Coin.UNCAPPED
                if (cap == null) problems.add("enter the cap with at most $decimals decimals")
                if (cap == null || cap.signum() == 0) return null
                return base(cap, minter = minter.account)
            }
        }
    }

    val options = options()
    val invalid = options?.let { Coin.deployProblem(it) }
    if (invalid != null && name.isNotEmpty() && symbol.isNotEmpty()) problems.add(invalid)
    val review = if (options == null || decimals == null) null else reviewOf(f.kind, options, decimals, to, minter)
    return CoinDraft(options, problems, options != null && invalid == null, review, to, minter, savings)
}

/** What creating the coin `o` does, in a sentence or two. */
private fun reviewOf(kind: CoinKind, o: Coin.Options, decimals: Int, to: UserAccount?, minter: UserAccount?): String {
    fun fmt(v: BigInteger) = "${formatAmount(v, decimals)} ${o.symbol}"
    return when (kind) {
        CoinKind.Fixed -> "${fmt(o.maxSupply)} go to your ${to?.label}. No one can ever make more."
        CoinKind.Mined -> {
            val era = if (o.halvingInterval > 0) {
                ", halving every ${formatAmount(BigInteger.valueOf(o.halvingInterval), 0)} blocks (about ${relativeDuration(blocksTime(o.halvingInterval))})"
            } else {
                ", the same in every block"
            }
            val least = if (o.tailReward.signum() > 0) " and never below ${fmt(o.tailReward)}" else ""
            val cap = if (o.maxSupply == Coin.UNCAPPED) "no cap" else "at most ${fmt(o.maxSupply)} in all"
            "Miners earn ${fmt(o.initialReward)} per block$era$least; $cap."
        }
        CoinKind.Minted -> {
            val cap = if (o.maxSupply == Coin.UNCAPPED) "No cap" else "At most ${fmt(o.maxSupply)} in all"
            val wait = if (minter != null && minter.delay > 0) "; each mint waits ${relativeDuration(minter.delay)} and can be cancelled until then" else "; mints happen at once"
            "Your ${minter?.label} mints$wait. $cap."
        }
    }
}

/**
 * Deploys a coin with `options`, follows it and notes the submission; its
 * transaction's id. A deployment pays no fee: it waits for room in a block.
 */
suspend fun createCoin(chain: Chain, w: WorkspaceState, options: Coin.Options, now: () -> Long = ::nowSeconds): String {
    val user = w.user ?: throw IllegalStateException("no user to create the coin")
    val info = w.info ?: throw IllegalStateException("the node is not reachable")
    val (txId, coinId) = chain.deployCoin(info.status.chainId, options)
    chain.store.addCoin(coinId.hex())
    chain.store.addSubmitted(SubmittedTx(id = txId, at = now(), summary = "Create ${options.name} (${options.symbol})", userId = user.id))
    return txId
}

// ---- Minting --------------------------------------------------------------------

private const val NOT_AN_ACCOUNT = "the account is 64 hex digits or a pay link"

/** Who `other` (typed or pasted) names, in words: a known name, else the account. */
private fun otherLabel(names: Names, account: String?): String = names[account ?: ""] ?: account ?: ""

class MintDraft(
    /** The account minted to (hex), null while none is named. */
    val to: String?,
    /** Who that is, in words. */
    val toLabel: String,
    /** The user's account chosen in the list, null while another one is typed. */
    val ownSlot: Long?,
    /** The account the other field names, when it names one. */
    val other: String?,
    /** The amount, 0 while none is typed; null when it does not read as one. */
    val amount: BigInteger?,
    /** What the cap leaves to mint; null for no cap. */
    val left: BigInteger?,
    /** What is wrong, each a phrase. */
    val problems: List<String>,
) {
    val ready: Boolean get() = problems.isEmpty() && amount != null && amount.signum() > 0 && to != null
}

/**
 * A mint of `c` as typed: `amount` to the user's account in `toSlot`, or to
 * the account `other` names (an account, or a pay link), which wins over the
 * list. `feeProblem`: what is wrong with its fee.
 */
fun mintDraft(c: CoinInfo, accounts: List<UserAccount>, names: Names, toSlot: Long, other: String, amount: String, feeProblem: String?): MintDraft {
    val decimals = c.config.decimals
    val otherText = trimWhiteSpace(other)
    val otherAccount = Links.accountIn(otherText)
    val own = accounts.firstOrNull { it.slot == toSlot } ?: accounts.firstOrNull()
    val to = if (otherText.isNotEmpty()) otherAccount else own?.account?.hex()
    val toLabel = if (otherText.isNotEmpty()) otherLabel(names, otherAccount) else own?.label ?: ""
    val typed = trimWhiteSpace(amount)
    val parsed = if (typed.isEmpty()) BigInteger.ZERO else amountIn(typed, decimals)
    val left = if (capped(c)) c.config.maxSupply - c.state.issued else null
    val problems = ArrayList<String>()
    if (otherText.isNotEmpty() && otherAccount == null) problems.add(NOT_AN_ACCOUNT)
    if (parsed == null) problems.add("enter an amount with at most $decimals decimals")
    if (parsed != null && parsed.signum() > 0 && left != null && parsed > left) problems.add("the cap leaves ${amountOf(c, left)} to mint")
    if (to != null && Coin.isReserved(unhex(to))) problems.add("that account is reserved")
    if (feeProblem != null) problems.add(feeProblem)
    return MintDraft(to, toLabel, if (otherText.isNotEmpty()) null else own?.slot, otherAccount, parsed, left, problems)
}

/** Where minting goes: another of the user's accounts, someone else's, or no one's. */
enum class MinterTarget { Mine, Other, None }

class MinterDraft(
    /** The user's accounts other than the minter, to move minting to. */
    val others: List<UserAccount>,
    /** The one of them chosen. */
    val chosen: UserAccount?,
    /** The account the other field names, when it names one. */
    val other: String?,
    /** The new minter (hex), null while none is named. */
    val next: String?,
    /** Who that is, in words. */
    val label: String,
    /** What is wrong, each a phrase. */
    val problems: List<String>,
) {
    val ready: Boolean get() = problems.isEmpty() && next != null
}

/**
 * Moving minting away from the user's account `minter`: to another of
 * `accounts` (`slot`, else the first time-locked one, the safer home for
 * minting), to the account `other` names, or to no one. `feeProblem`: what
 * is wrong with its fee.
 */
fun minterDraft(minter: UserAccount, accounts: List<UserAccount>, names: Names, target: MinterTarget, slot: Long?, other: String, feeProblem: String?): MinterDraft {
    val others = accounts.filter { it.slot != minter.slot }
    val chosen = others.firstOrNull { it.slot == slot } ?: others.firstOrNull { it.delay > 0 } ?: others.firstOrNull()
    val otherText = trimWhiteSpace(other)
    val otherAccount = Links.accountIn(otherText)
    val next = when (target) {
        MinterTarget.None -> Coin.ZERO_ACCOUNT.hex()
        MinterTarget.Other -> otherAccount
        MinterTarget.Mine -> chosen?.account?.hex()
    }
    val label = when (target) {
        MinterTarget.None -> "no one"
        MinterTarget.Other -> otherLabel(names, otherAccount)
        MinterTarget.Mine -> chosen?.label ?: ""
    }
    val problems = ArrayList<String>()
    if (target == MinterTarget.Other && otherText.isNotEmpty() && otherAccount == null) problems.add(NOT_AN_ACCOUNT)
    if (next != null && next == Coin.STATE_KEY.hex()) problems.add("that account is reserved")
    if (feeProblem != null) problems.add(feeProblem)
    return MinterDraft(others, chosen, otherAccount, next, label, problems)
}

/**
 * Signs `effect` (a mint, or a new minter) from the user's minting account
 * `minter` of `c` and submits it, with `tip` as its run fee and `fee`
 * paying for it. From a time-locked account the action is scheduled, and its
 * run handed to the node for when the lock has passed; when the node does not
 * take it, the summary says to run it from the wallet. The submission is
 * noted with `summary` and its fee, and `paid`: what the acting account pays
 * itself in `c`, for its "on the way".
 */
suspend fun submitMinterAction(
    chain: Chain,
    w: WorkspaceState,
    c: CoinInfo,
    minter: UserAccount,
    effect: Coin.Effect,
    tip: BigInteger,
    fee: FeeSource,
    summary: String,
    paid: BigInteger = BigInteger.ZERO,
    now: () -> Long = ::nowSeconds,
    /** How the signed action goes to the node; the chain's own way unless a test says. */
    send: (suspend (Signer, Coin.Action, FeePayer?) -> Chain.Submitted)? = null,
): Sent {
    val user = w.user ?: throw IllegalStateException("no user to sign")
    val info = w.info ?: throw IllegalStateException("the node is not reachable")
    val signer = chain.resolveSigner(user, minter.slot, info.canonical, w.keys, w.identity)
    val action = Coin.Action(effect, Coin.NO_TIMING.copy(tip = tip))
    val payer = fee.resolve()
    // A lost answer still gives the id, and the action's account and nonce: its run is queued all the same.
    val (r, account, nonce) = try {
        val s = send?.invoke(signer, action, payer) ?: chain.submitCoinAction(info.status.chainId, c.id, signer, action, payer = payer)
        Triple(Sent(s.txId, false), s.account, s.nonce)
    } catch (e: MaybeSent) {
        val (account, nonce) = e.action ?: throw e
        Triple(Sent(e.txId, true, e.action), account, nonce)
    }
    val handed = minter.delay == 0L || try {
        // The node runs it once the lock has passed.
        chain.queueRun(c.id, account, nonce, now() + minter.delay)
        true
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        false
    }
    val noted = summary + if (handed) "" else " · run it from the wallet when due"
    chain.store.addSubmitted(
        SubmittedTx(
            id = r.txId,
            at = now(),
            summary = withFee(noted, fee),
            userId = user.id,
            coin = if (paid.signum() > 0) c.hex else null,
            account = if (paid.signum() > 0) minter.account.hex() else null,
            amount = if (paid.signum() > 0) paid.toString() else null,
        ),
    )
    return r
}
