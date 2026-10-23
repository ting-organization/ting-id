// Adding a user: a new identity, with a new recovery phrase; one restored
// from its recovery phrase, found on the chain by the key the words give; or
// one restored by its ID, which another device or the guardians hand over.
package app.tingid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import app.tingid.chain.DELAY_PRESETS
import app.tingid.chain.Id
import app.tingid.chain.Phrase
import app.tingid.chain.Scheme
import app.tingid.chain.SystemEntropy
import app.tingid.chain.hex
import app.tingid.chain.isHex
import app.tingid.chain.nowSeconds
import app.tingid.chain.publicKey
import app.tingid.chain.trimWhiteSpace
import app.tingid.chain.unhex
import app.tingid.chain.wipe
import app.tingid.state.ChainInfo
import app.tingid.state.KeyMeta
import app.tingid.state.PhraseEntry
import app.tingid.state.SubmittedTx
import app.tingid.state.User
import app.tingid.state.UserKind
import app.tingid.state.fetchIdentity
import app.tingid.state.findIdentitiesWithKey
import app.tingid.state.keyNameFor
import app.tingid.state.nextColor
import app.tingid.state.sent
import app.tingid.state.userIdentity
import app.tingid.text.relativeDuration
import app.tingid.ui.Avatar
import app.tingid.ui.Banner
import app.tingid.ui.BannerTone
import app.tingid.ui.Button
import app.tingid.ui.ButtonKind
import app.tingid.ui.Caption
import app.tingid.ui.Card
import app.tingid.ui.Field
import app.tingid.ui.HoldWhileBusy
import app.tingid.ui.IconCircle
import app.tingid.ui.IdentityTab
import app.tingid.ui.Info
import app.tingid.ui.ListItem
import app.tingid.ui.LocalActivity
import app.tingid.ui.LocalNav
import app.tingid.ui.LocalServices
import app.tingid.ui.NewUserRoute
import app.tingid.ui.NoAutofill
import app.tingid.ui.PhraseRoute
import app.tingid.ui.Pill
import app.tingid.ui.Screen
import app.tingid.ui.SecureWindow
import app.tingid.ui.Segmented
import app.tingid.ui.Spacing
import app.tingid.ui.Sub
import app.tingid.ui.Tone
import app.tingid.ui.identity.PhraseInput
import app.tingid.ui.sentence
import app.tingid.ui.t
import app.tingid.vault.NotConfirmed
import app.tingid.vault.Owner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** What is being added. */
private enum class Mode { New, Restore, Existing }

/** The two choices at the top: a new identity, or a restore. */
private enum class Top { New, Restore }

/** The ways to restore: the words, or the ID that another device or the guardians hand over. */
private enum class RestoreBy { Phrase, Id }

private class RestoreWay(val value: RestoreBy, val label: String, val hint: String, val icon: ImageVector)

private val RESTORE_BY = listOf(
    RestoreWay(RestoreBy.Phrase, "Recovery phrase", "The words you wrote down", Icons.Outlined.Lock),
    RestoreWay(RestoreBy.Id, "Identity ID", "Your identity’s ID: another of your phones lets this one in, or your guardians recover it", Icons.Outlined.Fingerprint),
)

/** A restore under way: the chain is read for identities that list the phrase's key. */
private sealed class Restore {
    class Reading(val height: Long, val tip: Long) : Restore()

    /** `words`: the words read for, which the identity is then restored with. */
    class Found(val ids: List<String>, val words: List<String>) : Restore()

    data object None : Restore()
}

/** A refusal with a message for the person. */
private class Refused(message: String) : Exception(message)

/** Whether `e` is the person stepping back from the phone's check, which needs no message. */
private fun stepsBack(e: Exception) = e is NotConfirmed && e.message == "cancelled"

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewUserScreen(route: NewUserRoute) {
    val services = LocalServices.current
    val activity = LocalActivity.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val data by services.store.data.collectAsState()
    val info by services.chain.info.collectAsState()
    val nodeError by services.chain.error.collectAsState()
    val users = data.users
    val keys = data.keys
    val advanced = data.settings.advanced
    val p = t
    // Every secret made here starts with the system's generator.
    val entropy = remember { SystemEntropy() }

    var name by remember { mutableStateOf("") }
    // `mode=restore`: opened to restore, as the welcome's Restore does.
    var top by remember { mutableStateOf(if (route.mode == "restore") Top.Restore else Top.New) }
    var restoreBy by remember { mutableStateOf(RestoreBy.Phrase) }
    val mode = if (top == Top.Restore) (if (restoreBy == RestoreBy.Id) Mode.Existing else Mode.Restore) else Mode.New
    // The typed words: here only, never saved, and cleared when the screen leaves.
    var words by remember { mutableStateOf(PhraseEntry.fields(Phrase.NEW_PHRASE_WORDS)) }
    var restore by remember { mutableStateOf<Restore?>(null) }
    var extraKeys by remember { mutableStateOf(listOf<String>()) }
    var existingId by remember { mutableStateOf("") }
    var guardians by remember { mutableStateOf(listOf<String>()) }
    var guardianHex by remember { mutableStateOf("") }
    var threshold by remember { mutableStateOf("0") }
    var locks by remember { mutableStateOf(false) }
    var changeDelay by remember { mutableLongStateOf(Id.DEFAULT_CHANGE_DELAY) }
    var recoveryDelay by remember { mutableLongStateOf(Id.DEFAULT_RECOVERY_DELAY) }
    var savingsDelay by remember { mutableLongStateOf(Id.DEFAULT_SLOTS[1]) }
    var busy by remember { mutableStateOf(false) }
    // Creating or restoring: the screen stays until it is done.
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // The scan that counts: words changed meanwhile make an older one's answer void.
    var scan by remember { mutableIntStateOf(0) }
    var scanJob by remember { mutableStateOf<Job?>(null) }

    HoldWhileBusy(sending)
    DisposableEffect(Unit) {
        onDispose {
            words = PhraseEntry.fields(Phrase.NEW_PHRASE_WORDS)
            restore = null
        }
    }
    // Words can be typed only while restoring by them: then nothing on screen is captured or autofilled.
    if (mode == Mode.Restore) {
        SecureWindow()
        NoAutofill()
    }

    val otherIdentities = users.mapNotNull { u -> userIdentity(u)?.let { u to it.id.hex() } }

    /** The identity tab, in place of this screen. */
    fun toIdentity() = nav.navigate(IdentityTab) {
        popUpTo(nav.graph.findStartDestination().id)
        launchSingleTop = true
    }

    /** A key made here for an identity that will not be: its secret leaves the vault, and the key the list. */
    suspend fun forgetKey(key: KeyMeta) {
        withContext(Dispatchers.IO) { runCatching { services.vault.deleteKey(key.id) } }
        services.store.removeKey(key.id)
    }

    /**
     * Makes the identity of `phrase` and submits it; returns the new user's id.
     * The user is saved first: an identity on chain that no user here names
     * would be lost from view. A refused submission takes the user, its words
     * and its key away again.
     */
    suspend fun createIdentity(phrase: Phrase, userName: String, color: String, i: ChainInfo): String {
        val c = i.canonical
        val key = services.keys.phraseKey(keyNameFor(userName), phrase)
        val idKeys = (listOf(key) + services.store.data.value.keys.filter { it.id in extraKeys && it.id != key.id })
            .map { Id.Key(c.policyFor(it.scheme), unhex(it.pub)) }
        val settings = Id.Settings(
            keys = idKeys,
            // The registry wants them in byte order, and the id is the hash of the creation input.
            guardians = Id.sortGuardians(guardians.map(::unhex)),
            threshold = trimWhiteSpace(threshold).toLongOrNull()?.takeIf { it >= 0 } ?: 0,
            changeDelay = changeDelay,
            recoveryDelay = recoveryDelay,
            slots = listOf(0, savingsDelay),
            salt = entropy.bytes(32),
        )
        val creation = Id.encodeCreation(settings)
        val identityId = Id.idOf(creation)
        Id.validSettings(settings, identityId)?.let { problem ->
            withContext(NonCancellable) { forgetKey(key) }
            throw Refused(problem)
        }
        val id = UUID.randomUUID().toString()
        val lock = try {
            services.phrases.savePhrase(id, phrase, Owner.prompt(activity))
        } catch (e: Exception) {
            // The words were never shown, so they are lost: their key goes with them.
            withContext(NonCancellable) { forgetKey(key) }
            throw e
        }
        services.store.addUser(
            User(
                id, userName, color, UserKind.Identity,
                identityId = identityId.hex(), registry = c.registry.hex(), chainId = i.chainHex, creation = creation.hex(),
                keyId = key.id, phrase = lock, phraseKeyId = key.id, phraseBackedUp = false,
            ),
        )
        val submitted = try {
            sent { services.chain.submitRegistryCall(c.registry, Id.createInput(creation)) }
        } catch (e: Exception) {
            // Left before the node answered: it may have the identity, so the user stays.
            if (e is CancellationException) throw e
            // Refused, so on no chain: the user and its words go, and its key with them.
            withContext(NonCancellable) {
                services.store.removeUser(id)
                runCatching { services.phrases.deletePhrase(id, lock) }
                forgetKey(key)
            }
            throw e
        }
        services.store.setActiveUser(id)
        services.store.addSubmitted(SubmittedTx(id = submitted.txId, at = nowSeconds(), summary = "Create identity $userName", userId = id))
        return id
    }

    fun create() {
        val adding = mode
        busy = true
        sending = true
        error = null
        scope.launch {
            try {
                val userName = trimWhiteSpace(name).ifEmpty { "Me" }
                val color = nextColor(services.store.data.value.users)
                val i = info ?: throw Refused(nodeError ?: "the node is not reachable")
                val c = i.canonical
                val node = services.chain.node
                if (adding == Mode.Existing) {
                    val h = trimWhiteSpace(existingId).lowercase()
                    if (!isHex(h, 32)) throw Refused("an identity ID is 64 hex digits")
                    services.store.data.value.users.firstOrNull { it.identityId == h && it.chainId == i.chainHex }
                        ?.let { throw Refused("${it.name} on this phone is this identity already") }
                    if (fetchIdentity(node, c.registry, unhex(h)) == null) throw Refused("no identity with that ID on this chain")
                    // This device's key for the identity; another device adds it, or a recovery installs it.
                    val key = services.keys.createDeviceKey(keyNameFor(userName))
                    val id = UUID.randomUUID().toString()
                    services.store.addUser(User(id, userName, color, UserKind.Identity, identityId = h, registry = c.registry.hex(), chainId = i.chainHex, keyId = key.id))
                    services.store.setActiveUser(id)
                    toIdentity()
                    return@launch
                }
                // The identity's first key is its recovery phrase's, kept on this device.
                Phrase.generate(entropy).use { phrase ->
                    val id = createIdentity(phrase, userName, color, i)
                    // The words first: the person writes them down while a block includes the identity.
                    services.phrases.rememberFresh(id, phrase)
                    nav.navigate(PhraseRoute(id)) { popUpTo<NewUserRoute> { inclusive = true } }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (!stepsBack(e)) error = e.message ?: "the identity could not be created"
            } finally {
                busy = false
                sending = false
            }
        }
    }

    /** Reads the phrase's key, then the chain for the identities that list it. */
    fun findRestore() {
        val text = words.joinToString(" ")
        Phrase.problem(text)?.let {
            error = it
            return
        }
        val i = info ?: run {
            error = nodeError ?: "the node is not reachable"
            return
        }
        val typed = words
        val mine = ++scan
        busy = true
        error = null
        restore = Restore.Reading(0, i.status.height)
        scanJob = scope.launch {
            try {
                // The words are stretched to their key: a moment's work, off the main thread.
                val pub = withContext(Dispatchers.Default) {
                    Phrase.parse(typed.joinToString(" ")).use { phrase ->
                        val secret = phrase.secret()
                        try {
                            publicKey(secret)
                        } finally {
                            secret.wipe()
                        }
                    }
                }
                val c = i.canonical
                val ids = findIdentitiesWithKey(services.chain.node, c.registry, Id.Key(c.policyFor(Scheme.Secp256k1), pub)) { height, tip ->
                    if (scan == mine) restore = Restore.Reading(height, tip)
                }
                if (scan != mine) return@launch
                restore = if (ids.isNotEmpty()) Restore.Found(ids.map { it.hex() }, typed) else Restore.None
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (scan != mine) return@launch
                restore = null
                error = e.message ?: "the chain could not be read"
            } finally {
                if (scan == mine) busy = false
            }
        }
    }

    /** Adds the restored identity, with the words it was found for; once, however often it is tapped. */
    fun finishRestore(identityHex: String) {
        val i = info ?: return
        val found = restore as? Restore.Found ?: return
        if (sending) return
        services.store.data.value.users.firstOrNull { it.identityId == identityHex && it.chainId == i.chainHex }?.let {
            error = "${it.name} on this phone is this identity already"
            return
        }
        sending = true
        busy = true
        error = null
        scope.launch {
            try {
                val userName = trimWhiteSpace(name).ifEmpty { "Me" }
                Phrase.parse(found.words.joinToString(" ")).use { phrase ->
                    val key = services.keys.phraseKey(keyNameFor(userName), phrase)
                    val id = UUID.randomUUID().toString()
                    val lock = services.phrases.savePhrase(id, phrase, Owner.prompt(activity))
                    // Restored from the words: they are written down already.
                    services.store.addUser(
                        User(
                            id, userName, nextColor(services.store.data.value.users), UserKind.Identity,
                            identityId = identityHex, registry = i.canonical.registry.hex(), chainId = i.chainHex,
                            keyId = key.id, phrase = lock, phraseKeyId = key.id, phraseBackedUp = true,
                        ),
                    )
                    services.store.setActiveUser(id)
                }
                words = PhraseEntry.fields(Phrase.NEW_PHRASE_WORDS)
                restore = null
                toIdentity()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (!stepsBack(e)) error = e.message ?: "the identity could not be restored"
            } finally {
                sending = false
                busy = false
            }
        }
    }

    Screen(title = "Add a user") {
        error?.let { Banner(sentence(it), BannerTone.Danger) }
        Segmented(listOf(Top.New to "New identity", Top.Restore to "Restore"), top) { top = it }
        if (top == Top.Restore) {
            Card(padded = false) {
                Column {
                    RESTORE_BY.forEachIndexed { i, r ->
                        val on = r.value == restoreBy
                        if (i > 0) Spacer(Modifier.fillMaxWidth().height(1.dp).background(p.border))
                        ListItem(
                            r.label,
                            subtitle = r.hint,
                            left = { IconCircle(r.icon, if (on) p.primary else p.muted, if (on) p.card else p.cardAlt) },
                            selected = on,
                            onClick = { restoreBy = r.value },
                            right = {
                                Icon(
                                    if (on) Icons.Outlined.RadioButtonChecked else Icons.Outlined.RadioButtonUnchecked,
                                    null,
                                    tint = if (on) p.primary else p.faint,
                                    modifier = Modifier.size(22.dp),
                                )
                            },
                        )
                    }
                }
            }
        }

        Card {
            val nameFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) {
                if (top == Top.New) runCatching { nameFocus.requestFocus() }
            }
            Field(name, { name = it }, Modifier.focusRequester(nameFocus), label = "Name", placeholder = "Your name")
            if (mode == Mode.New) Caption("An instant Spending account, a Savings account whose withdrawals wait, a recovery phrase, and guardians who can restore access.")
        }

        if (mode == Mode.Restore) {
            Card {
                PhraseInput(words) { w ->
                    words = w
                    restore = null
                    // A scan of the words before is void now.
                    scan++
                    scanJob?.cancel()
                    busy = false
                }
                RestoreResult(restore, onFinish = ::finishRestore, onById = { restoreBy = RestoreBy.Id })
                if (restore !is Restore.Found) {
                    Button(
                        "Find my identity",
                        onClick = ::findRestore,
                        loading = busy,
                        enabled = words.none { it.isEmpty() } && Phrase.problem(words.joinToString(" ")) == null,
                    )
                }
            }
        }

        if (mode == Mode.Existing) {
            Card {
                Field(existingId, { existingId = trimWhiteSpace(it) }, label = "Identity ID", placeholder = "64 hex digits", keyboardType = KeyboardType.Ascii)
                Caption("The Identity tab then shows a code to add on a device that has the identity. Or your guardians recover it here, with a new recovery phrase.")
            }
        }

        if (mode == Mode.New) {
            Card {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Sub("Guardians")
                    Info(
                        "Guardians",
                        "People you trust to restore your access. A majority of them must agree, and you can stop a recovery while it waits. With a single guardian, that one alone can hand your identity to new keys.",
                    )
                }
                Caption("Optional: you can add them later.")
                for ((user, h) in otherIdentities) {
                    val on = h in guardians
                    ListItem(
                        user.name,
                        subtitle = "identity on this device",
                        left = { Avatar(user.name, user.color, 32.dp) },
                        selected = on,
                        onClick = { guardians = if (on) guardians - h else guardians + h },
                        right = if (on) ({ Pill("guardian", Tone.Primary, small = true) }) else null,
                    )
                }
                for (g in guardians.filter { g -> otherIdentities.none { it.second == g } }) {
                    ListItem(
                        "${g.take(12)}…",
                        subtitle = "pasted ID",
                        right = { Button("Remove", onClick = { guardians = guardians - g }, kind = ButtonKind.Ghost, small = true) },
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Field(guardianHex, { guardianHex = it }, Modifier.weight(1f), placeholder = "Paste an identity ID", keyboardType = KeyboardType.Ascii)
                    Button(
                        "Add",
                        onClick = {
                            val pasted = trimWhiteSpace(guardianHex).lowercase()
                            val registry = info?.canonical?.registry
                            error = null
                            if (registry != null) {
                                scope.launch {
                                    val found = try {
                                        fetchIdentity(services.chain.node, registry, unhex(pasted))
                                    } catch (e: Exception) {
                                        if (e is CancellationException) throw e
                                        error = "the node did not answer: try again"
                                        return@launch
                                    }
                                    if (found == null) {
                                        error = "no identity has that ID on this network: a guardian must be an identity, not an account"
                                        return@launch
                                    }
                                    if (pasted !in guardians) guardians = guardians + pasted
                                    guardianHex = ""
                                }
                            }
                        },
                        kind = ButtonKind.Secondary,
                        small = true,
                        enabled = isHex(trimWhiteSpace(guardianHex), 32) && info != null,
                    )
                }
                if (advanced) Field(threshold, { threshold = it }, label = "Approvals needed (0 = more than half)", keyboardType = KeyboardType.Number)
            }

            // Everyone else gets the defaults: savings and changes wait 7 days, recovery 2.
            if (advanced) {
                Card {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Sub("Time locks", Modifier.weight(1f))
                        Button(if (locks) "Done" else "Change", onClick = { locks = !locks }, kind = ButtonKind.Ghost, small = true)
                    }
                    if (locks) {
                        Caption("Savings: withdrawals wait this long and can be cancelled meanwhile.")
                        Presets(savingsDelay, { savingsDelay = it }, min = 60)
                        Caption("Changes to devices, guardians and settings wait:")
                        Presets(changeDelay, { changeDelay = it }, min = 3600)
                        Caption("Guardians hand over the identity after:")
                        Presets(recoveryDelay, { recoveryDelay = it }, min = 60, max = changeDelay - 1)
                        Caption(
                            "Recovery (${relativeDuration(recoveryDelay)}) must be shorter than changes (${relativeDuration(changeDelay)}), so guardians can undo what a thief starts.",
                        )
                    } else {
                        Caption(
                            "Savings withdrawals wait ${relativeDuration(savingsDelay)}; changes to the identity wait ${relativeDuration(changeDelay)}; guardians can " +
                                "hand it over after ${relativeDuration(recoveryDelay)}. Everything stays cancellable while it waits.",
                        )
                    }
                }
            }
        }

        if (advanced && mode == Mode.New) {
            Card {
                Sub("Keys")
                Caption("The recovery phrase gives the identity its first key, a secp256k1 key on this device.")
                if (keys.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Caption("Also sign with existing keys:")
                        for (k in keys) {
                            val on = k.id in extraKeys
                            ListItem(
                                k.name,
                                subtitle = k.scheme.label,
                                selected = on,
                                onClick = { extraKeys = if (on) extraKeys - k.id else extraKeys + k.id },
                                right = if (on) ({ Pill("included", Tone.Primary, small = true) }) else null,
                            )
                        }
                    }
                }
            }
        }

        if (mode != Mode.Restore) {
            Button(
                if (mode == Mode.New) "Create identity" else "Add identity",
                onClick = ::create,
                loading = busy,
                enabled = info != null && !(mode == Mode.Existing && !isHex(existingId, 32)),
            )
        }
        if (mode == Mode.New) Caption("Next you write down its recovery phrase. The identity can receive funds as soon as it is created.")
        Spacer(Modifier.height(Spacing.lg))
    }
}
/** The delays offered, as buttons: the chosen one in ink. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Presets(value: Long, onChange: (Long) -> Unit, min: Long = 0, max: Long? = null) {
    val options = DELAY_PRESETS.filter { it.seconds >= min && (max == null || it.seconds <= max) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        for (o in options) Button(o.label, onClick = { onChange(o.seconds) }, kind = if (o.seconds == value) ButtonKind.Primary else ButtonKind.Ghost, small = true)
    }
}

/** What reading the chain for the phrase's identity found. */
@Composable
private fun RestoreResult(restore: Restore?, onFinish: (String) -> Unit, onById: () -> Unit) {
    when (restore) {
        is Restore.Reading -> Caption("Looking for the identity · block ${restore.height} of ${restore.tip}")
        is Restore.Found -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Caption(if (restore.ids.size == 1) "This phrase belongs to an identity:" else "This phrase belongs to more than one identity. Choose one:")
            for (id in restore.ids) {
                ListItem("Identity ${id.take(8)}…${id.takeLast(6)}", subtitle = "its keys list this phrase’s key", chevron = true, onClick = { onFinish(id) })
            }
        }
        Restore.None -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Banner(
                "No identity on this network lists this phrase’s key. Check the words and their order. If your guardians recovered the identity, or a change replaced the key, restore it by its ID instead.",
                BannerTone.Warning,
            )
            Button("Restore by its ID instead", onClick = onById, kind = ButtonKind.Secondary, small = true, icon = Icons.Outlined.Fingerprint)
        }
        null -> {}
    }
}
