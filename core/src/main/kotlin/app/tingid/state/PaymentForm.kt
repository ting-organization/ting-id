// What the Send screen checks of a payment before it can be reviewed: the
// amount, the recipient, what the account has once payments on their way are
// counted, the fee and a scheduled run's fee, and what is left for later
// fees. Also whom the screen offers to pay. Pure, for tests: the screen keeps
// what was typed and reads the balances and the fee.
package app.tingid.state

import app.tingid.chain.Canonical
import app.tingid.chain.Coin
import app.tingid.chain.Id
import app.tingid.chain.formatAmount
import app.tingid.chain.hex
import app.tingid.chain.isHex
import app.tingid.chain.parseAmount
import app.tingid.chain.policyAccount
import app.tingid.chain.trimWhiteSpace
import app.tingid.chain.unhex
import app.tingid.text.lockOf
import java.math.BigInteger

/** The fee as a payment sees it: what [quoteFee] says, through the screen's fee choice. */
class PaymentFee(
    /** The fee in the payer's coin, base units; 0 for none. */
    val fee: BigInteger,
    /** The sending account pays it inside its own action, in the coin it sends. */
    val own: Boolean,
    /** What is wrong with the fee, if anything. */
    val problem: String?,
    /** What an operation carrying its own fee pays in the fee coin now. */
    val usual: BigInteger,
    val feeCoin: CoinInfo?,
    /** Every instant account of the user that may pay, with its balance. */
    val options: List<PayerOption>,
    val payer: PayerOption?,
    /** What the run of a scheduled payment in the coin sent pays its runner. */
    val runTip: BigInteger,
)

/** What the Send screen holds of a payment. */
class PaymentInput(
    /** The amount as typed, without its groups' spaces. */
    val amount: String,
    /** The recipient as typed: 64 hex digits once it is one. */
    val to: String,
    /** The coin sent; null while none is followed. */
    val coin: CoinInfo?,
    /** The sending account in that coin; null while none is known. */
    val from: AccountView?,
    /** What payments of the account still on their way take from it ([onTheWay]). */
    val outgoing: BigInteger,
    val fee: PaymentFee,
    /** Set for a later time, `at` (seconds), rather than now. */
    val later: Boolean,
    val at: Long,
    val now: Long,
    /** The recipient was found to be an identity's ID, not an account. */
    val toIsIdentity: Boolean,
    /** The coin a pay link asked for: nothing is sent in another one. */
    val codeCoin: String?,
)

class PaymentCheck(
    /** The amount in base units: 0 while none is typed, null when it is no amount. */
    val parsed: BigInteger?,
    /** How long the sending account's withdrawals wait, 0 for an instant one. */
    val lock: Long,
    /** It runs later: at a chosen time, or when a time-locked account's lock ends. */
    val willSchedule: Boolean,
    /** What the account has available, less what is on its way. */
    val availableNow: BigInteger,
    /** The fee the action carries itself, when its own account pays in this coin. */
    val ownFee: BigInteger,
    /** What a scheduled payment pays again when it runs, reserved until then. */
    val runFee: BigInteger,
    /** What leaves the sending account: the amount, its own fee and the run fee. */
    val needed: BigInteger,
    /** The most that leaves the fee, and a scheduled run's fee, payable; may be below 0. */
    val maxAmount: BigInteger,
    /** What stops the payment, as messages to show in a row. */
    val problems: List<String>,
    /** A pay link asked for another coin than the one chosen. */
    val coinMismatch: Boolean,
    /** Nothing stops it: it can be reviewed. */
    val ready: Boolean,
    /** What an amount like `1.000` reads as, when it may be meant as a thousand. */
    val dotCaution: String?,
    /** That little or nothing is left for later fees, when so. */
    val feeWarning: String?,
)

/** A dot and three figures may be meant as a thousand. */
private val DOT_THOUSAND = Regex("^[1-9][0-9]{0,2}\\.[0-9]{3}$")

fun checkPayment(i: PaymentInput): PaymentCheck {
    val c = i.coin
    val decimals = c?.config?.decimals ?: 0
    val symbol = c?.config?.symbol ?: ""
    fun amount(v: BigInteger) = "${formatAmount(v, decimals)} $symbol"
    var parseProblem: String? = null
    val parsed = try {
        if (i.amount.isNotEmpty()) parseAmount(i.amount, decimals) else BigInteger.ZERO
    } catch (e: Exception) {
        parseProblem = e.message
        null
    }
    val dotCaution = if (parsed != null && DOT_THOUSAND.matches(trimWhiteSpace(i.amount))) "Read as ${amount(parsed)}: the dot marks decimals." else null
    val fee = i.fee
    val ownFee = if (fee.own) fee.fee else BigInteger.ZERO
    val lock = i.from?.account?.delay ?: 0
    val willSchedule = i.later || lock > 0
    // What payments still on their way take from the account is not there to send again.
    val held = i.from?.record?.available ?: BigInteger.ZERO
    val availableNow = if (held > i.outgoing) held - i.outgoing else BigInteger.ZERO
    // A scheduled action pays the fee's amount again when it runs, reserved until then.
    val runFee = if (willSchedule && c != null) fee.runTip else BigInteger.ZERO
    val needed = (parsed ?: BigInteger.ZERO) + ownFee + runFee
    val recipient = isHex(i.to, 32)
    val problems = ArrayList<String>()
    if (c == null) problems.add("follow a coin in Settings")
    if (i.from == null) problems.add("no account to send from")
    if (i.to.isNotEmpty() && !recipient) problems.add("the recipient must be a 64-digit hex account")
    // An account nobody can spend from: the zero account and the coin's own reserved keys.
    if (recipient && (Coin.isReserved(unhex(i.to)) || i.to.lowercase() == Coin.ZERO_ACCOUNT.hex())) problems.add("nobody can spend from that account: check the recipient")
    if (recipient && i.toIsIdentity) problems.add("that is an identity ID, not an account: send to its Spending account")
    if (i.amount.isNotEmpty() && parsed == null) problems.add(parseProblem ?: "enter an amount like 12.5")
    if (parsed != null && parsed.signum() == 0 && i.amount.isNotEmpty()) problems.add("amount must be above zero")
    fee.problem?.let { problems.add(it) }
    if (i.from != null && needed > availableNow) problems.add("only ${amount(availableNow)} available")
    if (i.later) When.laterProblem(i.at, i.now)?.let { problems.add(it) }
    val coinMismatch = i.codeCoin != null && i.codeCoin != c?.hex
    val ready = problems.isEmpty() && i.to.isNotEmpty() && parsed != null && parsed.signum() > 0 && !coinMismatch
    val maxAmount = availableNow - ownFee - runFee
    return PaymentCheck(
        parsed = parsed,
        lock = lock,
        willSchedule = willSchedule,
        availableNow = availableNow,
        ownFee = ownFee,
        runFee = runFee,
        needed = needed,
        maxAmount = maxAmount,
        problems = problems,
        coinMismatch = coinMismatch,
        ready = ready,
        dotCaution = dotCaution,
        feeWarning = if (ready) feeWarning(i, needed) else null,
    )
}

/**
 * What the fee coin leaves on the instant accounts after the payment `i`,
 * which takes `needed` from its account: with little left, later payments and
 * changes cannot pay their fees. Null while it is not known.
 */
fun feesLeft(i: PaymentInput, needed: BigInteger): BigInteger? {
    val feeCoin = i.fee.feeCoin ?: return null
    val from = i.from ?: return null
    val holdings = i.fee.options.filter { it.coin.hex == feeCoin.hex }
    if (holdings.isEmpty() || holdings.any { it.available == null }) return null
    var left = holdings.fold(BigInteger.ZERO) { sum, o -> sum + (o.available ?: BigInteger.ZERO) }
    if (i.coin?.hex == feeCoin.hex && from.account.delay == 0L) left -= needed
    if (!i.fee.own && i.fee.payer?.coin?.hex == feeCoin.hex) left -= i.fee.fee
    return if (left.signum() < 0) BigInteger.ZERO else left
}

private fun feeWarning(i: PaymentInput, needed: BigInteger): String? {
    val feeCoin = i.fee.feeCoin ?: return null
    val left = feesLeft(i, needed) ?: return null
    val usual = i.fee.usual
    if (usual.signum() <= 0 || left >= usual * BigInteger.valueOf(3)) return null
    val d = feeCoin.config.decimals
    val s = feeCoin.config.symbol
    val what = if (left.signum() == 0) "nothing" else "only ${formatAmount(left, d)} $s"
    return "After this, $what is left for fees. Each later payment or change pays one, about ${formatAmount(usual, d)} $s, so keep a little $s in Spending."
}

/** Someone the Send screen offers to pay: a name, what the account is, the account (hex) and a colour. */
class Recipient(val label: String, val sub: String, val account: String, val color: String)

/** The colour of a contact among the recipients. */
const val CONTACT_COLOR = "#64748B"

/**
 * Whom the Send screen offers to pay: every account of this phone's identity
 * users, as their creation lists them, except the one sending
 * (`activeUserId`'s slot `fromSlot`); the accounts of its other key users,
 * on chain `c`; and the contacts.
 */
fun recipients(users: List<User>, keys: List<KeyMeta>, contacts: List<Contact>, c: Canonical?, activeUserId: String?, fromSlot: Long?): List<Recipient> {
    val out = ArrayList<Recipient>()
    for (u in users) {
        if (u.kind == UserKind.Identity && u.identityId != null && u.registry != null) {
            val slots = u.creation?.let { Id.decodeCreation(unhex(it)) }?.slots ?: Id.DEFAULT_SLOTS
            slots.forEachIndexed { index, delay ->
                val slot = index.toLong()
                if (u.id == activeUserId && slot == fromSlot) return@forEachIndexed
                val account = Id.account(unhex(u.registry), unhex(u.identityId), slot).hex()
                out.add(Recipient("${u.name} · ${slotLabel(slot)}", if (delay != 0L) lockOf(delay) else "instant", account, u.color))
            }
        } else if (u.kind == UserKind.Key && c != null) {
            val key = keys.firstOrNull { it.id == u.keyId }
            if (key != null && u.id != activeUserId) {
                out.add(Recipient(u.name, "simple account", policyAccount(c.policyFor(key.scheme), unhex(key.pub)).hex(), u.color))
            }
        }
    }
    for (contact in contacts) out.add(Recipient(contact.name, "contact", contact.account, CONTACT_COLOR))
    return out
}
