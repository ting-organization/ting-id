// Settings: security, the coins followed, fees, nodes, what was signed for
// sites, the theme, the advanced and coin tools switches, the keys on this
// device, the recovery phrase, deleting everything, and the version.
package app.tingid.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.tingid.chain.Canonical
import app.tingid.chain.formatAmount
import app.tingid.chain.hex
import app.tingid.chain.isHex
import app.tingid.chain.relativeTime
import app.tingid.chain.shortId
import app.tingid.chain.trimWhiteSpace
import app.tingid.state.KeyMeta
import app.tingid.state.SignatureKind
import app.tingid.state.SignatureNote
import app.tingid.state.SignatureOutcome
import app.tingid.state.Theme
import app.tingid.state.User
import app.tingid.state.UserKind
import app.tingid.state.WorkspaceState
import app.tingid.state.fetchCoin
import app.tingid.state.isStandardCoin
import app.tingid.state.keyUsers
import app.tingid.state.knownCoinsFor
import app.tingid.state.nextColor
import app.tingid.text.siteName
import app.tingid.ui.Banner
import app.tingid.ui.BannerTone
import app.tingid.ui.Button
import app.tingid.ui.ButtonKind
import app.tingid.ui.Caption
import app.tingid.ui.Card
import app.tingid.ui.ConfirmHost
import app.tingid.ui.ConfirmOptions
import app.tingid.ui.ConfirmState
import app.tingid.ui.Field
import app.tingid.ui.IconCircle
import app.tingid.ui.IdChip
import app.tingid.ui.ListItem
import app.tingid.ui.LocalServices
import app.tingid.ui.Pill
import app.tingid.ui.Screen
import app.tingid.ui.SectionHeader
import app.tingid.ui.Segmented
import app.tingid.ui.Sheet
import app.tingid.ui.Spacing
import app.tingid.ui.Sub
import app.tingid.ui.Tone
import app.tingid.ui.identity.PhraseCard
import app.tingid.ui.money.CoinMark
import app.tingid.ui.money.FeeSettings
import app.tingid.ui.money.Hairline
import app.tingid.ui.money.NodeSettings
import app.tingid.ui.rememberConfirm
import app.tingid.ui.sentence
import app.tingid.ui.settings.AboutSettings
import app.tingid.ui.settings.SecuritySettings
import app.tingid.ui.t
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.UUID

/** Where keys are kept, said wherever keys are listed. */
private const val KEYSTORE_NOTE = "Keys are kept in the device keystore. Your recovery phrases are their only copy outside this device."

/** A standard contract the chain should have, and its canonical ID there. */
private class Standard(val label: String, val idIn: (Canonical) -> ByteArray)

private val STANDARD = listOf(
    Standard("secp256k1 policy") { it.policySecp256k1 },
    Standard("P-256 policy") { it.policyP256 },
    Standard("Multisig policy") { it.multisig },
    Standard("Identity registry") { it.registry },
)

@Composable
fun SettingsScreen() {
    val services = LocalServices.current
    val w by services.workspace.state.collectAsState()
    val data by services.store.data.collectAsState()
    val confirm = rememberConfirm()
    val settings = data.settings
    // Whether each standard contract is on the chain, by label; missing while not asked yet.
    var deployed by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    val info = w.info
    val node = w.node
    LaunchedEffect(node, info?.chainHex, info?.status?.height) {
        val i = info ?: return@LaunchedEffect
        try {
            deployed = coroutineScope { STANDARD.map { s -> async { s.label to node.contractExists(s.idIn(i.canonical)) } }.awaitAll().toMap() }
        } catch (e: Exception) {
            // No node answers: the last answer stands.
            if (e is CancellationException) throw e
        }
    }
    val missing = STANDARD.filter { deployed[it.label] == false }.map { it.label }

    Screen(title = "Settings") {
        SecuritySettings()
        CoinsSection(w, confirm)
        FeeSettings(w)
        NodeSettings(w, missing)
        SignaturesSection(data.signatures.filter { it.userId == w.user?.id }, confirm)

        SectionHeader("Appearance")
        Card {
            Segmented(
                listOf(Theme.System to "System", Theme.Light to "Light", Theme.Dark to "Dark"),
                settings.theme,
            ) { theme -> services.store.setSettings { it.copy(theme = theme) } }
        }

        SectionHeader(
            "Advanced",
            info = "Shows keys, signature schemes, the identity's time locks and recovery threshold, contract IDs and chain capacity. When this is off, the app manages keys by itself and identities keep the default time locks.",
        )
        Card { SwitchRow("Show keys and technical details", settings.advanced) { v -> services.store.setSettings { it.copy(advanced = v) } } }

        SectionHeader(
            "Coin tools",
            info = "A Coins tab that lists every coin on the chain, creates new coins, and mints coins whose minter is one of your accounts. Off, Ting ID shows only the coins you follow.",
        )
        Card { SwitchRow("Browse, create and mint coins", settings.coinTools) { v -> services.store.setSettings { it.copy(coinTools = v) } } }

        if (settings.advanced) {
            KeysSection(w, data.keys, data.users, confirm)
            if (info != null) {
                SectionHeader("Chain")
                Card(padded = false) {
                    Column {
                        val format = NumberFormat.getIntegerInstance()
                        Caption(
                            "Capacity ${info.status.limits.joinToString(" / ") { format.format(it) }} (C / S / D / G) per block. Standard contracts are canonical deployments derived from the chain ID.",
                            Modifier.padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.lg, bottom = 4.dp),
                        )
                        for (s in STANDARD) {
                            Hairline()
                            val on = deployed[s.label]
                            ListItem(
                                s.label,
                                subtitleContent = { IdChip(s.idIn(info.canonical).hex()) },
                                right = if (on == null) null else ({ Pill(if (on) "deployed" else "missing", if (on) Tone.Success else Tone.Danger, small = true) }),
                            )
                        }
                    }
                }
            }
        }

        w.user?.let { PhraseCard(it, w.identity, info?.canonical, w.keys, loading = w.identityLoading) }

        DeviceSection(data.users, data.keys, confirm)

        AboutSettings()
        Spacer(Modifier.height(Spacing.lg))
    }
    ConfirmHost(confirm)
}

/** A setting that is on or off: its title and a switch. */
@Composable
private fun SwitchRow(title: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) { Sub(title) }
        Switch(
            checked = value,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = t.primary, uncheckedTrackColor = t.border),
            modifier = Modifier.semantics { contentDescription = title },
        )
    }
}

/** The coins followed, those this chain lacks, adding one by its contract ID, and the ones to suggest. */
@Composable
private fun CoinsSection(w: WorkspaceState, confirm: ConfirmState) {
    val p = t
    val services = LocalServices.current
    val scope = rememberCoroutineScope()
    val followed = services.store.data.collectAsState().value.settings.coins
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    val known = w.info?.let { i -> knownCoinsFor(i.chainHex).filter { it.contract !in followed } } ?: emptyList()
    // Coins the user's accounts hold without following them; the registry is no coin to suggest.
    val excluded = followed + listOfNotNull(w.info?.canonical?.registry?.hex())
    val found by remember(w.scope, w.accounts.map { it.account.hex() }, excluded) { services.coins.discovered(w.scope, w.accounts, excluded) }
        .collectAsState(initial = emptyList())

    fun add(id: String) {
        adding = true
        error = null
        val node = w.node
        scope.launch {
            try {
                val h = trimWhiteSpace(id).lowercase()
                if (!isHex(h, 32)) throw IllegalArgumentException("a contract ID is 64 hex digits")
                if (!isStandardCoin(node, h)) throw IllegalArgumentException("that contract does not run the standard coin")
                if (fetchCoin(node, services.store, h) == null) throw IllegalArgumentException("that coin is not initialized on this chain")
                services.store.addCoin(h)
                input = ""
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = e.message ?: "the coin could not be added"
            } finally {
                adding = false
            }
        }
    }

    SectionHeader(
        "Coins",
        info = "Follow any coin that runs the standard coin contract by its contract ID. The wallet verifies the contract's code before adding it.",
    )
    Card(padded = false) {
        Column {
            if (w.coins.isEmpty() && w.missingCoins.isEmpty()) Caption("No coins followed yet.", Modifier.padding(Spacing.lg))
            w.coins.forEachIndexed { i, c ->
                if (i > 0) Hairline()
                ListItem(
                    c.config.name,
                    left = { CoinMark(c) },
                    subtitleContent = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Caption(c.config.symbol)
                            IdChip(c.hex)
                        }
                    },
                    right = {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = "Unfollow ${c.config.name}",
                            tint = p.muted,
                            modifier = Modifier.clip(CircleShape).clickable(role = Role.Button) {
                                scope.launch {
                                    val yes = confirm.ask(
                                        ConfirmOptions(
                                            title = "Unfollow ${c.config.name}?",
                                            body = "It disappears from this wallet. What you hold stays on chain, and you can follow it again here.",
                                            confirm = "Unfollow",
                                            back = "Keep following",
                                            danger = true,
                                        ),
                                    )
                                    if (yes) services.store.removeCoin(c.hex)
                                }
                            }.padding(6.dp).size(20.dp),
                        )
                    },
                )
            }
            for (h in w.missingCoins) {
                Hairline()
                ListItem(
                    shortId(h, 10, 6),
                    subtitle = "not on this chain",
                    right = { Button("Remove", onClick = { services.store.removeCoin(h) }, kind = ButtonKind.Ghost, small = true) },
                )
            }
            Hairline()
            Column(Modifier.fillMaxWidth().padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Field(input, { input = it }, label = "Add a coin by contract ID", placeholder = "64 hex digits", keyboardType = KeyboardType.Ascii, error = error)
                Row {
                    Button("Add", onClick = { add(input) }, kind = ButtonKind.Secondary, small = true, icon = Icons.Outlined.Add, loading = adding, enabled = isHex(input, 32))
                }
            }
        }
    }
    if (known.isNotEmpty() || found.isNotEmpty()) {
        SectionHeader("Suggested", icon = Icons.Outlined.AutoAwesome)
        Card(padded = false) {
            Column {
                known.forEachIndexed { i, k ->
                    if (i > 0) Hairline()
                    ListItem(k.name, subtitle = "known on this chain", right = { Button("Follow", onClick = { add(k.contract) }, kind = ButtonKind.Secondary, small = true) })
                }
                found.forEachIndexed { i, f ->
                    if (i > 0 || known.isNotEmpty()) Hairline()
                    ListItem(
                        f.info.config.name,
                        left = { CoinMark(f.info) },
                        subtitle = "you hold ${formatAmount(f.total, f.info.config.decimals)} ${f.info.config.symbol}",
                        right = { Button("Follow", onClick = { services.store.addCoin(f.info.hex) }, kind = ButtonKind.Secondary, small = true) },
                    )
                }
            }
        }
    }
}

/** What this user signed for sites on this device, newest first, and clearing the list. */
@Composable
private fun SignaturesSection(signatures: List<SignatureNote>, confirm: ConfirmState) {
    val p = t
    val services = LocalServices.current
    val scope = rememberCoroutineScope()
    SectionHeader(
        "Signed for sites",
        info = "Requests of sites this user answered on this device: sign-ins, permissions and statements. Scan a site's code from the wallet to sign in with Ting ID.",
        action = if (signatures.isNotEmpty()) "Clear" else null,
        onAction = {
            scope.launch {
                val yes = confirm.ask(
                    ConfirmOptions(
                        title = "Clear what you signed for sites?",
                        body = "The list goes from this device. What was signed stays signed: sites keep what they got.",
                        confirm = "Clear the list",
                        back = "Keep it",
                        danger = true,
                    ),
                )
                if (yes) services.store.clearSignatures()
            }
        },
    )
    Card(padded = false) {
        Column {
            if (signatures.isEmpty()) Caption("Nothing signed for a site yet.", Modifier.padding(Spacing.lg))
            signatures.take(30).forEachIndexed { i, n ->
                if (i > 0) Hairline()
                val signed = n.outcome == SignatureOutcome.Signed
                val failed = n.outcome == SignatureOutcome.Failed
                val icon = when {
                    !signed -> Icons.Outlined.Cancel
                    n.kind == SignatureKind.SignIn -> Icons.AutoMirrored.Outlined.Login
                    n.kind == SignatureKind.Grant -> Icons.Outlined.Key
                    else -> Icons.Outlined.Description
                }
                val outcome = n.outcome.name.lowercase()
                ListItem(
                    n.title,
                    left = {
                        IconCircle(
                            icon,
                            if (signed) p.primary else if (failed) p.danger else p.muted,
                            if (signed) p.primarySoft else if (failed) p.dangerSoft else p.cardAlt,
                        )
                    },
                    subtitle = "${siteName(n.origin)} · $outcome ${relativeTime(n.at)}${n.message?.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""}",
                )
            }
        }
    }
}

/** The keys on this device (advanced): who each serves, adding an unused one as a user, deleting it, and making a new one. */
@Composable
private fun KeysSection(w: WorkspaceState, keys: List<KeyMeta>, users: List<User>, confirm: ConfirmState) {
    val services = LocalServices.current
    val scope = rememberCoroutineScope()
    val identities by remember(users) { services.chain.identities(users) }.collectAsState(initial = emptyMap())
    var problem by remember { mutableStateOf<String?>(null) }
    var sheet by remember { mutableStateOf(false) }

    fun delete(k: KeyMeta) {
        scope.launch {
            val yes = confirm.ask(
                ConfirmOptions(
                    title = "Delete key ${k.name}?",
                    body = "Its secret is erased from this device and cannot be brought back. Anything its own account holds can then never be spent.",
                    confirm = "Delete key",
                    back = "Keep it",
                    danger = true,
                ),
            )
            if (!yes) return@launch
            problem = null
            try {
                // Erased and unlisted together, even if the screen goes meanwhile.
                withContext(NonCancellable + Dispatchers.IO) {
                    services.vault.deleteKey(k.id)
                    services.store.removeKey(k.id)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                problem = "${k.name} was not deleted: ${e.message}"
            }
        }
    }

    SectionHeader("Keys on this device", action = "New key", onAction = { sheet = true })
    Card(padded = false) {
        Column {
            Column(
                Modifier.fillMaxWidth().padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.lg, bottom = if (keys.isNotEmpty()) 4.dp else Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Caption(KEYSTORE_NOTE)
                if (keys.isEmpty()) Caption("No keys yet; creating a user makes one.")
                problem?.let { Banner(sentence(it), BannerTone.Danger) }
            }
            keys.forEachIndexed { i, k ->
                if (i > 0) Hairline()
                // Who a key belongs to: the user it was made for, or an identity it signs for.
                val (used, unknown) = keyUsers(k, users, identities, w.info?.canonical)
                val serves = when {
                    used.isNotEmpty() -> used.joinToString(", ")
                    unknown.isNotEmpty() -> "may sign for ${unknown.joinToString(", ")}"
                    else -> "unused"
                }
                ListItem(
                    k.name,
                    subtitle = "${k.scheme.label} · ${shortId(k.pub, 10, 6)} · $serves",
                    right = if (used.isNotEmpty() || unknown.isNotEmpty()) null else ({
                        Row {
                            Button(
                                "Add as user",
                                onClick = { services.store.addUser(User(UUID.randomUUID().toString(), k.name, nextColor(users), UserKind.Key, keyId = k.id)) },
                                kind = ButtonKind.Ghost,
                                small = true,
                            )
                            Button("Delete…", onClick = { delete(k) }, kind = ButtonKind.Ghost, small = true)
                        }
                    }),
                )
            }
        }
    }
    if (sheet) NewKeySheet(keys.size, onClose = { sheet = false })
}

/** Making a key on this device, named by the person. Ting ID makes secp256k1 keys only (chain/Keys). */
@Composable
private fun NewKeySheet(count: Int, onClose: () -> Unit) {
    val services = LocalServices.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    Sheet(onClose = onClose, title = "New key") {
        Field(name, { name = it }, label = "Name", placeholder = "Backup")
        Caption("A secp256k1 key: the common curve, cheapest to verify.")
        error?.let { Banner(sentence(it), BannerTone.Danger) }
        Button(
            "Create",
            loading = creating,
            onClick = {
                if (!creating) {
                    creating = true
                    error = null
                    scope.launch {
                        try {
                            // A key made is kept and listed even if the sheet goes meanwhile.
                            withContext(NonCancellable) { services.keys.createDeviceKey(trimWhiteSpace(name).ifEmpty { "Key ${count + 1}" }) }
                            name = ""
                            onClose()
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            error = e.message ?: "the key could not be made"
                        } finally {
                            creating = false
                        }
                    }
                }
            },
        )
    }
}

/** What this device holds, and deleting all of it after typing to confirm. */
@Composable
private fun DeviceSection(users: List<User>, keys: List<KeyMeta>, confirm: ConfirmState) {
    val services = LocalServices.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    // Users whose words are only on this phone, named before everything is deleted.
    val unwritten = users.filter { it.phrase != null && !it.phraseBackedUp }.map { it.name }

    fun wipe() {
        scope.launch {
            val yes = confirm.ask(
                ConfirmOptions(
                    title = "Delete everything on this device?",
                    body = "Users, contacts, keys, recovery phrases, settings and activity are deleted from this device. Identities and funds stay on chain: write down your recovery phrases first, as a key that only this device holds is gone for good." +
                        (if (unwritten.isNotEmpty()) " Not written down yet: the recovery phrase${if (unwritten.size == 1) "" else "s"} of ${unwritten.joinToString(", ")}." else ""),
                    confirm = "Delete everything",
                    back = "Keep everything",
                    danger = true,
                    typeToConfirm = "delete everything",
                ),
            )
            if (!yes) return@launch
            error = null
            try {
                // Once begun, deleting goes to the end even if the screen goes.
                val kept = withContext(NonCancellable) { services.keys.deleteEverything() }
                if (kept.isNotEmpty()) {
                    error = "The keystore did not delete ${kept.joinToString(", ")}: still on this device. Delete everything again to remove ${if (kept.size == 1) "it" else "them"}."
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = "Not everything was deleted: ${e.message}"
            }
        }
    }

    SectionHeader("This device")
    Card {
        Caption("${users.size} user${if (users.size == 1) "" else "s"}, ${keys.size} key${if (keys.size == 1) "" else "s"}. $KEYSTORE_NOTE")
        Row { Button("Delete everything on this device…", onClick = ::wipe, kind = ButtonKind.Danger, small = true) }
        error?.let { Banner(sentence(it), BannerTone.Danger) }
    }
}
