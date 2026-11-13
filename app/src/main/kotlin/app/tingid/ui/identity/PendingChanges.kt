// The changes waiting to apply to the active user's identity, each to cancel,
// and to apply once due. The wallet shows them as Identity does: a change no
// one here made is how a stolen key starts a takeover, and it can only be
// stopped while it waits. A change that can no longer apply says why, and is
// offered only to cancel.
package app.tingid.ui.identity

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.tingid.chain.Id
import app.tingid.chain.hex
import app.tingid.chain.nowSeconds
import app.tingid.chain.relativeTime
import app.tingid.state.ApplyBlocked
import app.tingid.state.CANCEL_CHUNK
import app.tingid.state.ChangeState
import app.tingid.state.IdentityInfo
import app.tingid.state.SubmittedTx
import app.tingid.state.User
import app.tingid.state.WorkspaceState
import app.tingid.state.blockedApply
import app.tingid.state.blockedWords
import app.tingid.state.changeStateOf
import app.tingid.state.guardiansToRead
import app.tingid.state.linkedDeviceKeys
import app.tingid.state.readChangeState
import app.tingid.state.sent
import app.tingid.text.describeOperation
import app.tingid.text.nameOf
import app.tingid.text.relativeDuration
import app.tingid.ui.Button
import app.tingid.ui.ButtonKind
import app.tingid.ui.Card
import app.tingid.ui.CardTone
import app.tingid.ui.ConfirmOptions
import app.tingid.ui.ConfirmSheet
import app.tingid.ui.IconCircle
import app.tingid.ui.Info
import app.tingid.ui.LocalActivity
import app.tingid.ui.LocalNav
import app.tingid.ui.LocalServices
import app.tingid.ui.Spacing
import app.tingid.ui.TxRoute
import app.tingid.ui.Type
import app.tingid.ui.confirmOwner
import app.tingid.ui.manropeStyle
import app.tingid.ui.money.FeeChoice
import app.tingid.ui.money.FeeRow
import app.tingid.ui.money.Hairline
import app.tingid.ui.money.rememberFee
import app.tingid.ui.t
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** A cancel or apply being confirmed: its question, and what runs with the owner's check once confirmed. */
private class ChangeAsking(val options: ConfirmOptions, val run: (check: suspend () -> Boolean) -> Unit)

/** What a cancel of `changeNos` says it did. */
private fun cancelSummary(changeNos: List<Long>): String = if (changeNos.size == 1) "Cancel change #${changeNos[0]}" else "Cancel ${changeNos.size} changes"

/** The state changes are checked against, null until it is read; `retrying` once a read went unanswered and another is to follow. */
class ChangeRead(val state: ChangeState?, val retrying: Boolean)

/**
 * The state the identity's pending changes, and `ops`, are checked against.
 * Read again when the identity or its pending changes change; with no
 * guardian to read, known at once. A read no node answers is asked again, a
 * little longer apart each time (2 s, doubling to 30 s), as long as the
 * screen stays.
 */
@Composable
fun rememberChangeState(w: WorkspaceState, ops: List<Id.Operation> = emptyList()): ChangeRead {
    val identity = w.identity ?: return ChangeRead(null, false)
    val named = guardiansToRead(identity, ops)
    var retrying by remember { mutableStateOf(false) }
    // What the links are read for: the identity as read, its pending changes and the guardians they name.
    // Links read for an earlier state are no answer for this one, as a change that applied may have moved them.
    val readFor = listOf(identity.id.hex(), identity.core, identity.pending.map { it.changeNo }, named.map { it.hex() })
    val read by produceState<Pair<List<Any>, Map<String, Boolean>>?>(null, readFor, w.node) {
        retrying = false
        var pause = 2_000L
        while (true) {
            try {
                value = readFor to readChangeState(w.node, identity, ops).guardians
                retrying = false
                return@produceState
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                retrying = true
                delay(pause)
                pause = minOf(pause * 2, 30_000L)
            }
        }
    }
    // With no guardian to read, the state is known at once.
    val links = if (named.isEmpty()) emptyMap() else read?.takeIf { it.first == readFor }?.second
    val known = links?.let { l -> changeStateOf(identity, named, named.filter { l[it.hex()] == true }) }
    return ChangeRead(known, retrying && known == null)
}

/**
 * Why pending changes cannot apply, by change number, as `state` has the
 * identity now; a change missing from it can.
 */
private fun blockedChanges(id: IdentityInfo, state: ChangeState?): Map<Long, ApplyBlocked> =
    if (state == null) emptyMap() else id.pending.mapNotNull { c -> blockedApply(state, id.pending, c)?.let { c.changeNo to it } }.toMap()

/**
 * The active user's pending identity changes: each with Cancel, and with
 * Apply once due and able to apply. Nothing while there are none. `sending`
 * shows the screen what is being sent, and why it was not. On the wallet
 * (`onWallet`) the card says the changes are the identity's.
 */
@Composable
fun PendingChanges(w: WorkspaceState, user: User, sending: Sending, onWallet: Boolean = false) {
    val services = LocalServices.current
    val activity = LocalActivity.current
    val nav = LocalNav.current
    val id = w.identity ?: return
    val info = w.info ?: return
    // One fee for cancelling and applying pending changes; registry operations pay nothing themselves.
    val fee = rememberFee(null)
    val state = rememberChangeState(w).state
    // The cancel or apply being confirmed: its sheet shows the fee, which can be changed there.
    var asking by remember { mutableStateOf<ChangeAsking?>(null) }
    if (id.pending.isEmpty()) return

    val linked = linkedDeviceKeys(info.canonical, id, w.keys)
    val onThisDevice = { keyId: Long -> linked.any { it.keyId == keyId } }
    fun operationsOf(changeNo: Long): String? =
        id.pending.firstOrNull { it.changeNo == changeNo }?.record?.operations?.joinToString("; ") { describeOperation(it, w.names, w.advanced, onThisDevice) }

    fun act(label: String, summary: String, check: suspend () -> Boolean, f: suspend () -> String) =
        sending.send(
            label,
            check = check,
            submit = {
                val s = sent(f)
                services.store.addSubmitted(SubmittedTx(id = s.txId, at = nowSeconds(), summary = summary, userId = user.id))
                s
            },
            opened = { s -> nav.navigate(TxRoute(s.txId, unsure = s.unsure)) },
        )

    // One signature cancels many pending changes; a long list goes in several
    // cancels of CANCEL_CHUNK, each within one transaction's budget, the last one shown.
    fun cancel(label: String, changeNos: List<Long>, check: suspend () -> Boolean) {
        val groups = changeNos.chunked(CANCEL_CHUNK)
        val last = groups.lastOrNull() ?: emptyList()
        val chosen = fee
        act(label, cancelSummary(last), check) {
            val signer = services.registry.identitySigner(user, info.canonical, w.keys, id)
            for (g in groups.dropLast(1)) {
                val s = sent { services.registry.cancelChanges(info.status.chainId, id, signer, g, chosen.resolve()) }
                services.store.addSubmitted(SubmittedTx(id = s.txId, at = nowSeconds(), summary = cancelSummary(g), userId = user.id))
            }
            services.registry.cancelChanges(info.status.chainId, id, signer, last, chosen.resolve())
        }
    }

    fun apply(changeNo: Long, check: suspend () -> Boolean) {
        val chosen = fee
        act("apply-$changeNo", "Apply change #$changeNo", check) {
            services.registry.applyChange(info.status.chainId, id, changeNo, chosen.resolve())
        }
    }

    fun confirmCancel(changeNos: List<Long>, label: String) {
        val one = if (changeNos.size == 1) operationsOf(changeNos[0])?.takeIf { it.isNotEmpty() } else null
        asking = ChangeAsking(
            ConfirmOptions(
                title = if (changeNos.size == 1) "Cancel this change?" else "Cancel all ${changeNos.size} changes?",
                body = if (one != null) "$one. It will not apply." else "None of them will apply.",
                confirm = if (changeNos.size == 1) "Cancel change" else "Cancel all",
                back = if (changeNos.size == 1) "Keep it" else "Keep them",
                danger = true,
            ),
        ) { check -> cancel(label, changeNos, check) }
    }
    fun confirmApply(changeNo: Long) {
        val ops = operationsOf(changeNo)?.takeIf { it.isNotEmpty() }
        asking = ChangeAsking(ConfirmOptions(title = "Apply change #$changeNo?", body = ops?.let { "$it." }, confirm = "Apply change", back = "Not now")) { check -> apply(changeNo, check) }
    }

    PendingSection(
        w,
        id,
        state != null,
        blockedChanges(id, state),
        canAct = linked.isNotEmpty(),
        busy = sending.busy,
        onWallet = onWallet,
        onThisDevice = onThisDevice,
        confirmCancel = ::confirmCancel,
        confirmApply = ::confirmApply,
    )
    asking?.let { a ->
        ConfirmSheet(
            a.options,
            onAnswer = { yes ->
                asking = null
                // The question without its question mark names the phone's check.
                val title = a.options.title.removeSuffix("?")
                if (yes) a.run { confirmOwner(activity, services, title) }
            },
            ready = fee.problem == null,
        ) { ConfirmFee(fee) }
    }
}

/**
 * The changes waiting to apply, each to cancel, and to apply once due. Apply
 * shows once `checked` says whether a change can apply; one in `blocked`
 * cannot, now or ever, and says why instead.
 */
@Composable
private fun PendingSection(
    w: WorkspaceState,
    id: IdentityInfo,
    checked: Boolean,
    blocked: Map<Long, ApplyBlocked>,
    canAct: Boolean,
    busy: String?,
    onWallet: Boolean,
    onThisDevice: (Long) -> Boolean,
    confirmCancel: (List<Long>, String) -> Unit,
    confirmApply: (Long) -> Unit,
) {
    val p = t
    val n = id.pending.size
    Card(tone = CardTone.Warning, padded = false) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(start = Spacing.lg, end = Spacing.lg, top = 14.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Icon(Icons.Outlined.HourglassEmpty, null, tint = p.warning, modifier = Modifier.size(16.dp))
                Text(
                    "$n pending ${if (onWallet) "identity " else ""}${if (n == 1) "change" else "changes"}",
                    style = manropeStyle(13.5f, FontWeight.SemiBold),
                    color = p.warning,
                )
                Info(
                    "Pending changes",
                    "A change to your identity waits ${relativeDuration(id.core.changeDelay)}, and any of your devices can cancel it meanwhile: " +
                        "cancel any you did not make. It is void once the device that proposed it is removed, or when guardians approve a recovery.",
                )
                Spacer(Modifier.weight(1f))
                if (canAct && n > 1) {
                    Text(
                        if (busy == "cancel-all") "Cancelling…" else "Cancel all",
                        style = manropeStyle(13.5f, FontWeight.Medium),
                        color = p.danger,
                        modifier = Modifier.clickable(role = Role.Button) { if (busy == null) confirmCancel(id.pending.map { it.changeNo }, "cancel-all") }.padding(4.dp),
                    )
                }
            }
            id.pending.forEachIndexed { i, change ->
                val changeNo = change.changeNo
                val record = change.record
                val due = record.effectiveAt <= nowSeconds()
                val why = blocked[changeNo]
                if (i > 0) Hairline(p.warningLine)
                Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        IconCircle(
                            when {
                                why != null -> Icons.Outlined.WarningAmber
                                due -> Icons.Outlined.CheckCircle
                                else -> Icons.Outlined.Schedule
                            },
                            p.warning,
                            p.warningLine,
                            38.dp,
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(
                                record.operations.joinToString("; ") { describeOperation(it, w.names, w.advanced, onThisDevice) },
                                style = manropeStyle(14.5f, FontWeight.Medium),
                                color = p.text,
                            )
                            // Who proposed it: a change none of this owner's devices made stands out.
                            val by = when {
                                w.advanced -> "by key ${record.proposer}"
                                onThisDevice(record.proposer) -> "by this device"
                                else -> "by Device ${record.proposer + 1}"
                            }
                            Text(
                                "#$changeNo · $by · " + when {
                                    why != null -> if (why.forGood) "cannot apply" else "cannot apply yet"
                                    due -> "ready to apply"
                                    else -> "applies ${relativeTime(record.effectiveAt)}"
                                },
                                style = Type.caption,
                                color = if (why != null) p.danger else p.warning,
                            )
                        }
                        if (canAct) {
                            Button("Cancel", onClick = { confirmCancel(listOf(changeNo), "cancel-$changeNo") }, kind = ButtonKind.Danger, small = true, loading = busy == "cancel-$changeNo")
                        }
                    }
                    if (why != null) {
                        val words = blockedWords(why.why, waiting = false, advanced = w.advanced) { nameOf(w.names, it) }.replaceFirstChar { it.uppercase() }
                        Text(
                            if (why.forGood) {
                                "$words, so the registry refuses it.${if (canAct) " Cancel it." else ""}"
                            } else {
                                "$words: it can apply only once another pending change has."
                            },
                            Modifier.padding(start = 50.dp),
                            style = Type.caption,
                            color = p.danger,
                        )
                    } else if (due && checked) {
                        Row(Modifier.padding(start = 50.dp)) {
                            Button("Apply", onClick = { confirmApply(changeNo) }, kind = ButtonKind.Secondary, small = true, icon = Icons.Outlined.Check, loading = busy == "apply-$changeNo")
                        }
                    }
                }
            }
        }
    }
}

/** The fee in a confirmation, between hairlines. */
@Composable
private fun ConfirmFee(fee: FeeChoice) {
    Column(Modifier.fillMaxWidth()) {
        Hairline()
        Box(Modifier.padding(vertical = 6.dp)) { FeeRow(fee) }
        Hairline()
    }
}
