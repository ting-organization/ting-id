// Scheduled actions of the user's accounts: what is waiting to run, who runs
// it, and the buttons to run, speed up, queue or cancel it, as rows and as a
// whole section; a cancel or a speed-up shows the fee it pays in its
// confirmation.
package app.tingid.ui.money

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.tingid.chain.Coin
import app.tingid.chain.formatAmount
import app.tingid.chain.nowSeconds
import app.tingid.chain.relativeTime
import app.tingid.state.Acting
import app.tingid.state.RunStatus
import app.tingid.state.ScheduledAction
import app.tingid.state.ScheduledActions
import app.tingid.state.WorkspaceState
import app.tingid.state.runKey
import app.tingid.state.runRefOf
import app.tingid.state.runsPaidFor
import app.tingid.state.scheduledActionKey
import app.tingid.text.Names
import app.tingid.text.describeEffect
import app.tingid.text.nameOf
import app.tingid.ui.Banner
import app.tingid.ui.BannerTone
import app.tingid.ui.Button
import app.tingid.ui.ButtonKind
import app.tingid.ui.Card
import app.tingid.ui.CardTone
import app.tingid.ui.ConfirmOptions
import app.tingid.ui.ConfirmSheet
import app.tingid.ui.IconCircle
import app.tingid.ui.Info
import app.tingid.ui.LocalNav
import app.tingid.ui.LocalServices
import app.tingid.ui.Spacing
import app.tingid.ui.TxRoute
import app.tingid.ui.initialsOf
import app.tingid.ui.manropeStyle
import app.tingid.ui.sentence
import app.tingid.ui.t
import kotlinx.coroutines.launch

/** A scheduled action of one of the user's accounts, as the lists show it; `scheduledOf` (state/Scheduled) lists them. */
typealias ScheduledEntry = ScheduledAction

/** The key of a scheduled action in lists and in what is busy: `<coin>-<slot>-<sequence>`. */
fun scheduledKey(e: ScheduledEntry): String = scheduledActionKey(e)

/** What the caption says about who runs the action. */
fun runNote(status: RunStatus?, due: Boolean): String = when (status) {
    RunStatus.Queued -> if (due) "the node is about to run it" else "the node runs it when due"
    RunStatus.Pending -> "run awaiting a block"
    RunStatus.Ran -> "ran"
    null -> if (due) "due now" else "not set to run yet"
}

/** A scheduled action as a title: what it moves and to whom. */
private fun effectTitle(e: ScheduledEntry, names: Names): String {
    val effect = e.record.effect
    if (effect is Coin.Effect.Transfer) return "${formatAmount(effect.amount, e.coin.config.decimals)} ${e.coin.config.symbol} to ${nameOf(names, effect.to)}"
    return describeEffect(effect, e.coin, names)
}

/**
 * Scheduled actions as rows, each with Cancel, and Run now or Run when due
 * while nothing runs it yet; one that is due and has not run also has Speed
 * up, until a batch from this device pays for its run. `runs`: run status by
 * [runKey] (Chain.runStatuses of [runRefOf] of each); `paid`: the keys of
 * those whose run a batch pays for ([runsPaidFor]); `busy`: what
 * [ScheduledActions.busy] says is under way.
 */
@Composable
fun ScheduledRows(
    entries: List<ScheduledEntry>,
    names: Names,
    runs: Map<String, RunStatus?>,
    paid: Set<String>,
    busy: String?,
    onCancel: (ScheduledEntry) -> Unit,
    onRun: (ScheduledEntry) -> Unit,
    onSpeedUp: (ScheduledEntry) -> Unit,
    onQueue: (ScheduledEntry) -> Unit,
) {
    val p = t
    entries.forEachIndexed { i, e ->
        val due = e.record.executeAt <= nowSeconds()
        val key = scheduledKey(e)
        val status = runs[runKey(runRefOf(e))]
        val handled = status == RunStatus.Queued || status == RunStatus.Pending
        // Due and not run: a fee can get its run into a block, unpaid or still waiting for room.
        val speedable = due && status != RunStatus.Ran && key !in paid
        val who = when (val effect = e.record.effect) {
            is Coin.Effect.Transfer -> nameOf(names, effect.to)
            is Coin.Effect.Mint -> nameOf(names, effect.to)
            else -> null
        }
        if (i > 0) Hairline(p.warningLine)
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = Spacing.lg), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (who != null) {
                    Box(Modifier.size(38.dp).clip(CircleShape).background(p.warningLine), contentAlignment = Alignment.Center) {
                        Text(initialsOf(who), style = manropeStyle(14f, FontWeight.SemiBold), color = p.warning)
                    }
                } else {
                    IconCircle(if (due) Icons.Outlined.PlayArrow else Icons.Outlined.Schedule, p.warning, p.warningLine, 38.dp)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(effectTitle(e, names), style = manropeStyle(14.5f, FontWeight.Medium), color = p.text)
                    val tip = e.record.tip
                    ToneCaption(
                        "From ${e.view.account.label} · ${if (due) "" else "${relativeTime(e.record.executeAt)} · "}${runNote(status, due)}" +
                            (if (key in paid) " · sped up" else "") +
                            if (tip.signum() > 0) " · run fee ${formatAmount(tip, e.coin.config.decimals)} ${e.coin.config.symbol}" else "",
                        p.warning,
                    )
                }
                Button("Cancel", onClick = { onCancel(e) }, kind = ButtonKind.Danger, small = true, loading = busy == "cancel-$key")
            }
            if (due && (!handled || speedable)) {
                Row(Modifier.padding(start = 50.dp), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    if (!handled) {
                        Button("Run now", onClick = { onRun(e) }, kind = ButtonKind.Secondary, small = true, icon = Icons.Outlined.PlayArrow, loading = busy == "run-$key")
                    }
                    if (speedable) {
                        Button("Speed up", onClick = { onSpeedUp(e) }, kind = ButtonKind.Secondary, small = true, icon = Icons.Outlined.Bolt, loading = busy == "speed-$key")
                    }
                }
            } else if (!due && !handled) {
                Row(Modifier.padding(start = 50.dp)) {
                    Button("Run when due", onClick = { onQueue(e) }, kind = ButtonKind.Secondary, small = true, icon = Icons.Outlined.Schedule, loading = busy == "queue-$key")
                }
            }
        }
    }
}

/**
 * Cancel, run and queue for scheduled actions (state/Scheduled) in the
 * workspace `w`, paying `fee` for cancels; `opened` is told the transaction
 * to show, while the screen is there. What was begun finishes even when the
 * screen goes. Its `busy` and `error` are flows to collect.
 */
@Composable
fun rememberScheduledActions(w: WorkspaceState, fee: FeeChoice, opened: (txId: String) -> Unit): ScheduledActions {
    val services = LocalServices.current
    val ui = rememberCoroutineScope()
    val currentW by rememberUpdatedState(w)
    val currentFee by rememberUpdatedState(fee)
    val currentOpened by rememberUpdatedState(opened)
    return remember(services) {
        ScheduledActions(services.chain, services.scope, { currentW }, { currentFee }, opened = { id -> ui.launch { currentOpened(id) } })
    }
}

/** What a cancel being confirmed takes: its entries, its label in `busy`, and the question. */
private class Asking(val entries: List<ScheduledEntry>, val label: String, val options: ConfirmOptions)

/**
 * The scheduled actions `entries` with Cancel, Run now, Speed up and Run when
 * due, Cancel all, and the fee the cancels pay. `acting`: the coin and account
 * of an account screen; none on the wallet, which lists every account's.
 * `info`: the (i) text beside the heading.
 */
@Composable
fun ScheduledSection(w: WorkspaceState, entries: List<ScheduledEntry>, acting: Acting?, info: String? = null) {
    val p = t
    val services = LocalServices.current
    val nav = LocalNav.current
    val fee = rememberFee(acting)
    val refs = entries.map(::runRefOf)
    val runs by remember(refs.map(::runKey)) { services.chain.runStatuses(refs) }.collectAsState(initial = emptyMap())
    val actions = rememberScheduledActions(w, fee) { id -> nav.navigate(TxRoute(id)) }
    val busy by actions.busy.collectAsState()
    val error by actions.error.collectAsState()
    val outbox = services.store.data.collectAsState().value.outbox
    val paid = w.info?.status?.chainId?.let { runsPaidFor(it, entries, outbox) } ?: emptySet()
    // The cancel or speed-up being confirmed: its sheet shows the fee, which can be changed there.
    var asking by remember { mutableStateOf<Asking?>(null) }
    var speeding by remember { mutableStateOf<ScheduledEntry?>(null) }
    if (entries.isEmpty()) return
    val cancelOne = { e: ScheduledEntry ->
        asking = Asking(
            listOf(e),
            "cancel-${scheduledKey(e)}",
            ConfirmOptions(
                title = "Cancel this scheduled action?",
                body = "${describeEffect(e.record.effect, e.coin, w.names)}, from ${e.view.account.label}. It will not run.",
                confirm = "Cancel action",
                back = "Keep it",
                danger = true,
            ),
        )
    }
    val cancelAll = {
        asking = Asking(
            entries,
            "cancel-all",
            ConfirmOptions(title = "Cancel all ${entries.size} scheduled actions?", body = "None of them will run.", confirm = "Cancel all", back = "Keep them", danger = true),
        )
    }
    error?.let { Banner(sentence(it), BannerTone.Danger) }
    Card(tone = CardTone.Warning, padded = false) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(start = Spacing.lg, end = Spacing.lg, top = 14.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Icon(Icons.Outlined.Schedule, null, tint = p.warning, modifier = Modifier.size(16.dp))
                Text("${entries.size} scheduled ${if (entries.size == 1) "action" else "actions"}", style = manropeStyle(13.5f, FontWeight.SemiBold), color = p.warning)
                if (info != null) Info("Scheduled", info)
                Spacer(Modifier.weight(1f))
                if (entries.size > 1 && w.user != null) {
                    Text(
                        if (busy == "cancel-all") "Cancelling…" else "Cancel all",
                        style = manropeStyle(13.5f, FontWeight.Medium),
                        color = p.danger,
                        modifier = Modifier.clickable(role = Role.Button) { if (busy == null) cancelAll() }.padding(4.dp),
                    )
                }
            }
            ScheduledRows(
                entries,
                w.names,
                runs,
                paid,
                busy,
                onCancel = cancelOne,
                onRun = actions::run,
                onSpeedUp = { e -> speeding = e },
                onQueue = actions::queue,
            )
        }
    }
    asking?.let { a ->
        ConfirmSheet(
            a.options,
            onAnswer = { yes ->
                if (yes) actions.cancel(a.entries, a.label)
                asking = null
            },
            ready = fee.problem == null,
        ) {
            Column(Modifier.fillMaxWidth()) {
                Hairline()
                Box(Modifier.padding(vertical = 6.dp)) { FeeRow(fee) }
                Hairline()
            }
        }
    }
    speeding?.let { e ->
        // From an instant account, in a batch beside the run, which carries no fee of its own.
        val speedFee = rememberFee(null)
        ConfirmSheet(
            ConfirmOptions(
                title = "Speed up this run?",
                body = "${describeEffect(e.record.effect, e.coin, w.names)}, from ${e.view.account.label}. A fee goes to the miner that runs it, paid only if the run gets into a block.",
                confirm = "Speed up",
            ),
            onAnswer = { yes ->
                if (yes) actions.speedUp(e, speedFee)
                speeding = null
            },
            ready = speedFee.problem == null && speedFee.fee.signum() > 0,
        ) {
            Column(Modifier.fillMaxWidth()) {
                Hairline()
                Box(Modifier.padding(vertical = 6.dp)) { FeeRow(speedFee) }
                Hairline()
            }
        }
    }
}
