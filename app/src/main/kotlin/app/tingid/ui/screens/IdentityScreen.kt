// The Identity tab: the active user's identity with its devices (or keys, in
// the advanced settings), guardians and accounts, the changes waiting to
// apply, and the ways into a recovery. A simple account is offered an
// identity; a phone without a user, the welcome.
package app.tingid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.TabletAndroid
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tingid.chain.Id
import app.tingid.chain.Link
import app.tingid.chain.Links
import app.tingid.chain.equal
import app.tingid.chain.hex
import app.tingid.chain.nowSeconds
import app.tingid.chain.relativeTime
import app.tingid.chain.shortId
import app.tingid.chain.unhex
import app.tingid.state.CANCEL_CHUNK
import app.tingid.state.IdentityInfo
import app.tingid.state.KeyMeta
import app.tingid.state.LinkedKey
import app.tingid.state.SubmittedTx
import app.tingid.state.User
import app.tingid.state.UserKind
import app.tingid.state.WorkspaceState
import app.tingid.state.deviceKeyOf
import app.tingid.state.guardiansOf
import app.tingid.state.linkedDeviceKeys
import app.tingid.state.policyLabel
import app.tingid.state.sent
import app.tingid.state.slotLabel
import app.tingid.state.userIdentity
import app.tingid.text.OTHER_CHAIN_USER
import app.tingid.text.count
import app.tingid.text.describeOperation
import app.tingid.text.deviceFingerprint
import app.tingid.text.nameOf
import app.tingid.text.relativeDuration
import app.tingid.ui.Avatar
import app.tingid.ui.Banner
import app.tingid.ui.BannerTone
import app.tingid.ui.Body
import app.tingid.ui.Button
import app.tingid.ui.ButtonKind
import app.tingid.ui.Caption
import app.tingid.ui.Card
import app.tingid.ui.CardTone
import app.tingid.ui.ConfirmOptions
import app.tingid.ui.ConfirmSheet
import app.tingid.ui.HoldWhileBusy
import app.tingid.ui.IconCircle
import app.tingid.ui.IdChip
import app.tingid.ui.Info
import app.tingid.ui.InkHeader
import app.tingid.ui.LinkShare
import app.tingid.ui.ListItem
import app.tingid.ui.LocalActivity
import app.tingid.ui.LocalNav
import app.tingid.ui.LocalServices
import app.tingid.ui.NameIdentitySheet
import app.tingid.ui.NewUserRoute
import app.tingid.ui.Pill
import app.tingid.ui.ProfileButton
import app.tingid.ui.ProposeRoute
import app.tingid.ui.RecoveryRoute
import app.tingid.ui.Screen
import app.tingid.ui.SectionHeader
import app.tingid.ui.Spacing
import app.tingid.ui.StatTile
import app.tingid.ui.Sub
import app.tingid.ui.Tone
import app.tingid.ui.TxRoute
import app.tingid.ui.Type
import app.tingid.ui.Welcome
import app.tingid.ui.confirmOwner
import app.tingid.ui.identity.Sending
import app.tingid.ui.identity.rememberSending
import app.tingid.ui.manropeStyle
import app.tingid.ui.money.FeeChoice
import app.tingid.ui.money.FeeRow
import app.tingid.ui.money.rememberFee
import app.tingid.ui.sentence
import app.tingid.ui.t
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** A cancel or apply being confirmed: its question, and what runs with the owner's check once confirmed. */
private class ChangeAsking(val options: ConfirmOptions, val run: (check: suspend () -> Boolean) -> Unit)

/** What a cancel of `changeNos` says it did. */
private fun cancelSummary(changeNos: List<Long>): String = if (changeNos.size == 1) "Cancel change #${changeNos[0]}" else "Cancel ${changeNos.size} changes"

@Composable
fun IdentityScreen() {
    val services = LocalServices.current
    val w by services.workspace.state.collectAsState()
    val user = w.user
    if (user == null) {
        Screen(title = "Identity") { Welcome() }
        return
    }
    if (user.kind == UserKind.Key) {
        SimpleAccount(user)
        return
    }
    IdentityUser(w, user)
}

/** A key user: one account on this device, and the way to an identity. */
@Composable
private fun SimpleAccount(user: User) {
    val nav = LocalNav.current
    Screen(title = "Identity", right = { ProfileButton() }) {
        Card {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Avatar(user.name, user.color, 48.dp)
                Column(Modifier.weight(1f)) {
                    Text(user.name, style = Type.heading, color = t.text)
                    Caption("Simple account")
                }
            }
            Body(
                "One account on this device, without guardians. An identity works from several devices, lets guardians restore access, and adds a savings account whose withdrawals wait.",
                muted = true,
            )
            Button("Create an identity", onClick = { nav.navigate(NewUserRoute()) }, icon = Icons.Outlined.Fingerprint)
        }
    }
}

@Composable
private fun IdentityUser(w: WorkspaceState, user: User) {
    val services = LocalServices.current
    val activity = LocalActivity.current
    val nav = LocalNav.current
    val p = t
    val users = services.store.data.collectAsState().value.users
    val sending = rememberSending()
    HoldWhileBusy(sending.busy != null)
    val me = userIdentity(user)
    val id = w.identity
    val info = w.info

    // Who may be a guardian, as this device knows: the ones named at creation or linked from here, and the other identities here.
    val known = LinkedHashMap<String, ByteArray>()
    guardiansOf(user).forEach { known[it] = unhex(it) }
    for (u in users) {
        val other = userIdentity(u) ?: continue
        if (me != null && !equal(other.id, me.id)) known[other.id.hex()] = other.id
    }
    val asked = if (me != null && id != null) me else null
    val candidates = known.values.toList()
    val guardians by remember(asked?.id?.hex(), asked?.registry?.hex(), known.keys.sorted()) {
        services.registry.linkedGuardians(asked, candidates)
    }.collectAsState(initial = emptyList())
    // One fee for cancelling and applying pending changes; registry operations pay nothing themselves.
    val fee = rememberFee(null)

    // The identity's keys this device holds, named "this device" in what a change does.
    val linked: List<LinkedKey> = if (id != null && info != null) linkedDeviceKeys(info.canonical, id, w.keys) else emptyList()
    val onThisDevice = { keyId: Long -> linked.any { it.keyId == keyId } }
    fun operationsOf(changeNo: Long): String? =
        id?.pending?.firstOrNull { it.changeNo == changeNo }?.record?.operations?.joinToString("; ") { describeOperation(it, w.names, w.advanced, onThisDevice) }

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
            val i = w.info
            val identity = w.identity
            if (i == null || identity == null) throw IllegalStateException("the identity is still loading: try again in a moment")
            val signer = services.registry.identitySigner(user, i.canonical, w.keys, identity)
            for (g in groups.dropLast(1)) {
                val s = sent { services.registry.cancelChanges(i.status.chainId, identity, signer, g, chosen.resolve()) }
                services.store.addSubmitted(SubmittedTx(id = s.txId, at = nowSeconds(), summary = cancelSummary(g), userId = user.id))
            }
            services.registry.cancelChanges(i.status.chainId, identity, signer, last, chosen.resolve())
        }
    }

    fun apply(changeNo: Long, check: suspend () -> Boolean) {
        val chosen = fee
        act("apply-$changeNo", "Apply change #$changeNo", check) {
            val i = w.info
            val identity = w.identity
            if (i == null || identity == null) throw IllegalStateException("the identity is still loading: try again in a moment")
            services.registry.applyChange(i.status.chainId, identity, changeNo, chosen.resolve())
        }
    }

    // The cancel or apply being confirmed: its sheet shows the fee, which can be changed there.
    var asking by remember { mutableStateOf<ChangeAsking?>(null) }
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

    val advanced = w.advanced
    val deviceKey = deviceKeyOf(user, w.keys) ?: linked.firstOrNull()?.key
    // A phone that signs for none of the identity's keys can only ask to be added, or recover.
    val canAct = linked.isNotEmpty()
    val pendingKey = deviceKey != null && id != null &&
        id.pending.any { pc -> pc.record.operations.any { it is Id.Operation.AddKey && equal(it.key.params, unhex(deviceKey.pub)) } }
    val propose = { route: ProposeRoute -> nav.navigate(route) }

    val header: @Composable () -> Unit = {
        InkHeader {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Identity", style = manropeStyle(14f, FontWeight.Bold), color = p.onHeaderMuted)
                ProfileButton(34.dp)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Avatar(user.name, user.color, 52.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        user.name,
                        style = manropeStyle(24f, FontWeight.ExtraBold).copy(letterSpacing = (-0.5).sp),
                        color = p.onHeader,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(
                            if (id != null) Icons.Outlined.VerifiedUser else Icons.Outlined.HourglassEmpty,
                            null,
                            tint = if (id != null) Color(0xFF34D399) else Color(0xFFFCD34D),
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            if (id != null) "On chain" else if (w.otherChain) "Another network" else "Waiting for the next block",
                            style = manropeStyle(12.5f, FontWeight.SemiBold),
                            color = p.onHeaderMuted,
                        )
                    }
                }
            }
            IdChip(user.identityId ?: "", label = "ID", light = true)
            if (id != null) {
                val keyCount = id.core.keyCount
                val guardianCount = id.core.guardianCount
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    StatTile(
                        label = if (advanced) (if (keyCount == 1L) "Key" else "Keys") else if (keyCount == 1L) "Device" else "Devices",
                        value = keyCount.toString(),
                        icon = if (advanced) Icons.Outlined.Key else Icons.Outlined.PhoneAndroid,
                        hint = if (linked.isNotEmpty()) "including this device" else "not this device yet",
                        onHeader = true,
                    )
                    StatTile(
                        label = if (guardianCount == 1L) "Guardian" else "Guardians",
                        value = guardianCount.toString(),
                        icon = Icons.Outlined.VerifiedUser,
                        hint = if (guardianCount > 0) "${id.core.required} needed to recover" else "add someone you trust",
                        onHeader = true,
                    )
                }
            }
        }
    }

    Screen(header = header) {
        if (w.otherChain) Banner(OTHER_CHAIN_USER, BannerTone.Warning)
        sending.error?.let { Banner(sentence(it), BannerTone.Danger) }

        if (id != null && linked.isEmpty()) NotThisPhone(w, user, id, deviceKey, pendingKey, sending)

        if (id != null) {
            KeysSection(w, user, id, canAct, propose)
            GuardiansSection(w, id, guardians, canAct, propose)
            AccountsSection(id)
            if (id.pending.isNotEmpty()) {
                PendingSection(w, id, linked, sending.busy, onThisDevice, { nos, label -> confirmCancel(nos, label) }, ::confirmApply)
            }
            asking?.let { a ->
                ConfirmSheet(
                    a.options,
                    onAnswer = { yes ->
                        asking = null
                        // The question without its question mark names the phone's check.
                        val title = a.options.title.removeSuffix("?")
                        if (yes) a.run { confirmOwner(activity, services.store, title) }
                    },
                    ready = fee.problem == null,
                ) { ConfirmFee(fee) }
            }
            RecoverySection()
            if (advanced) TimeLocksSection(id, canAct, propose)
        }
        Spacer(Modifier.height(Spacing.lg))
    }
}

/** A hairline between rows of a card. */
@Composable
private fun Rule(color: Color = t.border) = Spacer(Modifier.fillMaxWidth().height(1.dp).background(color))

/** The fee in a confirmation, between hairlines. */
@Composable
private fun ConfirmFee(fee: FeeChoice) {
    Column(Modifier.fillMaxWidth()) {
        Rule()
        Box(Modifier.padding(vertical = 6.dp)) { FeeRow(fee) }
        Rule()
    }
}

/** This phone signs for none of the identity's keys: how it gets added, or recovers. */
@Composable
private fun NotThisPhone(w: WorkspaceState, user: User, id: IdentityInfo, deviceKey: KeyMeta?, pendingKey: Boolean, sending: Sending) {
    val services = LocalServices.current
    val nav = LocalNav.current
    val p = t
    val info = w.info
    Card(tone = CardTone.Warning) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            IconCircle(Icons.Outlined.PhoneAndroid, p.warning, p.warningSoft)
            Column(Modifier.weight(1f)) {
                Sub("This phone can’t act for ${user.name} yet")
                Caption(
                    if (pendingKey) {
                        "Another device has proposed to add it. Once the change waits out ${relativeDuration(id.core.changeDelay)}, anyone can apply it, this phone too, under Pending changes; then it works."
                    } else {
                        "Add it from a device that already has access, or ask your guardians to recover the identity."
                    },
                )
            }
        }
        if (deviceKey != null && info != null) {
            Caption("On a device that has access, scan this or paste the link there: Scan on the wallet, or Devices → Add device.")
            LinkShare(Links.encode(Link.Device(Links.netOf(info.status.chainId), id.id.hex(), deviceKey.scheme, unhex(deviceKey.pub))))
            Caption("This phone’s key is ${deviceFingerprint(unhex(deviceKey.pub))}: the other phone shows the same before it adds this one.")
        } else {
            Button(
                "Prepare this device",
                onClick = {
                    // Made in the app's scope: a key half made when the screen goes would be lost.
                    services.scope.launch {
                        try {
                            services.keys.ensureDeviceKey(user, w.keys)
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            sending.error = e.message ?: "the key could not be made"
                        }
                    }
                },
                kind = ButtonKind.Secondary,
                small = true,
                icon = Icons.Outlined.Key,
            )
        }
        Button("Recover with guardians", onClick = { nav.navigate(RecoveryRoute(mode = "mine")) }, kind = ButtonKind.Ghost, small = true, icon = Icons.Outlined.Refresh)
    }
}

@Composable
private fun KeysSection(w: WorkspaceState, user: User, id: IdentityInfo, canAct: Boolean, propose: (ProposeRoute) -> Unit) {
    val p = t
    val advanced = w.advanced
    SectionHeader(
        if (advanced) "Keys" else "Devices",
        action = if (canAct) (if (advanced) "Add key" else "Add device") else null,
        onAction = { propose(ProposeRoute(kind = "addKey")) },
    )
    // The first key is the one the identity was made with: in Ting ID, its recovery phrase's.
    val phraseKey = w.keys.firstOrNull { it.id == user.phraseKeyId }
    Card(padded = false) {
        Column {
            id.keys.forEachIndexed { i, (keyId, key) ->
                if (i > 0) Rule()
                val device = w.keys.firstOrNull { equal(unhex(it.pub), key.params) }
                val label = policyLabel(w.info?.canonical, key.policy)
                val title = if (advanced) device?.name ?: "Key $keyId" else if (device != null) "This device" else "Device ${keyId + 1}"
                val isPhrase = phraseKey != null && equal(unhex(phraseKey.pub), key.params)
                val note = when {
                    isPhrase -> " · your recovery phrase gives this key"
                    keyId == 0L && device == null -> " · made the identity; its recovery phrase gives this key"
                    else -> ""
                }
                ListItem(
                    title,
                    subtitle = if (advanced) "key $keyId · $label · ${shortId(key.params, 10, 6)}$note" else "can act for this identity$note",
                    left = {
                        IconCircle(
                            if (device != null) Icons.Outlined.PhoneAndroid else if (advanced) Icons.Outlined.Key else Icons.Outlined.TabletAndroid,
                            p.primary,
                            p.primarySoft,
                        )
                    },
                    right = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            if (device != null && advanced) Pill("this device", Tone.Primary, small = true)
                            if (canAct && id.keys.size > 1) {
                                Button("Remove", onClick = { propose(ProposeRoute(kind = "removeKey", keyId = keyId.toString())) }, kind = ButtonKind.Ghost, small = true)
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun GuardiansSection(w: WorkspaceState, id: IdentityInfo, guardians: List<ByteArray>, canAct: Boolean, propose: (ProposeRoute) -> Unit) {
    val p = t
    // A guardian with no name here can be given one.
    var naming by remember { mutableStateOf<String?>(null) }
    val required = id.core.required
    SectionHeader(
        "Guardians",
        action = if (canAct) "Link" else null,
        onAction = { propose(ProposeRoute(kind = "addGuardian")) },
        info = "People you trust. $required of them can hand the identity to new keys if yours are lost, after ${relativeDuration(id.core.recoveryDelay)} that you can use to stop it. " +
            "They cannot move funds themselves, but the keys they hand it to can${if (required == 1L) ": with one approval needed, a single guardian could" else ""}.",
    )
    Card(padded = false) {
        Column {
            if (guardians.isEmpty()) {
                Box(Modifier.padding(Spacing.lg)) {
                    Caption(if (id.core.guardianCount > 0) "${id.core.guardianCount} linked, none known to this device." else "No guardians linked yet.")
                }
            }
            guardians.forEachIndexed { i, g ->
                if (i > 0) Rule()
                val unnamed = w.names[g.hex()] == null
                ListItem(
                    nameOf(w.names, g),
                    subtitle = shortId(g, 10, 6),
                    left = { IconCircle(Icons.Outlined.VerifiedUser, p.accent, p.accentSoft) },
                    right = if (unnamed || canAct) {
                        {
                            Row {
                                if (unnamed) Button("Name", onClick = { naming = g.hex() }, kind = ButtonKind.Ghost, small = true)
                                if (canAct) Button("Unlink", onClick = { propose(ProposeRoute(kind = "removeGuardian", id = g.hex())) }, kind = ButtonKind.Ghost, small = true)
                            }
                        }
                    } else {
                        null
                    },
                )
            }
        }
    }
    naming?.let { NameIdentitySheet(it, onClose = { naming = null }) }
}

@Composable
private fun AccountsSection(id: IdentityInfo) {
    val p = t
    val vault = id.slots.firstOrNull { it.delay > 0 }
    SectionHeader(
        "Accounts",
        info = vault?.let {
            "Spending is instant. Withdrawals from ${slotLabel(it.slot)} wait ${relativeDuration(it.delay)}, and any of your devices can cancel one meanwhile, " +
                "so whoever takes a phone or your words cannot empty it at once."
        },
    )
    Card(padded = false) {
        Column {
            id.slots.forEachIndexed { i, s ->
                if (i > 0) Rule()
                val locked = s.delay != 0L
                ListItem(
                    slotLabel(s.slot),
                    subtitle = if (locked) "time-locked" else "instant",
                    left = {
                        IconCircle(
                            if (locked) Icons.Outlined.Lock else Icons.Outlined.AccountBalanceWallet,
                            if (locked) p.accent else p.primary,
                            if (locked) p.accentSoft else p.primarySoft,
                        )
                    },
                )
            }
        }
    }
}

/** The changes waiting to apply, each to cancel, and to apply once due. */
@Composable
private fun PendingSection(
    w: WorkspaceState,
    id: IdentityInfo,
    linked: List<LinkedKey>,
    busy: String?,
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
                Text("$n pending ${if (n == 1) "change" else "changes"}", style = manropeStyle(13.5f, FontWeight.ExtraBold), color = p.warning)
                Info(
                    "Pending changes",
                    "A change waits ${relativeDuration(id.core.changeDelay)}, and any of your devices can cancel it meanwhile. " +
                        "It is void once the device that proposed it is removed, or when guardians approve a recovery.",
                )
                Spacer(Modifier.weight(1f))
                if (linked.isNotEmpty() && n > 1) {
                    Text(
                        if (busy == "cancel-all") "Cancelling…" else "Cancel all",
                        style = manropeStyle(13.5f, FontWeight.Bold),
                        color = p.danger,
                        modifier = Modifier.clickable(role = Role.Button) { if (busy == null) confirmCancel(id.pending.map { it.changeNo }, "cancel-all") }.padding(4.dp),
                    )
                }
            }
            id.pending.forEachIndexed { i, change ->
                val changeNo = change.changeNo
                val record = change.record
                val due = record.effectiveAt <= nowSeconds()
                if (i > 0) Rule(p.warningLine)
                Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        IconCircle(if (due) Icons.Outlined.CheckCircle else Icons.Outlined.Schedule, p.warning, p.warningLine, 38.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(
                                record.operations.joinToString("; ") { describeOperation(it, w.names, w.advanced, onThisDevice) },
                                style = manropeStyle(14.5f, FontWeight.Bold),
                                color = p.text,
                            )
                            Text("#$changeNo · ${if (due) "ready to apply" else "applies ${relativeTime(record.effectiveAt)}"}", style = Type.caption, color = p.warning)
                        }
                        if (linked.isNotEmpty()) {
                            Button("Cancel", onClick = { confirmCancel(listOf(changeNo), "cancel-$changeNo") }, kind = ButtonKind.Danger, small = true, loading = busy == "cancel-$changeNo")
                        }
                    }
                    if (due) {
                        Row(Modifier.padding(start = 50.dp)) {
                            Button("Apply", onClick = { confirmApply(changeNo) }, kind = ButtonKind.Secondary, small = true, icon = Icons.Outlined.Check, loading = busy == "apply-$changeNo")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecoverySection() {
    val nav = LocalNav.current
    SectionHeader(
        "Recovery",
        icon = Icons.Outlined.Shield,
        info = "Lost your phone? On the new one, restore with your recovery phrase. Without it, add the identity by its ID (Add a user → Restore → Identity ID) and start a recovery there: " +
            "it shows a link for your guardians. Once enough approve and the recovery delay passes, the new phone takes over with a new recovery phrase, and the old devices and words stop working.",
    )
    Card {
        Caption(
            "Phone or words stolen? Cancel every withdrawal and change you did not make, under Scheduled on the wallet and Pending changes here. Then ask your guardians to " +
                "recover the identity to a new phrase: that voids the thief’s changes, and the old phone and words stop working. Spending is instant, so move what is left " +
                "there to Savings first.",
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Button("Recover this identity", onClick = { nav.navigate(RecoveryRoute(mode = "mine")) }, kind = ButtonKind.Secondary, small = true, icon = Icons.Outlined.Refresh)
            Button("Help someone", onClick = { nav.navigate(RecoveryRoute(mode = "help")) }, kind = ButtonKind.Ghost, small = true, icon = Icons.Outlined.PanTool)
        }
    }
}

@Composable
private fun TimeLocksSection(id: IdentityInfo, canAct: Boolean, propose: (ProposeRoute) -> Unit) {
    val p = t
    val core = id.core
    SectionHeader(
        "Time locks",
        icon = Icons.Outlined.HourglassEmpty,
        action = if (canAct) "Add account" else null,
        onAction = { propose(ProposeRoute(kind = "setSlot", slot = core.slotCount)) },
        info = "Recovery must be shorter than changes, so guardians can undo what a thief starts. A change here applies after the current change delay; lowering an account's lock also waits out that lock.",
    )
    val change: (ProposeRoute) -> (@Composable () -> Unit)? = { route ->
        if (canAct) ({ Button("Change", onClick = { propose(route) }, kind = ButtonKind.Ghost, small = true) }) else null
    }
    Card(padded = false) {
        Column {
            ListItem(
                "Changes and recovery",
                subtitle = "changes ${relativeDuration(core.changeDelay)} · recovery ${relativeDuration(core.recoveryDelay)}",
                left = { IconCircle(Icons.Outlined.HourglassEmpty, p.primary, p.primarySoft) },
                right = change(ProposeRoute(kind = "setDelays")),
            )
            Rule()
            ListItem(
                "Recovery threshold",
                subtitle = "${count(core.required, "approval")}${if (core.threshold == 0L) ", more than half" else ""}",
                left = { IconCircle(Icons.Outlined.People, p.accent, p.accentSoft) },
                right = change(ProposeRoute(kind = "setThreshold")),
            )
            for (s in id.slots) {
                Rule()
                val locked = s.delay != 0L
                ListItem(
                    slotLabel(s.slot),
                    subtitle = if (locked) "withdrawals wait ${relativeDuration(s.delay)}" else "instant",
                    left = {
                        IconCircle(
                            if (locked) Icons.Outlined.Lock else Icons.Outlined.AccountBalanceWallet,
                            if (locked) p.accent else p.primary,
                            if (locked) p.accentSoft else p.primarySoft,
                        )
                    },
                    right = change(ProposeRoute(kind = "setSlot", slot = s.slot)),
                )
            }
        }
    }
}
