// Proposing a change to the active user's identity: adding a device (from
// another phone's device link, or one of this phone's keys), removing a key,
// linking or unlinking a guardian, the recovery threshold, the delays, and an
// account's time lock. A change waits before it applies, and any of the
// identity's devices can cancel it meanwhile.
package app.tingid.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.tingid.chain.DELAY_PRESETS
import app.tingid.chain.Id
import app.tingid.chain.Links
import app.tingid.chain.equal
import app.tingid.chain.hex
import app.tingid.chain.isHex
import app.tingid.chain.nowSeconds
import app.tingid.chain.trimWhiteSpace
import app.tingid.chain.unhex
import app.tingid.state.Checked
import app.tingid.state.SubmittedTx
import app.tingid.state.deviceLinkKey
import app.tingid.state.fetchIdentity
import app.tingid.state.identityName
import app.tingid.state.keyNameFor
import app.tingid.state.linkedDeviceKeys
import app.tingid.state.sent
import app.tingid.state.slotLabel
import app.tingid.state.thresholdOf
import app.tingid.state.userIdentity
import app.tingid.text.Names
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
import app.tingid.ui.Field
import app.tingid.ui.HoldWhileBusy
import app.tingid.ui.ListItem
import app.tingid.ui.LocalActivity
import app.tingid.ui.LocalNav
import app.tingid.ui.LocalServices
import app.tingid.ui.Pill
import app.tingid.ui.ProposeRoute
import app.tingid.ui.Screen
import app.tingid.ui.Spacing
import app.tingid.ui.Sub
import app.tingid.ui.TAKEN
import app.tingid.ui.Tone
import app.tingid.ui.TxRoute
import app.tingid.ui.WalletTab
import app.tingid.ui.confirmOwner
import app.tingid.ui.identity.rememberSending
import app.tingid.ui.money.FeeRow
import app.tingid.ui.money.rememberFee
import app.tingid.ui.money.withFee
import app.tingid.ui.nameTaken
import app.tingid.ui.sentence
import kotlinx.coroutines.CancellationException

private val TITLES = mapOf(
    "addKey" to "Add a device",
    "removeKey" to "Remove a device",
    "addGuardian" to "Link a guardian",
    "removeGuardian" to "Unlink a guardian",
    "setThreshold" to "Recovery threshold",
    "setDelays" to "Delays",
    "setSlot" to "Account time lock",
)

/** Changes to how the identity is recovered: applying one voids open recovery proposals. */
private val RECOVERY_KINDS = setOf("addGuardian", "removeGuardian", "setThreshold", "setDelays")

// Where the key to add comes from: another device's link, a new key here, or a key of this device (its id).
private const val FROM_LINK = "code"
private const val NEW_KEY = "new"

/** `route.kind`: the change to propose; the changes this screen proposes are the only ones a route may name. */
@Composable
fun ProposeScreen(route: ProposeRoute) {
    val services = LocalServices.current
    val activity = LocalActivity.current
    val nav = LocalNav.current
    val w by services.workspace.state.collectAsState()
    val users = services.store.data.collectAsState().value.users
    val contacts = services.store.data.collectAsState().value.contacts
    val kind = (route.kind ?: "addKey").takeIf { it in TITLES }
    val keyIdParam = route.keyId?.takeIf { Regex("^[0-9]+$").matches(it) }?.toLongOrNull()
    val user = w.user
    val identity = w.identity
    val info = w.info
    val core = identity?.core
    val advanced = w.advanced

    // A key of this device to add, such as the one of a new recovery phrase.
    val presetKey = route.key?.takeIf { k -> w.keys.any { it.id == k } }
    val addingPhrase = presetKey != null && user?.phraseKeyId == presetKey
    var source by remember { mutableStateOf(presetKey ?: FROM_LINK) }
    // The device link: scanned or pasted on the wallet, or typed here.
    var code by remember { mutableStateOf(route.code ?: "") }
    var newKeyName by remember { mutableStateOf("") }
    var guardian by remember { mutableStateOf(route.id?.takeIf { isHex(it, 32) } ?: "") }
    // Whether the guardian to link is an identity on this network; an account pasted by mistake could never approve.
    var guardianFound by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val guardianId = trimWhiteSpace(guardian).lowercase()
    val registry = info?.canonical?.registry
    // A guardian with no name here is named with the link, as a contact.
    var guardianName by remember { mutableStateOf("") }
    val naming = kind == "addGuardian" && guardianFound == (guardianId to true) && identityName(w.names, registry, guardianId) == null
    // The texts here name the guardian as its contact does, also when the contact has only its Spending account.
    val guardianKnownAs = if (kind in setOf("addGuardian", "removeGuardian") && isHex(guardianId, 32)) identityName(w.names, registry, guardianId) else null
    val withGuardian = { names: Names -> if (guardianKnownAs != null) names + (guardianId to guardianKnownAs) else names }
    val newName = guardianName.trim()
    val nameClash = naming && nameTaken(contacts, newName)
    LaunchedEffect(guardianId, registry?.hex(), w.node) {
        if (kind != "addGuardian" || registry == null || !isHex(guardianId, 32)) return@LaunchedEffect
        try {
            guardianFound = guardianId to (fetchIdentity(w.node, registry, unhex(guardianId)) != null)
        } catch (e: Exception) {
            // Not found out: the change waits for the answer, which another try may give.
            if (e is CancellationException) throw e
        }
    }
    var threshold by remember { mutableStateOf(core?.threshold?.toString() ?: "0") }
    var changeDelay by remember { mutableLongStateOf(core?.changeDelay ?: Id.DEFAULT_CHANGE_DELAY) }
    var recoveryDelay by remember { mutableLongStateOf(core?.recoveryDelay ?: Id.DEFAULT_RECOVERY_DELAY) }
    val slotNo = route.slot?.takeIf { it >= 0 } ?: core?.slotCount ?: 1L
    val currentSlot = identity?.slots?.firstOrNull { it.slot == slotNo }
    var slotDelay by remember { mutableLongStateOf(currentSlot?.delay ?: 604_800L) }
    val sending = rememberSending()
    HoldWhileBusy(sending.busy != null)
    val fee = rememberFee(null)

    if (kind == null) {
        Screen(title = "Propose a change") {
            Banner("This is not a change Ting ID can propose.", BannerTone.Danger)
            Button("Back", onClick = { if (!nav.popBackStack()) nav.navigate(WalletTab) }, kind = ButtonKind.Secondary)
        }
        return
    }

    val me = user?.let(::userIdentity)
    val linked = if (identity != null && info != null) linkedDeviceKeys(info.canonical, identity, w.keys) else emptyList()
    // Whether the key to remove is one this device signs with.
    val removingThisDevice = keyIdParam != null && linked.any { it.keyId == keyIdParam }
    // The identity's keys this device holds, named "this device" in what a change does.
    val thisDevice = linked.map { it.keyId }
    // Whether the key to remove is the one this user's recovery phrase gives.
    val phraseKeyMeta = w.keys.firstOrNull { it.id == user?.phraseKeyId }
    val removingPhraseKey = keyIdParam != null && phraseKeyMeta != null &&
        identity?.keys?.any { it.keyId == keyIdParam && equal(it.key.params, unhex(phraseKeyMeta.pub)) } == true
    fun isLinked(pub: ByteArray) = identity?.keys?.any { equal(it.key.params, pub) } == true
    val availableKeys = w.keys.filter { !isLinked(unhex(it.pub)) }
    val otherIdentities = if (me == null) emptyList() else users.mapNotNull { u -> userIdentity(u)?.takeIf { !equal(it.id, me.id) }?.let { u to it.id.hex() } }
    // The device link's key, when it is a link for this identity on this network.
    val device = deviceLinkKey(code, info?.let { Links.netOf(it.status.chainId) }, me?.id?.hex(), user?.name)
    val parsedCode = (device as? Checked.Ok)?.value

    // The change as the inputs say now, or what is missing.
    fun operation(): Checked<Id.Operation> {
        fun no(message: String) = Checked.Refused(message)
        return when (kind) {
            "addKey" -> {
                val i = info ?: return no("not connected")
                when (source) {
                    NEW_KEY -> no("built on submit")
                    FROM_LINK -> when {
                        parsedCode == null -> no("paste the device link")
                        isLinked(parsedCode.pub) -> no("that device already has access")
                        else -> Checked.Ok(Id.Operation.AddKey(Id.Key(i.canonical.policyFor(parsedCode.scheme), parsedCode.pub)))
                    }
                    else -> w.keys.firstOrNull { it.id == source }?.let { Checked.Ok(Id.Operation.AddKey(Id.Key(i.canonical.policyFor(it.scheme), unhex(it.pub)))) }
                        ?: no("choose a key")
                }
            }
            "removeKey" -> keyIdParam?.let { Checked.Ok(Id.Operation.RemoveKey(it)) } ?: no("missing key")
            "addGuardian" -> when {
                !isHex(guardianId, 32) -> no("choose a guardian")
                guardianFound?.first != guardianId -> no("looking the guardian up")
                guardianFound?.second != true -> no("no identity has that ID on this network: a guardian must be an identity, not an account")
                else -> Checked.Ok(Id.Operation.AddGuardian(unhex(guardianId)))
            }
            "removeGuardian" -> if (isHex(guardian, 32)) Checked.Ok(Id.Operation.RemoveGuardian(unhex(guardian))) else no("missing guardian")
            "setThreshold" -> when (val n = thresholdOf(threshold, core?.guardianCount ?: 0)) {
                is Checked.Ok -> Checked.Ok(Id.Operation.SetThreshold(n.value))
                is Checked.Refused -> n
            }
            "setDelays" -> if (recoveryDelay < changeDelay) Checked.Ok(Id.Operation.SetDelays(changeDelay, recoveryDelay)) else no("recovery must be shorter than changes")
            else -> Checked.Ok(Id.Operation.SetSlot(slotNo, slotDelay))
        }
    }

    if (identity == null || core == null) {
        Screen(title = "Propose a change") {
            Banner("The identity must be on chain before it can change.", BannerTone.Warning)
        }
        return
    }

    val title = when {
        addingPhrase -> "Add a recovery phrase"
        advanced && kind == "addKey" -> "Add a key"
        advanced && kind == "removeKey" -> "Remove a key"
        else -> TITLES.getValue(kind)
    }

    fun submit() {
        val u = user ?: return
        val i = info ?: return
        val now = w
        val chosen = fee
        val planned = operation()
        val fromNewKey = kind == "addKey" && source == NEW_KEY
        val keyName = trimWhiteSpace(newKeyName).ifEmpty { keyNameFor(u.name) }
        val adding = addingPhrase
        sending.send(
            "propose",
            check = { confirmOwner(activity, services.store, title, "for ${u.name}") },
            submit = {
                val payer = chosen.resolve()
                val op = if (fromNewKey) {
                    val key = services.keys.createDeviceKey(keyName)
                    Id.Operation.AddKey(Id.Key(i.canonical.policyFor(key.scheme), unhex(key.pub)))
                } else {
                    when (planned) {
                        is Checked.Ok -> planned.value
                        is Checked.Refused -> throw IllegalStateException(planned.message)
                    }
                }
                val signer = services.registry.identitySigner(u, i.canonical, now.keys, identity)
                val s = sent { services.registry.proposeChange(i.status.chainId, identity, signer, listOf(op), payer) }
                val summary = "Propose: ${describeOperation(op, withGuardian(now.names), now.advanced) { it in thisDevice }}"
                services.store.addSubmitted(SubmittedTx(id = s.txId, at = nowSeconds(), summary = withFee(summary, chosen), userId = u.id))
                if (adding) services.store.updateUser(u.id) { it.copy(phraseToAdd = false) }
                // A guardian this phone linked stays known to it, by name and to unlink.
                if (op is Id.Operation.AddGuardian) {
                    services.store.updateUser(u.id) { it.copy(knownGuardians = (it.knownGuardians + op.id.hex()).distinct()) }
                    if (naming && newName.isNotEmpty()) services.store.nameIdentity(newName, op.id.hex(), i.canonical.registry)
                }
                s
            },
            opened = { s -> nav.navigate(TxRoute(s.txId, unsure = s.unsure)) { popUpTo<ProposeRoute> { inclusive = true } } },
        )
    }

    val preview = operation()
    // Lowering an account's lock also waits out the lock it has.
    val lowering = kind == "setSlot" && currentSlot != null && slotDelay < currentSlot.delay
    val wait = if (lowering) maxOf(core.changeDelay, currentSlot.delay) else core.changeDelay
    val canSubmit = (preview is Checked.Ok || (kind == "addKey" && source == NEW_KEY)) && !nameClash
    val selected: @Composable () -> Unit = { Pill("selected", Tone.Primary, small = true) }

    Screen(title = title) {
        sending.error?.let { Banner(sentence(it), BannerTone.Danger) }
        Card {
            if (kind == "addKey" && addingPhrase) {
                Caption("Adds the key of your new recovery phrase to the identity. Like every change, it waits before it applies, so a thief cannot add a key of their own at once.")
            }
            if (kind == "addKey" && !addingPhrase) {
                if (advanced) {
                    ListItem("A device link", subtitle = "from another phone", selected = source == FROM_LINK, onClick = { source = FROM_LINK }, right = if (source == FROM_LINK) selected else null)
                } else {
                    Caption("Paste the device link the other device shows after Add a user → Restore → Identity ID, or scan its code from the wallet.")
                }
                if (source == FROM_LINK) {
                    Column(Modifier.padding(start = if (advanced) Spacing.md else 0.dp), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Field(
                            code,
                            { code = it },
                            label = "Device link",
                            placeholder = "ting:device?…",
                            multiline = true,
                            keyboardType = KeyboardType.Uri,
                            error = (device as? Checked.Refused)?.message,
                        )
                        if (parsedCode != null) {
                            Banner(
                                "Key ${deviceFingerprint(parsedCode.pub)}: check that the new phone shows the same. Only add a phone you hold yourself.",
                                BannerTone.Info,
                                Icons.Outlined.PhoneAndroid,
                            )
                        }
                    }
                }
                if (advanced) {
                    // Ting ID makes secp256k1 keys only, so there is no scheme to choose here.
                    ListItem("A new key on this device", selected = source == NEW_KEY, onClick = { source = NEW_KEY }, right = if (source == NEW_KEY) selected else null)
                    if (source == NEW_KEY) {
                        Column(Modifier.padding(start = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            Field(newKeyName, { newKeyName = it }, label = "Name", placeholder = keyNameFor(user?.name ?: "Me"))
                        }
                    }
                    for (k in availableKeys) {
                        ListItem(
                            k.name,
                            subtitle = "${k.scheme.label} · already on this device",
                            selected = source == k.id,
                            onClick = { source = k.id },
                            right = if (source == k.id) selected else null,
                        )
                    }
                }
            }
            if (kind == "removeKey" && (keyIdParam == 0L || removingPhraseKey)) {
                Banner(
                    if (removingPhraseKey) {
                        "This is the key of your recovery phrase: once it is removed, the written words stop working. Make a new recovery phrase afterwards, in Settings."
                    } else {
                        "This is the first key, the one the identity was made with. Made in Ting ID, it is also the key of its recovery phrase: removing it makes those written words stop working. Make a new recovery phrase afterwards, in Settings."
                    },
                    BannerTone.Warning,
                )
            }
            if (kind == "removeKey") {
                val what = when {
                    advanced -> "Remove key ${route.keyId} from this identity."
                    removingThisDevice -> "This device will no longer be able to act for the identity."
                    else -> "That device will no longer be able to act for the identity."
                }
                Body("$what Make sure another one stays usable.")
            }
            if (kind == "addGuardian") {
                Caption("Pick an identity on this device or paste an ID.")
                for ((u, idHex) in otherIdentities) {
                    ListItem(u.name, left = { Avatar(u.name, u.color, 32.dp) }, selected = guardian == idHex, onClick = { guardian = idHex })
                }
                Field(guardian, { guardian = it }, label = "Identity ID", placeholder = "64 hex digits", keyboardType = KeyboardType.Ascii)
                if (naming) {
                    Field(
                        guardianName,
                        { guardianName = it },
                        label = "Their name",
                        placeholder = "Bob",
                        hint = "Optional: saved as a contact, with their Spending account.",
                        error = if (nameClash) TAKEN else null,
                    )
                }
            }
            if (kind == "removeGuardian" && isHex(guardian, 32)) Body("Unlink guardian ${nameOf(withGuardian(w.names), unhex(guardianId))}.")
            if (kind == "setThreshold") {
                Field(threshold, { threshold = it }, label = "Approvals a recovery needs", keyboardType = KeyboardType.Number, hint = "0 means more than half of the guardians.")
                Caption("Currently ${count(core.guardianCount, "guardian")}.")
            }
            if (kind == "setDelays") {
                Caption("Changes wait:")
                DelayPresets(changeDelay, { changeDelay = it }, min = 3600)
                Caption("Recovery waits:")
                DelayPresets(recoveryDelay, { recoveryDelay = it }, min = 60, max = changeDelay - 1)
            }
            if (kind == "setSlot") {
                Caption(
                    if (currentSlot != null) {
                        "${slotLabel(slotNo)} currently ${if (currentSlot.delay != 0L) "waits ${relativeDuration(currentSlot.delay)}" else "is instant"}."
                    } else {
                        "A new account, ${slotLabel(slotNo)}."
                    },
                )
                DelayPresets(slotDelay, { slotDelay = it })
            }
        }
        Card(tone = CardTone.Alt) {
            Sub("What happens")
            Body(
                when (preview) {
                    is Checked.Refused -> if (kind == "addKey" && source == NEW_KEY) "Adds a new key made on this device." else sentence(preview.message)
                    is Checked.Ok ->
                        if (kind == "addKey" && !advanced) "The other device can act for this identity."
                        else describeOperation(preview.value, withGuardian(w.names), w.advanced) { it in thisDevice }
                },
            )
            Caption(
                "Takes effect in ${relativeDuration(wait)}; until then any of your devices can cancel it." +
                    (if (advanced && lowering) " Lowering a lock waits out the current lock first." else "") +
                    (if (advanced && kind in RECOVERY_KINDS) " Once it applies, open recovery approvals no longer count." else "") +
                    (if (advanced) " Guardians reaching their threshold for a recovery void it." else ""),
            )
            fee.summary?.let { Caption("$it.") }
        }
        FeeRow(fee, boxed = true)
        Button("Propose", onClick = ::submit, loading = sending.busy != null, enabled = canSubmit && fee.problem == null)
        Spacer(Modifier.height(Spacing.lg))
    }
}

/** The delay presets from `min` to `max` seconds, the chosen one in ink. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DelayPresets(value: Long, onChange: (Long) -> Unit, min: Long = 0, max: Long? = null) {
    val options = DELAY_PRESETS.filter { it.seconds >= min && (max == null || it.seconds <= max) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        for (p in options) {
            Button(p.label, onClick = { onChange(p.seconds) }, kind = if (p.seconds == value) ButtonKind.Primary else ButtonKind.Ghost, small = true)
        }
    }
}
