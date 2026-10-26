// Words for on-chain things: amounts in display units, accounts by the names
// this device knows, effects and events in plain language.
package app.tingid.text

import app.tingid.chain.CONFIRMATIONS
import app.tingid.chain.CallEvent
import app.tingid.chain.Coin
import app.tingid.chain.Id
import app.tingid.chain.NodeHealth
import app.tingid.chain.NodeState
import app.tingid.chain.Offchain
import app.tingid.chain.PoolNotice
import app.tingid.chain.equal
import app.tingid.chain.formatAmount
import app.tingid.chain.hex
import app.tingid.chain.nowSeconds
import app.tingid.chain.relativeTime
import app.tingid.chain.sha256
import app.tingid.chain.shortId
import app.tingid.chain.trimWhiteSpace
import app.tingid.state.ActivityItem
import app.tingid.state.CoinInfo
import app.tingid.state.SLOT_LABELS
import app.tingid.state.Settled
import app.tingid.state.Watch
import app.tingid.state.slotLabel
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Account (hex) → label, built from users, their slots and contacts. */
typealias Names = Map<String, String>

/** How a line is colored. */
enum class Tone { Neutral, Primary, Accent, Warning, Danger, Success }

/** The marks lines carry; `ionicon` names the one the first Ting ID drew. */
enum class Icon(val ionicon: String) {
    ArrowDown("arrow-down"),
    ArrowUp("arrow-up"),
    Flame("flame-outline"),
    Hammer("hammer-outline"),
    Swap("swap-horizontal"),
    CheckmarkCircle("checkmark-circle-outline"),
    CloseCircle("close-circle-outline"),
    Time("time-outline"),
    Play("play-outline"),
    Key("key-outline"),
    Fingerprint("finger-print-outline"),
    Hourglass("hourglass-outline"),
    ShieldCheckmark("shield-checkmark-outline"),
    Shield("shield-outline"),
    RefreshCircle("refresh-circle-outline"),
    LogIn("log-in-outline"),
    Document("document-text-outline"),
    Checkmark("checkmark-outline"),
    Refresh("refresh-outline"),
    AlertCircle("alert-circle-outline"),
    Program("extension-puzzle-outline"),
}

fun nameOf(names: Names, account: ByteArray): String = names[account.hex()] ?: shortId(account, 8, 6)

/** An account's short form after a name this device knows for it, as in "to 499d9b…e716"; nothing when no name is known, as the name is the short form then. */
private fun idAfterName(names: Names, account: ByteArray): String = if (account.hex() in names) " · ${shortId(account, 6, 4)}" else ""

/** A move between two accounts, as "Anna · Spending → Anna · Savings" reads when both are one identity's: "Spending → Savings". */
fun movedBetween(from: String, to: String): String {
    val owner = from.substringBefore(" · ", "")
    return if (owner.isNotEmpty() && to.startsWith("$owner · ")) "${from.removePrefix("$owner · ")} → ${to.removePrefix("$owner · ")}" else "$from → $to"
}

fun amount(c: CoinInfo?, value: BigInteger): String = if (c == null) value.toString() else "${formatAmount(value, c.config.decimals)} ${c.config.symbol}"

fun describeEffect(e: Coin.Effect, c: CoinInfo?, names: Names): String = when (e) {
    is Coin.Effect.Transfer -> "Send ${amount(c, e.amount)} to ${nameOf(names, e.to)}"
    is Coin.Effect.Burn -> "Burn ${amount(c, e.amount)}"
    is Coin.Effect.Mint -> "Mint ${amount(c, e.amount)} to ${nameOf(names, e.to)}"
    is Coin.Effect.SetMinter -> "Set minter to ${nameOf(names, e.minter)}"
}

/** A short fingerprint of a key: its parameters, abbreviated. */
fun keyFingerprint(key: Id.Key): String = shortId(key.params, 8, 4)

/** A device key as both phones show it before one adds the other: 16 hex digits of its hash, in groups of four. */
fun deviceFingerprint(pub: ByteArray): String = sha256(pub).copyOf(8).hex().chunked(4).joinToString(" ")

/** The keys a recovery installs, e.g. "1 key: 04a1b2c3…d4e5". */
fun describeKeys(keys: List<Id.Key>): String =
    "${keys.size} key${if (keys.size == 1) "" else "s"}: ${keys.joinToString(", ") { keyFingerprint(it) }}"

/**
 * A slot's name; a slot number from the chain may be beyond any Int, and is
 * never taken for one of the first two.
 */
private fun slotName(slot: Long): String = if (slot < SLOT_LABELS.size) slotLabel(slot) else "Account $slot"

/**
 * A change to an identity in words; without `advanced`, keys are the devices
 * that hold them, named as the Identity tab names them: this device, which
 * `onThisDevice` tells, or Device 1, 2, ….
 */
fun describeOperation(op: Id.Operation, names: Names, advanced: Boolean = true, onThisDevice: ((keyId: Long) -> Boolean)? = null): String = when (op) {
    is Id.Operation.AddKey -> if (advanced) "Add key ${keyFingerprint(op.key)}" else "Add a device"
    is Id.Operation.RemoveKey -> when {
        advanced -> "Remove key ${op.keyId}"
        onThisDevice?.invoke(op.keyId) == true -> "Remove this device"
        else -> "Remove Device ${op.keyId + 1}"
    }
    is Id.Operation.AddGuardian -> "Link guardian ${nameOf(names, op.id)}"
    is Id.Operation.RemoveGuardian -> "Unlink guardian ${nameOf(names, op.id)}"
    is Id.Operation.SetThreshold -> if (op.threshold == 0L) "Recovery needs a majority of guardians" else "Recovery needs ${count(op.threshold, "guardian")}"
    is Id.Operation.SetDelays -> "Delays: changes ${relativeDuration(op.changeDelay)}, recovery ${relativeDuration(op.recoveryDelay)}"
    is Id.Operation.SetSlot -> "${slotName(op.slot)}: ${if (op.delay == 0L) "instant" else lockOf(op.delay)}"
}

/** Why a transaction ran without succeeding, from its receipt's status, in words. */
fun statusWords(status: String): String = when (status) {
    "Revert" -> "the program refused it"
    "Trap" -> "the program stopped with an error"
    "Compute", "Access", "Data", "Growth" -> "it needed more than its budget"
    "Structure" -> "it is not well formed"
    "Missing" -> "the program it calls is not on chain"
    "InvalidModule" -> "the program could not be loaded"
    "Exists" -> "what it creates exists already"
    "ReadOnly" -> "it tried to change what it may only read"
    else -> "it failed ($status)"
}

/** A message as a sentence: capitalized, with a full stop at the end. */
fun sentence(message: String): String {
    val t = trimWhiteSpace(message)
    if (t.isEmpty()) return t
    val s = t[0].uppercase() + t.substring(1)
    return if (s.last() in ".!?…") s else "$s."
}

/** `1 guardian`, `3 guardians`. */
fun count(n: Long, noun: String, nouns: String = "${noun}s"): String = "$n ${if (n == 1L) noun else nouns}"

/** `1 day`, `1.5 days`: a number with one decimal at most, as [fixed1] writes it. */
private fun countOf(n: String, noun: String): String = "$n ${if (n == "1") noun else "${noun}s"}"

/** `v` with one decimal, as JavaScript's `toFixed(1)` rounds it, without a trailing `.0`: `1.5`, `2`. */
private fun fixed1(v: Double): String {
    val d = BigDecimal(v).setScale(1, RoundingMode.HALF_UP)
    return if (d.signum() == 0) "0" else d.stripTrailingZeros().toPlainString()
}

/** A span of time in words: `45 minutes`, `1 hour`, `7 days`. */
fun relativeDuration(seconds: Long): String {
    if (seconds == 0L) return "none"
    if (seconds < 3600) return count(Math.round(seconds / 60.0), "minute")
    // Rounded first, so that 25 hours reads `1 day`, not `1 days`.
    if (seconds < 86_400) return countOf(fixed1(seconds / 3600.0), "hour")
    return countOf(fixed1(seconds / 86_400.0), "day")
}

/** An account's lock as a noun: `7-day lock`, `12-hour lock`. */
fun lockOf(seconds: Long): String = when {
    seconds < 3600 -> "${Math.round(seconds / 60.0)}-minute lock"
    seconds < 86_400 -> "${fixed1(seconds / 3600.0)}-hour lock"
    else -> "${fixed1(seconds / 86_400.0)}-day lock"
}

/** When a scheduled payment runs: after the chosen wait and the account's lock, whichever ends later. */
fun runsIn(wait: Long, lock: Long): String {
    if (lock == 0L) return "Runs in ${relativeDuration(wait)}"
    if (wait == 0L) return "Runs in ${relativeDuration(lock)}, once the account's ${lockOf(lock)} has passed"
    return "Runs in ${relativeDuration(maxOf(wait, lock))}, the later of the chosen ${relativeDuration(wait)} and the account's ${lockOf(lock)}"
}

/** Who or what an activity was with: the other account, or the coin itself for what it issued or burned. */
/** Who an activity was with. `known`: a name of this phone's, not the account's short form. */
data class Party(val name: String, val coin: Boolean = false, val known: Boolean = true)

data class Described(
    val title: String,
    val detail: String? = null,
    val tone: Tone,
    val icon: Icon,
    /** Signed amount for the right column, e.g. "+5 DLR". */
    val amount: String? = null,
    val amountTone: Tone? = null,
    val party: Party? = null,
    /** The action in a few words, for a line under the party: the party's name says who, so no account follows it. */
    val short: String? = null,
    /** What a scheduled payment will move once it runs. */
    val planned: String? = null,
    /** The fees its transaction paid, e.g. "0.0001 DLR". */
    val fee: String? = null,
)

/**
 * Activity with each day's payments to the same miner in one coin as one row,
 * their amounts summed: a miner gets one with every block. `items` newest
 * first, as shown; `day` names an item's day.
 */
fun groupMinerPayments(items: List<ActivityItem>, day: (time: Long) -> String): List<ActivityItem> {
    val out = ArrayList<ActivityItem>()
    for (item in items) {
        val prev = out.lastOrNull()
        val e = item.coin as? Coin.Event.ProducerPayment
        val p = prev?.coin as? Coin.Event.ProducerPayment
        if (
            prev != null && e != null && p != null &&
            equal(p.payee, e.payee) &&
            equal(prev.contract, item.contract) &&
            item.time != 0L &&
            prev.time != 0L &&
            day(prev.time) == day(item.time)
        ) {
            out[out.size - 1] = prev.copy(coin = Coin.Event.ProducerPayment(p.payee, p.amount + e.amount), count = (prev.count ?: 1) + 1)
        } else {
            out.add(item)
        }
    }
    return out
}

/** An activity item in words; a fee its transaction paid joins the detail. `now` (seconds) is when it is shown. */
fun describeActivity(item: ActivityItem, coins: Map<String, CoinInfo>, names: Names, mine: List<ByteArray>, advanced: Boolean = false, now: Long = nowSeconds()): Described {
    val d = describeEvent(item, coins, names, mine, advanced, now)
    if (item.fees.isEmpty()) return d
    val fee = item.fees.joinToString(" + ") { f -> amount(coins[f.contract.hex()], f.amount) }
    return d.copy(fee = fee, detail = if (!d.detail.isNullOrEmpty()) "${d.detail} · fee $fee" else "fee $fee")
}

private fun describeEvent(item: ActivityItem, coins: Map<String, CoinInfo>, names: Names, mine: List<ByteArray>, advanced: Boolean, now: Long): Described {
    fun isMine(a: ByteArray) = mine.any { equal(it, a) }
    item.call?.let { return describeCall(item, it, coins, names, now) }
    val e = item.coin
    if (e != null) {
        val c = coins[item.contract.hex()]
        val coinParty = Party(c?.config?.name ?: "Coin", coin = true)
        return when (e) {
            is Coin.Event.Transfer -> {
                val zeroFrom = equal(e.from, Coin.ZERO_ACCOUNT)
                val zeroTo = equal(e.to, Coin.ZERO_ACCOUNT)
                val value = amount(c, e.amount)
                when {
                    zeroFrom -> Described(
                        title = "Received",
                        detail = "issued by the coin",
                        tone = Tone.Success,
                        icon = Icon.ArrowDown,
                        amount = "+$value",
                        amountTone = Tone.Success,
                        party = coinParty,
                        short = "Issued to ${nameOf(names, e.to)}",
                    )
                    zeroTo -> Described(
                        title = "Burned",
                        detail = "from ${nameOf(names, e.from)}",
                        tone = Tone.Neutral,
                        icon = Icon.Flame,
                        amount = "−$value",
                        party = coinParty,
                        short = "Burned from ${nameOf(names, e.from)}",
                    )
                    item.fee && isMine(e.from) -> Described("Fee", "from ${nameOf(names, e.from)} to the miner", Tone.Neutral, Icon.Hammer, amount = "−$value")
                    isMine(e.to) && !isMine(e.from) -> Described(
                        title = "Received",
                        detail = "from ${nameOf(names, e.from)}",
                        tone = Tone.Success,
                        icon = Icon.ArrowDown,
                        amount = "+$value",
                        amountTone = Tone.Success,
                        party = Party(nameOf(names, e.from), known = e.from.hex() in names),
                        short = "Received",
                    )
                    isMine(e.from) && !isMine(e.to) -> Described(
                        title = "Sent",
                        detail = "to ${nameOf(names, e.to)}${idAfterName(names, e.to)}",
                        tone = Tone.Primary,
                        icon = Icon.ArrowUp,
                        amount = "−$value",
                        party = Party(nameOf(names, e.to), known = e.to.hex() in names),
                        short = "Sent",
                    )
                    else -> Described("Moved", movedBetween(nameOf(names, e.from), nameOf(names, e.to)), Tone.Neutral, Icon.Swap, amount = value)
                }
            }
            is Coin.Event.Scheduled -> {
                val tip = if (e.tip.signum() > 0) " · run fee ${amount(c, e.tip)}" else ""
                val target = when (val effect = e.effect) {
                    is Coin.Effect.Transfer -> effect.to
                    is Coin.Effect.Mint -> effect.to
                    else -> null
                }
                val to = target?.let { Party(nameOf(names, it), known = it.hex() in names) }
                val planned = (e.effect as? Coin.Effect.Transfer)?.let { amount(c, it.amount) }
                val effect = describeEffect(e.effect, c, names)
                when (item.settled) {
                    Settled.Executed -> Described(
                        title = "Scheduled",
                        detail = "$effect · ran$tip",
                        tone = Tone.Neutral,
                        icon = Icon.CheckmarkCircle,
                        party = to,
                        short = "Scheduled · ran",
                        planned = planned,
                    )
                    Settled.Cancelled -> Described(
                        title = "Scheduled",
                        detail = "$effect · cancelled",
                        tone = Tone.Neutral,
                        icon = Icon.CloseCircle,
                        party = to,
                        short = "Scheduled · cancelled",
                        planned = planned,
                    )
                    null -> Described(
                        title = "Scheduled",
                        detail = "$effect · runs ${relativeTime(e.executeAt, now)}$tip",
                        tone = Tone.Warning,
                        icon = Icon.Time,
                        party = to,
                        short = "Scheduled · runs ${relativeTime(e.executeAt, now)}",
                        planned = planned,
                    )
                }
            }
            is Coin.Event.Executed ->
                Described("Ran", item.plan?.let { describeEffect(it, c, names) } ?: "a scheduled action of ${nameOf(names, e.account)}", Tone.Success, Icon.Play)
            is Coin.Event.Cancelled ->
                Described("Cancelled", item.plan?.let { describeEffect(it, c, names) } ?: "a scheduled action of ${nameOf(names, e.account)}", Tone.Neutral, Icon.CloseCircle)
            is Coin.Event.ProducerPayment -> {
                val n = item.count
                val blocks = if (n != null && n > 1) " · ${count(n.toLong(), "payment")}" else ""
                if (isMine(e.payee)) {
                    Described("Mining fees", "to ${nameOf(names, e.payee)}$blocks", Tone.Success, Icon.Hammer, amount = "+${amount(c, e.amount)}", amountTone = Tone.Success)
                } else {
                    Described("Paid to the miner", "${nameOf(names, e.payee)}$blocks", Tone.Neutral, Icon.Hammer, amount = amount(c, e.amount))
                }
            }
            is Coin.Event.Minter -> Described("Minter changed", nameOf(names, e.minter), Tone.Neutral, Icon.Key)
        }
    }
    return when (val ev = requireNotNull(item.identity) { "an activity item without an event" }) {
        is Id.Event.Created -> Described("Identity created", nameOf(names, ev.id), Tone.Accent, Icon.Fingerprint)
        is Id.Event.ChangeProposed -> Described(
            title = "Change #${ev.changeNo} proposed",
            detail = "${ev.operations.joinToString("; ") { describeOperation(it, names, advanced) }} — applies ${relativeTime(ev.effectiveAt, now)}",
            tone = Tone.Warning,
            icon = Icon.Hourglass,
        )
        is Id.Event.ChangeCancelled -> Described("Change #${ev.changeNo} cancelled", tone = Tone.Neutral, icon = Icon.CloseCircle)
        is Id.Event.ChangeApplied -> Described("Change #${ev.changeNo} applied", tone = Tone.Accent, icon = Icon.CheckmarkCircle)
        is Id.Event.RecoveryApproved -> Described(
            title = "Recovery of ${nameOf(names, ev.id)} approved",
            detail = "by ${nameOf(names, ev.guardian)} · ${ev.approvals} approval${if (ev.approvals == 1L) "" else "s"}" +
                (if (ev.reachedAt > 0) ", threshold reached: pending changes voided" else "") +
                " · installs ${describeKeys(ev.keys)}",
            tone = Tone.Warning,
            icon = Icon.ShieldCheckmark,
        )
        is Id.Event.RecoveryWithdrawn -> Described(
            title = "Recovery approval for ${nameOf(names, ev.id)} withdrawn",
            detail = "by ${nameOf(names, ev.guardian)} · ${ev.approvals} approval${if (ev.approvals == 1L) "" else "s"} left" +
                if (ev.reachedAt > 0) ", threshold still reached" else "",
            tone = Tone.Neutral,
            icon = Icon.Shield,
        )
        is Id.Event.Recovered -> Described("Identity recovered", "keys replaced", Tone.Accent, Icon.RefreshCircle)
    }
}

/**
 * A program's call for one of the user's accounts, by the program's ID: the
 * program has no name Ting ID could trust. What the call burned or paid is its
 * amount.
 */
private fun describeCall(item: ActivityItem, e: CallEvent, coins: Map<String, CoinInfo>, names: Names, now: Long): Described {
    val where = "from ${nameOf(names, e.account)} · program ${shortId(item.contract)}"
    val paid = item.paid.takeIf { it.isNotEmpty() }?.joinToString(" + ") { amount(coins[it.contract.hex()], it.amount) }?.let { "−$it" }
    return when (e) {
        is CallEvent.Executed ->
            if (item.run) Described("Program call ran", where, Tone.Success, Icon.Program, amount = paid)
            else Described("Program call", where, Tone.Primary, Icon.Program, amount = paid)
        is CallEvent.Scheduled -> when (item.settled) {
            Settled.Executed -> Described("Program call scheduled", "$where · ran", Tone.Neutral, Icon.Program, amount = paid)
            Settled.Cancelled -> Described("Program call scheduled", "$where · cancelled", Tone.Neutral, Icon.Program, amount = paid)
            null -> Described("Program call scheduled", "$where · runs ${relativeTime(e.executeAt, now)}", Tone.Warning, Icon.Program, amount = paid)
        }
        is CallEvent.Cancelled -> Described("Program call cancelled", where, Tone.Neutral, Icon.Program)
    }
}

// ---- Signing requests of sites -----------------------------------------------

/** A site as the app names it: the host, or the whole origin of one in development. */
fun siteName(origin: String): String = if (origin.startsWith("https://")) origin.substring("https://".length) else origin

/** Past this, a sign-in or grant gets a warning that it lasts long. */
private const val LONG = 90 * 86_400L

/** A date and clock time in the phone's language and time zone, like `Oct 23, 2026, 3:04 PM`. */
fun formatTime(at: Long): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(at))

data class DescribedRequest(
    val icon: Icon,
    /** What signing does, in the app's words; the site is named apart, in the screen's header. */
    val headline: String,
    val detail: String?,
    val warning: String?,
    /** The confirming button, named for what it does. */
    val action: String,
)

/**
 * What a site's request does when `signer` signs it, in the app's own words,
 * never the site's. `now` is in seconds; `time` writes a date and time.
 */
fun describeSignRequest(r: Offchain.Request, signer: String, now: Long = nowSeconds(), time: (Long) -> String = ::formatTime): DescribedRequest {
    /** Until when it lasts, in words, and a warning when that is over or far off. */
    fun lasting(until: Long): Pair<String, String?> {
        val left = until - now
        if (left <= 0) return "already over" to "This has already run out; the site may not accept it."
        return "${time(until)} (${relativeTime(until, now)})" to (if (left > LONG) "This lasts ${relativeDuration(left)}." else null)
    }
    return when (r) {
        is Offchain.Request.SignIn -> {
            val (until, warning) = lasting(r.sessionUntil)
            DescribedRequest(
                icon = Icon.LogIn,
                headline = "Sign in as $signer",
                detail = if (r.key != null) "The browser that asked stays signed in until $until." else "Signed in until $until.",
                warning = warning,
                action = "Sign in",
            )
        }
        is Offchain.Request.Grant -> {
            val (until, warning) = lasting(r.until)
            DescribedRequest(
                icon = Icon.Key,
                headline = "Give access as $signer",
                detail = "Until $until${if (r.key != null) ", through a key the site holds" else ""}.",
                warning = warning,
                action = "Allow",
            )
        }
        is Offchain.Request.Statement -> DescribedRequest(
            icon = Icon.Document,
            headline = "Sign a statement as $signer",
            detail = if (r.attachment != null) "It covers the attached file; open it before you sign." else null,
            warning = null,
            action = "Sign",
        )
    }
}

// ---- Submissions waiting for a block ------------------------------------------

/** A line about a submission: what it says, and how it looks. */
data class WatchLine(val detail: String, val tone: Tone, val icon: Icon)

/** Where a submitted transaction stands, in a line under its summary. `now` is in seconds; `time` writes a date and time. */
fun describeWatch(watch: Watch, sentAt: Long, now: Long = nowSeconds(), time: (Long) -> String = ::formatTime): WatchLine {
    val sent = "sent ${relativeTime(sentAt, now)}"
    return when (watch) {
        Watch.Checking -> WatchLine("Asking the node · $sent", Tone.Neutral, Icon.Time)
        Watch.Waiting -> WatchLine("Waiting for a block · $sent", Tone.Warning, Icon.Time)
        is Watch.Confirming -> WatchLine("In block ${watch.height}; done once a few more blocks follow", Tone.Neutral, Icon.Checkmark)
        is Watch.Scheduled -> WatchLine(
            "Held by the node until ${time(watch.notBefore)}${watch.lastResult?.takeIf { it.isNotEmpty() }?.let { " · the node’s last try: “$it”" } ?: ""}",
            Tone.Warning,
            Icon.Time,
        )
        Watch.Resent -> WatchLine("The node had lost it; sent again · $sent", Tone.Warning, Icon.Refresh)
        Watch.Lost -> WatchLine("The node no longer knows it, and this device has no copy to send again", Tone.Danger, Icon.AlertCircle)
        is Watch.Failed -> WatchLine("Not done: ${watch.reason}", Tone.Danger, Icon.CloseCircle)
    }
}

/** The one message while no node answers: screens keep showing what the nodes last reported. */
fun offlineMessage(height: Long?): String =
    if (height != null) "Can't reach the node. Showing what it last reported, at block $height." else "Can't reach the node. Check its address in Settings."

/** The general message about the nodes, if any: none answers, or their answers did not check out. `now` is in seconds. */
fun nodeNotice(height: Long?, error: String?, notice: PoolNotice?, now: Long = nowSeconds()): String? = when {
    !error.isNullOrEmpty() -> offlineMessage(height)
    notice is PoolNotice.Distrusted -> "A node gave answers that don't check out, so Ting ID stopped using it. See Settings → Nodes."
    notice is PoolNotice.Split -> "Your nodes follow different chains, and Ting ID can't tell which is right. Check them in Settings → Nodes."
    notice is PoolNotice.Old -> "The newest block your nodes have is from ${relativeTime(notice.since, now)}: they may be behind. Check them in Settings → Nodes."
    else -> null
}

/** What an identity of another chain than the nodes' shows instead of its accounts. */
const val OTHER_CHAIN_USER =
    "This identity belongs to another Ting network, so it has no accounts here. Choose another user, or add that network’s nodes in Settings."

/** The (i) text of Settings → Nodes. */
const val NODES_INFO =
    "Ting ID reads from the node furthest ahead and moves on to the next when one stops answering; transactions go to every node. It checks every balance and record against the block it comes from, and with two or more nodes, that the others have that block too and agree on each coin’s settings. Plain http is only for a node on this device or your home network."

enum class NodeTone { Success, Muted, Warning, Danger }

/** One node's line in Settings → Nodes. */
data class NodeLine(val text: String, val tone: NodeTone)

/** One node's line in Settings → Nodes, and its tone; `bestHeight` is the height of the node in use. */
fun describeNode(h: NodeHealth, bestHeight: Long?): NodeLine {
    val status = h.status
    val block = if (status != null) " · block ${status.height}" else ""
    return when (h.state) {
        NodeState.Ok ->
            if (!h.best && status != null && bestHeight != null && bestHeight - status.height > CONFIRMATIONS) NodeLine("Catching up$block", NodeTone.Muted)
            else NodeLine("${if (h.best) "In use" else "Answering"}$block", NodeTone.Success)
        NodeState.Unknown -> NodeLine("Checking…", NodeTone.Muted)
        NodeState.Down -> NodeLine("Not answering", NodeTone.Warning)
        NodeState.Outdated -> NodeLine("Needs an update: Ting ID cannot check its answers", NodeTone.Warning)
        NodeState.OtherChain -> NodeLine("On another chain$block", NodeTone.Warning)
        NodeState.Distrusted -> NodeLine("Not used: ${h.problem ?: "its answers did not check out"}", NodeTone.Danger)
        NodeState.Insecure -> NodeLine("Not used: plain http is only for a node on this device or your home network", NodeTone.Danger)
    }
}
