// The Identity tab: the active user's identity with its guardians and
// accounts, its devices once it has more than one (its keys, and adding them,
// in the advanced settings), the changes waiting to apply, a recovery its
// guardians approve, and the ways into a recovery. A simple account is
// offered an identity; a phone without a user, the welcome.
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Refresh
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
import androidx.compose.ui.draw.clip
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
import app.tingid.chain.isHex
import app.tingid.chain.nowSeconds
import app.tingid.chain.relativeTime
import app.tingid.chain.shortId
import app.tingid.chain.unhex
import app.tingid.state.IdentityInfo
import app.tingid.state.KeyMeta
import app.tingid.state.LinkedKey
import app.tingid.state.OpenRecovery
import app.tingid.state.User
import app.tingid.state.UserKind
import app.tingid.state.WorkspaceState
import app.tingid.state.deviceKeyOf
import app.tingid.state.guardiansOf
import app.tingid.state.linkedDeviceKeys
import app.tingid.state.openRecoveries
import app.tingid.state.policyLabel
import app.tingid.state.slotLabel
import app.tingid.state.userIdentity
import app.tingid.text.Names
import app.tingid.text.OTHER_CHAIN_USER
import app.tingid.text.count
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
import app.tingid.ui.Sheet
import app.tingid.ui.Spacing
import app.tingid.ui.StatTile
import app.tingid.ui.Sub
import app.tingid.ui.Tone
import app.tingid.ui.Type
import app.tingid.ui.Welcome
import app.tingid.ui.identity.PendingChanges
import app.tingid.ui.identity.Sending
import app.tingid.ui.identity.rememberSending
import app.tingid.ui.manropeStyle
import app.tingid.ui.sentence
import app.tingid.ui.t
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun IdentityScreen() {
    val services = LocalServices.current
    val w by services.workspace.state.collectAsState()
    val user = w.user
    if (user == null) {
        Screen(title = "Identity", tab = true) { Welcome() }
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
    Screen(title = "Identity", tab = true) {
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
    val sending = rememberSending()
    HoldWhileBusy(sending.busy != null)
    val id = w.identity
    val info = w.info
    // Recoveries of this identity that guardians approve: its devices cannot stop one, only the guardians can.
    val recoveries = rememberOpenRecoveries(w)

    val guardians = rememberLinkedGuardians(w, user)
    // The identity's keys this device holds.
    val linked: List<LinkedKey> = if (id != null && info != null) linkedDeviceKeys(info.canonical, id, w.keys) else emptyList()

    val advanced = w.advanced
    val deviceKey = deviceKeyOf(user, w.keys) ?: linked.firstOrNull()?.key
    // A phone that signs for none of the identity's keys can only ask to be added, or recover.
    val canAct = linked.isNotEmpty()
    val pendingKey = deviceKey != null && id != null &&
        id.pending.any { pc -> pc.record.operations.any { it is Id.Operation.AddKey && equal(it.key.params, unhex(deviceKey.pub)) } }
    val propose = { route: ProposeRoute -> nav.navigate(route) }
    // Most identities have one device, this phone: the devices show once there is another, to remove it if lost.
    val showDevices = advanced || (id != null && id.keys.size > 1)
    // The code that links this identity as someone's guardian, once it is on chain for them to find.
    val guardianLink = remember(info?.status?.chainId?.hex(), id?.id?.hex(), user.name) {
        if (id != null && info != null) Links.encode(Link.Guardian(Links.netOf(info.status.chainId), id.id.hex(), Links.linkText(user.name))) else null
    }
    var showingCode by remember { mutableStateOf(false) }

    val header: @Composable () -> Unit = {
        InkHeader {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Identity", style = manropeStyle(14f, FontWeight.Medium), color = p.onHeaderMuted)
                ProfileButton(34.dp)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Avatar(user.name, user.color, 52.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        user.name,
                        style = manropeStyle(24f, FontWeight.SemiBold).copy(letterSpacing = (-0.5).sp),
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                // On a narrow screen the ID gives way, shortened further, so the code stays in reach.
                IdChip(user.identityId ?: "", label = "ID", light = true, modifier = Modifier.weight(1f, fill = false))
                if (guardianLink != null) CodeChip { showingCode = true }
            }
            if (id != null) {
                val keyCount = id.core.keyCount
                val guardianCount = id.core.guardianCount
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    if (showDevices) {
                        StatTile(
                            label = if (advanced) (if (keyCount == 1L) "Key" else "Keys") else if (keyCount == 1L) "Device" else "Devices",
                            value = keyCount.toString(),
                            icon = if (advanced) Icons.Outlined.Key else Icons.Outlined.PhoneAndroid,
                            hint = if (linked.isNotEmpty()) "including this device" else "not this device yet",
                            onHeader = true,
                        )
                    }
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
        if (id != null) for (r in recoveries) RecoveryNotice(r, id, w.names)

        if (id != null && linked.isEmpty()) NotThisPhone(w, user, id, deviceKey, pendingKey, sending)

        if (id != null) {
            if (showDevices) KeysSection(w, user, id, canAct, propose)
            GuardiansSection(w, id, guardians, canAct, propose)
            AccountsSection(id)
            PendingChanges(w, user, sending)
            RecoverySection()
            if (advanced) TimeLocksSection(id, canAct, propose)
        }
        Spacer(Modifier.height(Spacing.lg))
    }
    if (showingCode && guardianLink != null) GuardianCodeSheet(guardianLink) { showingCode = false }
}

/** Beside the ID in the header: opens the user's guardian code. */
@Composable
private fun CodeChip(onClick: () -> Unit) {
    val p = t
    Row(
        Modifier.clip(RoundedCornerShape(999.dp)).background(p.headerRaised).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Outlined.QrCode2, null, tint = p.onHeader, modifier = Modifier.size(14.dp))
        Text("QR code", style = Type.caption, color = p.onHeader, maxLines = 1, softWrap = false)
    }
}

/** The user's guardian code: scanned in Ting ID, it links this identity as the scanner's guardian. */
@Composable
private fun GuardianCodeSheet(link: String, onClose: () -> Unit) {
    Sheet(onClose = onClose, title = "Be a guardian") {
        Body("Someone who wants you as a guardian scans this with Ting ID: Scan on the wallet, or Guardians → Link on their Identity.", muted = true)
        LinkShare(link, text = false)
        Caption("If they ever lose their phone, your approval helps them get their identity back. It moves none of your coins.")
    }
}

/** A hairline between rows of a card. */
@Composable
private fun Rule(color: Color = t.border) = Spacer(Modifier.fillMaxWidth().height(0.5.dp).background(color))

/**
 * The guardians of the user's identity that this device knows, read again
 * with each block. Who may be a guardian, as this device knows: the ones named
 * at creation or linked from here, the contacts that are identities, and the
 * other identities here. A restored identity finds its guardians among the
 * contacts.
 */
@Composable
internal fun rememberLinkedGuardians(w: WorkspaceState, user: User?): List<ByteArray> {
    val services = LocalServices.current
    val data by services.store.data.collectAsState()
    val me = user?.let(::userIdentity)
    val known = LinkedHashMap<String, ByteArray>()
    user?.let(::guardiansOf)?.forEach { known[it] = unhex(it) }
    for (c in data.contacts) c.identity?.takeIf { isHex(it, 32) }?.let { known[it.lowercase()] = unhex(it) }
    for (u in data.users) {
        val other = userIdentity(u) ?: continue
        if (me != null && !equal(other.id, me.id)) known[other.id.hex()] = other.id
    }
    val asked = if (me != null && w.identity != null) me else null
    val candidates = known.values.toList()
    val guardians by remember(asked?.id?.hex(), asked?.registry?.hex(), known.keys.sorted()) {
        services.registry.linkedGuardians(asked, candidates)
    }.collectAsState(initial = emptyList())
    return guardians
}

/**
 * The recoveries of the active user's identity that guardians approve now,
 * found in its activity with no reads of their own; one to keys of this phone
 * alone, which the user asked for here, is not among them.
 */
@Composable
internal fun rememberOpenRecoveries(w: WorkspaceState): List<OpenRecovery> {
    val services = LocalServices.current
    val scan by remember(w.scope) { services.activities.scan(w.scope) }.collectAsState(initial = null)
    val id = w.identity
    val c = w.info?.canonical
    if (id == null || c == null) return emptyList()
    val here = w.keys.map { Id.Key(c.policyFor(it.scheme), unhex(it.pub)) }
    return openRecoveries(scan?.activity ?: emptyList(), id.id, id.core, here)
}

/** A recovery's headline: begun, or under way once enough guardians approve. */
internal fun recoveryHeadline(r: OpenRecovery, core: Id.Core): String =
    if (r.reachedAt > 0 && r.approvals >= core.required) "Your identity is being recovered" else "A recovery of your identity has begun"

/** A recovery of the user's identity that guardians approve: who, when it can finish, and that only guardians can stop it. */
@Composable
private fun RecoveryNotice(r: OpenRecovery, id: IdentityInfo, names: Names) {
    val p = t
    val core = id.core
    val at = if (r.reachedAt > 0 && r.approvals >= core.required) r.reachedAt + core.recoveryDelay else null
    // By name when the events show each approval; one given before they were read is counted only.
    val who = if (r.guardians.size.toLong() == r.approvals) r.guardians.joinToString(", ") { nameOf(names, it) } else count(r.approvals, "guardian")
    val stands = when {
        at == null -> ", ${r.approvals} of ${core.required} needed"
        at <= nowSeconds() -> "; anyone can finish it now"
        else -> "; it can finish ${relativeTime(at)}"
    }
    Card(tone = CardTone.Warning) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            IconCircle(Icons.Outlined.Shield, p.warning, p.warningSoft)
            Column(Modifier.weight(1f)) {
                Sub(recoveryHeadline(r, core))
                Caption("$who approved$stands. Not you? Ask your guardians to withdraw.")
            }
            Info(
                "Recovery",
                "Guardians can hand your identity to new keys, ${relativeDuration(core.recoveryDelay)} after ${core.required} of them approve. " +
                    "Your devices cannot stop it: only guardians can, each by withdrawing their approval before it finishes. " +
                    "Once it finishes, the new keys act for your identity and its accounts, and this phone no longer can. " +
                    "If you asked for it on another phone, there is nothing to do.",
            )
        }
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
            Caption("On a device that has access, open Scan on the wallet and scan this, or paste the link there.")
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
        action = if (canAct && advanced) "Add key" else null,
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
                    subtitle = if (advanced) "key $keyId · $label · ${deviceFingerprint(key.params)}$note" else "can act for this identity$note",
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
        info = "People you trust. If yours are lost, $required of them can hand the identity to new keys, ${relativeDuration(id.core.recoveryDelay)} after they approve. " +
            "Your devices cannot stop a recovery: only guardians can, by withdrawing their approvals while it waits. " +
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
    // What to do when the phone or the words are stolen, in steps, opened on a tap.
    var stolen by remember { mutableStateOf(false) }
    Card {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button) { stolen = !stolen },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Phone or words stolen?", Modifier.weight(1f), style = Type.subheading, color = t.text)
            Icon(if (stolen) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown, if (stolen) "Hide the steps" else "Show the steps", tint = t.muted)
        }
        if (stolen) {
            Caption("1. Cancel every withdrawal and change you did not make: under Scheduled on the wallet, and Pending changes here.")
            Caption("2. Move what is left in Spending to Savings: Spending is instant.")
            Caption("3. Ask your guardians to recover the identity to a new phrase. That voids the thief’s changes, and the old phone and words stop working.")
        }
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
