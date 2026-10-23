// Recovery of an identity by its guardians. "Recover mine": the link the
// guardians approve, which hands the identity to new keys, and the finish once
// they have and the delay has passed. "Help someone": a guardian checks the
// keys another's recovery link would install, and approves or withdraws.
package app.tingid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.tingid.chain.Id
import app.tingid.chain.Link
import app.tingid.chain.Links
import app.tingid.chain.equal
import app.tingid.chain.hex
import app.tingid.chain.isHex
import app.tingid.chain.nowSeconds
import app.tingid.chain.relativeTime
import app.tingid.chain.unhex
import app.tingid.state.Checked
import app.tingid.state.IdentityInfo
import app.tingid.state.ProposalStatus
import app.tingid.state.Standing
import app.tingid.state.SubmittedTx
import app.tingid.state.deviceKeyOf
import app.tingid.state.fetchIdentity
import app.tingid.state.linkedDeviceKeys
import app.tingid.state.policyLabel
import app.tingid.state.proposalStatus
import app.tingid.state.recoverLink
import app.tingid.state.sent
import app.tingid.state.userIdentity
import app.tingid.text.count
import app.tingid.text.keyFingerprint
import app.tingid.text.nameOf
import app.tingid.text.relativeDuration
import app.tingid.ui.Banner
import app.tingid.ui.BannerTone
import app.tingid.ui.Button
import app.tingid.ui.ButtonKind
import app.tingid.ui.Caption
import app.tingid.ui.Card
import app.tingid.ui.CardTone
import app.tingid.ui.ConfirmOptions
import app.tingid.ui.ConfirmSheet
import app.tingid.ui.Field
import app.tingid.ui.HoldWhileBusy
import app.tingid.ui.LinkShare
import app.tingid.ui.ListItem
import app.tingid.ui.LocalActivity
import app.tingid.ui.LocalNav
import app.tingid.ui.LocalServices
import app.tingid.ui.PhraseRoute
import app.tingid.ui.Pill
import app.tingid.ui.RecoveryRoute
import app.tingid.ui.Screen
import app.tingid.ui.Segmented
import app.tingid.ui.Spacing
import app.tingid.ui.Sub
import app.tingid.ui.Tone
import app.tingid.ui.TxRoute
import app.tingid.ui.Type
import app.tingid.ui.confirmOwner
import app.tingid.ui.identity.rememberSending
import app.tingid.ui.money.FeeRow
import app.tingid.ui.money.rememberFee
import app.tingid.ui.money.withFee
import app.tingid.ui.sentence
import app.tingid.ui.t
import app.tingid.vault.NotConfirmed
import app.tingid.vault.Owner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class RecoveryMode { Mine, Help }

/** The target and proposal last read, for `subject` (the target, key list and guardian, in hex). */
private class RecoveryLookup(val subject: String, val info: IdentityInfo?, val status: ProposalStatus?)

/** `route.mode`: "help" to approve another's recovery, else the user's own; `route.code`: a recovery link, scanned or pasted on the wallet. */
@Composable
fun RecoveryScreen(route: RecoveryRoute) {
    val services = LocalServices.current
    val activity = LocalActivity.current
    val nav = LocalNav.current
    val ui = rememberCoroutineScope()
    val p = t
    val w by services.workspace.state.collectAsState()
    val user = w.user
    val identity = w.identity
    val info = w.info
    val advanced = w.advanced
    // The key this device made for the user, or failing that one that already signs for it.
    val deviceKey = user?.let { u ->
        deviceKeyOf(u, w.keys) ?: if (identity != null && info != null) linkedDeviceKeys(info.canonical, identity, w.keys).firstOrNull()?.key else null
    }
    var mode by remember { mutableStateOf(if (route.mode == "help") RecoveryMode.Help else RecoveryMode.Mine) }
    var selectedKeys by remember { mutableStateOf(listOfNotNull(deviceKey?.id)) }
    var linkText by remember { mutableStateOf(route.code ?: "") }
    var lookup by remember { mutableStateOf<RecoveryLookup?>(null) }
    val sending = rememberSending()
    HoldWhileBusy(sending.busy != null)
    // Paid from the acting user's own instant account: the guardian's, when approving.
    val fee = rememberFee(null)
    val me = user?.let(::userIdentity)

    // "Mine": the identity's keys become the key of a new recovery phrase, as the
    // old words may be what was lost or stolen; in advanced mode, the keys picked here.
    val phraseKey = user?.let { u -> w.keys.firstOrNull { it.id == u.phraseKeyId } }
    val phraseListed = phraseKey != null && identity != null && info != null && linkedDeviceKeys(info.canonical, identity, listOf(phraseKey)).isNotEmpty()
    val recoveryKey = phraseKey?.takeIf { !phraseListed }
    val chosen = if (advanced) w.keys.filter { it.id in selectedKeys } else listOfNotNull(recoveryKey)
    var preparing by remember { mutableStateOf(false) }
    // New words, their key kept on this phone: written down first, then the recovery installs that key.
    fun prepare() {
        val u = user ?: return
        preparing = true
        sending.error = null
        ui.launch {
            try {
                services.keys.newUserPhrase(u, Owner.prompt(activity))
                nav.navigate(PhraseRoute(u.id))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // The person stepped back from the phone's check: nothing to say.
                if (!(e is NotConfirmed && e.message == "cancelled")) sending.error = e.message ?: "the words could not be kept"
            } finally {
                preparing = false
            }
        }
    }
    val myKeyList = info?.let { i -> chosen.map { Id.Key(i.canonical.policyFor(it.scheme), unhex(it.pub)) } }?.takeIf { it.isNotEmpty() }?.let { Id.encodeKeyList(it) }

    // "Help": the recovering identity and its new keys, from the link it sent.
    val link = recoverLink(linkText, info?.let { Links.netOf(it.status.chainId) })
    val request = (link as? Checked.Ok)?.value
    val target = request?.id ?: ""
    val targetId = if (mode == RecoveryMode.Mine) me?.id else if (isHex(target, 32)) unhex(target) else null
    val keyList = if (mode == RecoveryMode.Mine) myKeyList else request?.keys

    // A guardian helping someone also learns where it stands on the proposal.
    val guardianId = if (mode == RecoveryMode.Help) identity?.id else null
    // What is looked up, read again with each block; the last answer for it shows meanwhile.
    val subject = targetId?.let { "${it.hex()}:${keyList?.hex() ?: ""}:${guardianId?.hex() ?: ""}" }
    val lookupKey = if (info != null && subject != null) "${info.status.height}:$subject" else null
    val current = lookup?.takeIf { it.subject == subject }
    val targetInfo = current?.info
    val status = current?.status

    LaunchedEffect(w.node, lookupKey) {
        if (info == null || targetId == null || subject == null || lookupKey == null) return@LaunchedEffect
        val registry = info.canonical.registry
        try {
            val found = fetchIdentity(w.node, registry, targetId)
            val valid = keyList?.takeIf { Id.decodeKeyList(it) != null }
            val s = if (found != null && valid != null) proposalStatus(w.node, registry, targetId, found.core, valid, guardianId) else null
            lookup = RecoveryLookup(subject, found, s)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            sending.error = e.message ?: "the identity could not be read"
        }
    }

    // One action at a time; a paid one names its fee in what was sent.
    fun act(label: String, summary: String, paid: Boolean = true, check: (suspend () -> Boolean)? = null, f: suspend () -> String) {
        val chosenFee = fee
        sending.send(
            label,
            check = check,
            submit = {
                val s = sent(f)
                user?.let { services.store.addSubmitted(SubmittedTx(id = s.txId, at = nowSeconds(), summary = if (paid) withFee(summary, chosenFee) else summary, userId = it.id)) }
                s
            },
            opened = { s -> nav.navigate(TxRoute(s.txId, unsure = s.unsure)) },
        )
    }

    // An approval carries the key list (the registry derives the proposal from it); a withdrawal names the proposal,
    // this one or another this guardian approves, since a guardian approves one proposal at a time.
    fun approval(approve: Boolean, other: ByteArray? = null, check: (suspend () -> Boolean)? = null) {
        val label = if (approve) "approve" else if (other != null) "withdraw-other" else "withdraw"
        val whom = if (isHex(target, 32)) nameOf(w.names, unhex(target)) else "an identity"
        val now = w
        val chosenFee = fee
        act(label, "${if (approve) "Approve" else "Withdraw"} recovery of $whom", check = check) {
            val u = now.user
            val i = now.info
            val guardian = now.identity
            if (u == null || i == null || guardian == null || targetId == null || status == null || keyList == null) {
                throw IllegalStateException("the identities are still loading: try again in a moment")
            }
            val signer = services.registry.identitySigner(u, i.canonical, now.keys, guardian)
            val registry = i.canonical.registry
            val payer = chosenFee.resolve()
            if (approve) services.registry.approveRecovery(i.status.chainId, registry, guardian, signer, targetId, keyList, status.proposal, payer)
            else services.registry.withdrawApproval(i.status.chainId, registry, guardian, signer, targetId, other ?: status.proposal, payer)
        }
    }

    // The keys the recovery installs, so a guardian can compare them with what the person sees.
    val newKeys = keyList?.let { Id.decodeKeyList(it) }
    fun keyLabel(k: Id.Key): String {
        val scheme = policyLabel(info?.canonical, k.policy)
        return if (w.keys.any { equal(unhex(it.pub), k.params) }) "$scheme · on this device" else scheme
    }

    val executableAt = status?.executableAt
    val due = executableAt != null && executableAt <= nowSeconds()
    // An identity without guardians has no one to approve: only its recovery phrase restores it.
    val noGuardians = mode == RecoveryMode.Mine && identity != null && identity.core.guardianCount == 0L
    val targetName = targetId?.let { nameOf(w.names, it) } ?: "them"
    var confirming by remember { mutableStateOf(false) }
    // Approving and finishing a recovery ask the phone to confirm its owner, as payments do.
    val owner = { title: String -> suspend { confirmOwner(activity, services.store, title) } }
    fun finish() {
        val chosenFee = fee
        act("recover", "Recover ${if (mode == RecoveryMode.Mine) "identity" else targetName}", check = owner("Finish the recovery")) {
            if (info == null || targetId == null || keyList == null) throw IllegalStateException("the identities are still loading: try again in a moment")
            services.registry.recover(info.status.chainId, info.canonical.registry, targetId, keyList, chosenFee.resolve())
        }
    }
    // Approved, but the delay still runs: the node can hold the finish until it is due.
    fun queueFinish() {
        act(
            "queue",
            "Recover ${if (mode == RecoveryMode.Mine) "identity" else targetName} when due",
            paid = false,
            check = {
                if (executableAt == null) throw IllegalStateException("not approved yet")
                confirmOwner(activity, services.store, "Finish the recovery when due")
            },
        ) {
            if (info == null || targetId == null || keyList == null || executableAt == null) throw IllegalStateException("the identities are still loading: try again in a moment")
            services.registry.queueRecover(info.canonical.registry, targetId, keyList, executableAt)
        }
    }
    val waitingToFinish = executableAt != null && !due
    val queueButton: @Composable () -> Unit = {
        if (waitingToFinish) {
            Button("Finish it when due", onClick = ::queueFinish, kind = ButtonKind.Ghost, small = true, icon = Icons.Outlined.HourglassEmpty, loading = sending.busy == "queue")
            Caption("The node holds it until ${relativeTime(executableAt)} and adds it to the first block it can. It goes without a fee, so a busy network may take longer: once due, Finish pays one.")
        }
    }

    Screen(title = "Recovery") {
        Segmented(listOf(RecoveryMode.Mine to "Recover mine", RecoveryMode.Help to "Help someone"), mode) { mode = it }
        sending.error?.let { Banner(sentence(it), BannerTone.Danger) }

        if (mode == RecoveryMode.Mine) {
            if (me == null) Banner("Select an identity user first.")
            if (noGuardians) {
                Banner(
                    "${user?.name ?: "This identity"} has no guardians, so only its recovery phrase can restore it: on a new phone, Restore → Recovery phrase. " +
                        "Add guardians on the Identity tab while you still can act for it.",
                    BannerTone.Warning,
                )
            }
            if (!noGuardians && advanced) {
                Card {
                    Sub("1. New keys")
                    Caption("Pick the keys on this device that should sign for ${user?.name ?: "the identity"} from now on. Create keys in Settings if needed.")
                    for (k in w.keys) {
                        val on = k.id in selectedKeys
                        ListItem(
                            k.name,
                            subtitle = k.scheme.label,
                            selected = on,
                            onClick = { selectedKeys = if (on) selectedKeys - k.id else selectedKeys + k.id },
                            right = if (on) ({ Pill("new key", Tone.Primary, small = true) }) else null,
                        )
                    }
                }
            } else if (!noGuardians && me != null && recoveryKey == null) {
                Card {
                    Sub("A new recovery phrase")
                    Caption("The recovery gives the identity the key of new words: whoever caused the trouble may hold the old ones. Write the new words down first.")
                    Button("Make a new recovery phrase", onClick = ::prepare, kind = ButtonKind.Secondary, small = true, icon = Icons.Outlined.Key, loading = preparing)
                }
            }
            if (myKeyList != null && me != null && !noGuardians) {
                Card {
                    Sub("${if (advanced) "2. " else ""}Send this to your guardians")
                    Caption("Each guardian scans it from their wallet, or pastes the link there or under Identity → Recovery → Help someone.")
                    LinkShare(Links.encode(Link.Recover(Links.netOf(info.status.chainId), me.id.hex(), myKeyList)))
                    if (newKeys != null) {
                        Caption("They should see ${if (newKeys.size == 1) "the key" else "the keys"} ${newKeys.joinToString(", ") { keyFingerprint(it) }} to be installed.")
                    }
                }
            }
        } else {
            Card {
                Sub("Who is recovering?")
                Field(
                    linkText,
                    { linkText = it },
                    label = "Recovery link",
                    placeholder = "ting:recover?…",
                    multiline = true,
                    keyboardType = KeyboardType.Uri,
                    error = (link as? Checked.Refused)?.message,
                )
                if (targetId != null) Caption("Recovering ${nameOf(w.names, targetId)}.") else Caption("Paste the link they sent, or scan its code from the wallet.")
                if (newKeys != null && newKeys.isEmpty()) Text("The link lists no keys.", style = Type.caption, color = p.danger)
            }
            if (newKeys != null && newKeys.isNotEmpty()) {
                Card {
                    Sub("Keys to install")
                    Caption("Approving hands the identity to ${if (newKeys.size == 1) "this key" else "these keys"}. Check with the person that they match what their new device shows.")
                    for (k in newKeys) ListItem(keyFingerprint(k), subtitle = keyLabel(k))
                }
            }
            if (identity == null) Banner("Only an identity on chain can approve; switch to one of yours.", BannerTone.Warning)
        }

        if (targetId != null && current != null && targetInfo == null && keyList != null) Banner("That identity is not on chain.", BannerTone.Warning)
        if (targetInfo != null && status != null) {
            RecoveryStatus(status, targetInfo, advanced, due) {
                FeeRow(fee)
                if (mode == RecoveryMode.Help && status.approvesOther != null) {
                    Banner("You approve another recovery of this identity. A guardian supports one recovery at a time: withdraw that approval to approve this one.", BannerTone.Warning)
                    Button(
                        "Withdraw the other approval",
                        onClick = { approval(false, status.approvesOther) },
                        kind = ButtonKind.Ghost,
                        loading = sending.busy == "withdraw-other",
                        enabled = identity != null,
                    )
                }
                if (mode == RecoveryMode.Help && status.guards == false) {
                    Banner("${user?.name ?: "This identity"} is not a guardian of $targetName: only its guardians can approve.", BannerTone.Warning)
                }
                if (mode == RecoveryMode.Help) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Button(
                            "Approve…",
                            onClick = { confirming = true },
                            modifier = Modifier.weight(1f),
                            loading = sending.busy == "approve",
                            enabled = identity != null && !newKeys.isNullOrEmpty() && status.standing == null && status.approvesOther == null && status.guards != false,
                        )
                        Button("Withdraw", onClick = { approval(false) }, kind = ButtonKind.Ghost, loading = sending.busy == "withdraw", enabled = identity != null && status.standing == Standing.Approved)
                    }
                    if (executableAt != null) {
                        Button(
                            if (due) "Finish recovery" else "Finish recovery ${relativeTime(executableAt)}",
                            onClick = ::finish,
                            kind = ButtonKind.Secondary,
                            enabled = due,
                            loading = sending.busy == "recover",
                        )
                    }
                    queueButton()
                } else {
                    Button(
                        if (due) "Recover now" else if (executableAt != null) "Recover ${relativeTime(executableAt)}" else "Recover once guardians approve",
                        onClick = ::finish,
                        enabled = due,
                        loading = sending.busy == "recover",
                    )
                    queueButton()
                }
            }
        }
        if (mode == RecoveryMode.Mine) Caption("Once enough guardians approve and the recovery delay passes, press Recover; the old devices then stop working.")
        if (confirming) {
            ConfirmSheet(
                ConfirmOptions(
                    title = "Approve the recovery of $targetName?",
                    body = "Approving helps hand $targetName to the keys above. Check with them by a call or in person that they asked for it and that the keys match what their new phone shows: someone could be posing as them.",
                    confirm = "Approve",
                    back = "Not now",
                ),
                onAnswer = { yes ->
                    confirming = false
                    if (yes) approval(true, check = owner("Approve the recovery of $targetName"))
                },
                ready = fee.problem == null,
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.fillMaxWidth().height(1.dp).background(p.border))
                    Box(Modifier.padding(vertical = 6.dp)) { FeeRow(fee) }
                    Spacer(Modifier.fillMaxWidth().height(1.dp).background(p.border))
                }
            }
        }
        Spacer(Modifier.height(Spacing.lg))
    }
}

/** Where the recovery stands: its approvals, when it can finish, and this guardian's part; then `actions`. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecoveryStatus(status: ProposalStatus, targetInfo: IdentityInfo, advanced: Boolean, due: Boolean, actions: @Composable ColumnScope.() -> Unit) {
    Card(tone = CardTone.Alt) {
        Sub("Status")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Pill("${status.approvals} of ${count(status.required, "approval")}", if (status.approvals >= status.required) Tone.Success else Tone.Warning)
            status.executableAt?.let { Pill(if (due) "ready" else "ready ${relativeTime(it)}", if (due) Tone.Success else Tone.Accent) }
            if (status.standing == Standing.Approved) Pill("you approved", Tone.Primary)
            if (status.standing == Standing.Withdrawn) Pill("you withdrew", Tone.Neutral)
        }
        Caption(
            (if (advanced) "Proposal ${status.proposal.hex().take(16)}… in recovery epoch ${targetInfo.core.recoveryEpoch}. " else "") +
                "The recovery delay is ${relativeDuration(targetInfo.core.recoveryDelay)}. Reaching the threshold voids every pending change of the identity; " +
                "a change to its guardians, threshold or delays voids the approvals.",
        )
        actions()
    }
}
