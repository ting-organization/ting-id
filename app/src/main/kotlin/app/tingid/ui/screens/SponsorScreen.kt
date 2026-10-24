// Setting up someone's identity from their create link (LINKS.md): the
// identity they built on their phone, created here with this user paying the
// fee, and coins sent to its Spending to start with. Anyone may submit a
// creation, but only exactly as built, so the link cannot be turned into
// another identity; the name is only what the link says.
package app.tingid.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import app.tingid.chain.BatchCall
import app.tingid.chain.Coin
import app.tingid.chain.Id
import app.tingid.chain.formatAmount
import app.tingid.chain.hex
import app.tingid.chain.nowSeconds
import app.tingid.chain.parseAmount
import app.tingid.chain.shortId
import app.tingid.chain.trimWhiteSpace
import app.tingid.state.Acting
import app.tingid.state.Contact
import app.tingid.state.CreateLinkReading
import app.tingid.state.Sent
import app.tingid.state.SubmittedTx
import app.tingid.state.USER_COLORS
import app.tingid.state.fetchIdentity
import app.tingid.state.readCreateLink
import app.tingid.state.sent
import app.tingid.state.slotLabel
import app.tingid.text.relativeDuration
import app.tingid.ui.Avatar
import app.tingid.ui.Banner
import app.tingid.ui.BannerTone
import app.tingid.ui.Button
import app.tingid.ui.ButtonKind
import app.tingid.ui.Caption
import app.tingid.ui.Card
import app.tingid.ui.Detail
import app.tingid.ui.DetailCard
import app.tingid.ui.DetailValue
import app.tingid.ui.Field
import app.tingid.ui.HoldWhileBusy
import app.tingid.ui.IdChip
import app.tingid.ui.LocalActivity
import app.tingid.ui.LocalNav
import app.tingid.ui.LocalServices
import app.tingid.ui.Screen
import app.tingid.ui.Spacing
import app.tingid.ui.SponsorRoute
import app.tingid.ui.Sub
import app.tingid.ui.TxRoute
import app.tingid.ui.Type
import app.tingid.ui.WalletTab
import app.tingid.ui.confirmOwner
import app.tingid.ui.manropeStyle
import app.tingid.ui.money.CoinMark
import app.tingid.ui.money.FeeRow
import app.tingid.ui.money.ToneCaption
import app.tingid.ui.money.rememberAccountViews
import app.tingid.ui.money.rememberFee
import app.tingid.ui.money.rememberFeeCoin
import app.tingid.ui.money.withFee
import app.tingid.ui.sentence
import app.tingid.ui.t
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.math.BigInteger
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Another link opened over this one starts afresh. */
@Composable
fun SponsorScreen(route: SponsorRoute) {
    key(route.code, route.at) { SponsorFlow(route.code ?: "") }
}

@Composable
private fun SponsorFlow(code: String) {
    val services = LocalServices.current
    val nav = LocalNav.current
    val w by services.workspace.state.collectAsState()
    val info = w.info
    val reading = remember(code, info?.chainHex) { readCreateLink(code, info?.status?.chainId) }
    when (reading) {
        is CreateLinkReading.Ok -> Sponsor(reading)
        is CreateLinkReading.Problem -> Screen(title = "Set up an identity") {
            Banner(sentence(reading.message), BannerTone.Danger)
            Button("Back", onClick = { nav.back() }, kind = ButtonKind.Secondary)
        }
    }
}

@Composable
private fun Sponsor(ok: CreateLinkReading.Ok) {
    val p = t
    val services = LocalServices.current
    val activity = LocalActivity.current
    val nav = LocalNav.current
    val ui = rememberCoroutineScope()
    val w by services.workspace.state.collectAsState()
    val contacts = services.store.data.collectAsState().value.contacts
    val link = ok.link
    val name = link.name?.let(::trimWhiteSpace)?.ifEmpty { null }

    // The coin to start it with: the one the link asks for when followed, else the fee coin.
    val feeCoin = rememberFeeCoin(w)
    val asked = link.coin?.let { w.coinById[it] }
    val c = asked ?: feeCoin
    val askedOther = link.coin != null && asked == null
    val from = w.accounts.firstOrNull { it.delay == 0L }
    val views = rememberAccountViews(listOfNotNull(c), listOfNotNull(from)).views
    val view = c?.let { views[it.hex]?.firstOrNull() }
    val available = view?.record?.available

    // Until the person types, the amount is what the link asks, if in a coin this phone follows:
    // an amount asked in another coin is no amount of the coin sent instead.
    var typed by remember { mutableStateOf<String?>(null) }
    val amount = typed ?: if (askedOther) "" else link.amount ?: ""
    // A contact already called so is never shadowed by one a link names.
    val nameTaken = name != null && contacts.any { trimWhiteSpace(it.name).lowercase() == name.lowercase() }
    var saveContact by remember { mutableStateOf(!nameTaken) }
    var busy by remember { mutableStateOf(false) }
    HoldWhileBusy(busy)
    val going = remember { AtomicBoolean(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val creationFee = rememberFee(null)
    val paymentFee = rememberFee(if (c != null && from != null) Acting(c, from) else null)

    // Whether a block has the identity already: then only the coins are sent.
    var exists by remember { mutableStateOf<Boolean?>(null) }
    val info = w.info
    val registry = info?.canonical?.registry
    val node = w.node
    LaunchedEffect(node, registry?.hex(), info?.status?.height) {
        val r = registry ?: return@LaunchedEffect
        try {
            exists = fetchIdentity(node, r, ok.id) != null
        } catch (e: Exception) {
            // No node answers now: the next block asks again.
            if (e is CancellationException) throw e
        }
    }

    val spending = registry?.let { Id.account(it, ok.id, 0) }
    val decimals = c?.config?.decimals ?: 0
    val symbol = c?.config?.symbol ?: ""
    val value: BigInteger? = try {
        if (trimWhiteSpace(amount).isNotEmpty()) parseAmount(amount, decimals) else BigInteger.ZERO
    } catch (e: Exception) {
        null
    }
    val paying = value != null && value.signum() > 0
    val ownFee = if (paymentFee.own) paymentFee.fee else BigInteger.ZERO
    val creating = exists == false
    val problems = buildList {
        if (w.user == null || from == null) add("add a user with an account first")
        if (value == null) add("enter an amount with at most $decimals decimals")
        if (paying && available != null && value + ownFee > available) add("only ${formatAmount(available, decimals)} $symbol available")
        if (creating) creationFee.problem?.let(::add)
        if (paying) paymentFee.problem?.let(::add)
        if (exists == true && !paying) add("it is on chain already: enter coins to send it")
    }
    val ready = problems.isEmpty() && exists != null && info != null && spending != null
    val title = if (creating) {
        if (paying) "Create and send ${formatAmount(value, decimals)} $symbol" else "Create the identity"
    } else {
        "Send ${formatAmount(value ?: BigInteger.ZERO, decimals)} $symbol"
    }
    val who = name ?: "the identity"

    fun submit() {
        val now = w
        val user = now.user
        val i = now.info
        val account = from
        val coin = c
        if (!ready || user == null || i == null || account == null || coin == null) return
        // Ready means the identity's Spending is known.
        val to = spending
        // A second tap before the first shows as busy sends nothing more.
        if (!going.compareAndSet(false, true)) return
        val sending = value ?: BigInteger.ZERO
        val create = creating
        val save = saveContact
        busy = true
        error = null
        ui.launch {
            val shown = try {
                if (!confirmOwner(activity, services, if (create) "Set up $who" else "Send to $who", if (sending.signum() > 0) "${formatAmount(sending, decimals)} $symbol" else null)) {
                    going.set(false)
                    busy = false
                    return@launch
                }
                // Sent from the app's own scope: once the phone confirmed, leaving the screen stops nothing.
                services.scope.async(Dispatchers.IO) {
                    val chainId = i.status.chainId
                    val canon = i.canonical
                    var made: Sent? = null
                    if (create) {
                        val payer = creationFee.resolve()
                        made = sent { services.chain.submitWithFee(canon, chainId, BatchCall(canon.registry, Id.createInput(link.input)), payer) }
                        services.store.addSubmitted(SubmittedTx(id = made.txId, at = nowSeconds(), summary = withFee("Create $who", creationFee), userId = user.id))
                    }
                    if (sending.signum() > 0) {
                        val signer = services.chain.resolveSigner(user, account.slot, canon, now.keys, now.identity)
                        val action = Coin.Action(Coin.Effect.Transfer(to, sending), Coin.NO_TIMING)
                        val payer = paymentFee.resolve()
                        val r = sent { services.chain.submitCoinAction(chainId, coin.id, signer, action, payer = payer).txId }
                        services.store.addSubmitted(
                            SubmittedTx(
                                id = r.txId,
                                at = nowSeconds(),
                                summary = withFee("Send ${formatAmount(sending, decimals)} $symbol to $who", paymentFee),
                                userId = user.id,
                                coin = coin.hex,
                                account = account.account.hex(),
                                amount = (sending + ownFee).toString(),
                                to = to.hex(),
                            ),
                        )
                        made = Sent(r.txId, r.unsure || made?.unsure == true)
                    }
                    if (save && name != null && !now.names.containsKey(to.hex())) {
                        services.store.addContact(Contact(UUID.randomUUID().toString(), name, to.hex()))
                    }
                    made ?: throw IllegalStateException("nothing to send")
                }.await()
            } catch (e: Exception) {
                // Left while sending: the screen is gone, and what was begun goes on.
                if (e is CancellationException) throw e
                error = e.message ?: "it could not be sent"
                going.set(false)
                busy = false
                return@launch
            }
            nav.navigate(TxRoute(shown.txId, sent = true, unsure = shown.unsure)) { popUpTo<SponsorRoute> { inclusive = true } }
        }
    }

    val settings = ok.settings
    Screen(title = "Set up an identity") {
        Card {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Avatar(name ?: "?", USER_COLORS[0], 48.dp, soft = true)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(if (name != null) "Set up $name" else "Set up an identity", style = Type.heading, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Caption(if (name != null) "That is the name the link gives: check it with whoever sent it." else "The link gives no name.")
                }
            }
            IdChip(ok.id.hex(), label = "ID")
        }

        if (exists == true) Banner("This identity is on chain already: only the coins are sent.")
        if (exists == false) Banner("It is not on chain yet: this creates it, exactly as built on their phone.")

        DetailCard {
            Detail("Keys", first = true) { DetailValue("${settings.keys.size}") }
            Detail("Guardians") { DetailValue(if (settings.guardians.isEmpty()) "None yet" else "${settings.guardians.size}") }
            settings.slots.forEachIndexed { slot, delay ->
                Detail(slotLabel(slot.toLong())) { DetailValue(if (delay != 0L) "withdrawals wait ${relativeDuration(delay)}" else "instant") }
            }
            Detail("Changes wait") { DetailValue(relativeDuration(settings.changeDelay)) }
        }

        if (c != null) {
            Card {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CoinMark(c, 36.dp)
                    Column(Modifier.weight(1f)) {
                        Sub("Coins to start with")
                        Caption("To its Spending, from your ${from?.label ?: "account"}${available?.let { " · ${formatAmount(it, decimals)} $symbol available" } ?: ""}")
                    }
                }
                Field(
                    amount,
                    { typed = it },
                    label = "Amount ($symbol)",
                    placeholder = "0",
                    keyboardType = KeyboardType.Decimal,
                    hint = if (link.amount != null && !askedOther) "The link asks for ${link.amount} $symbol." else "Optional: 0 creates it without coins.",
                )
                if (askedOther) {
                    ToneCaption(
                        "The link asks for ${link.amount?.let { "$it of " } ?: ""}coin ${shortId(link.coin ?: "")}, which you do not follow. Anything entered here is sent in $symbol.",
                        p.warning,
                    )
                }
            }
        }

        if (creating || paying) {
            Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                if (creating) FeeRow(creationFee, label = "Creation fee")
                if (paying) FeeRow(paymentFee, label = "Payment fee")
            }
        }

        if (name != null && spending != null && !w.names.containsKey(spending.hex())) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text("Save $name as a contact", Modifier.weight(1f), style = manropeStyle(14.5f, FontWeight.SemiBold), color = p.text)
                Switch(
                    checked = saveContact,
                    onCheckedChange = { saveContact = it },
                    colors = SwitchDefaults.colors(checkedTrackColor = p.primary, uncheckedTrackColor = p.border),
                    modifier = Modifier.semantics { contentDescription = "Save $name as a contact" },
                )
            }
        }
        if (name != null && nameTaken) ToneCaption("A contact is called $name already: saving this one makes two.", p.warning, Modifier.padding(horizontal = 4.dp))

        if (problems.isNotEmpty() && exists != null) Banner(problems.joinToString(" ") { sentence(it) }, BannerTone.Warning)
        error?.let { Banner(sentence(it), BannerTone.Danger) }
        Button(if (exists == null) "Looking it up…" else title, onClick = ::submit, large = true, loading = busy, enabled = ready)
        Spacer(Modifier.height(Spacing.lg))
    }
}

/** Back to the screen before, or to the wallet when a link opened this one. */
private fun NavHostController.back() {
    if (previousBackStackEntry != null) popBackStack()
    else navigate(WalletTab) { popUpTo(graph.id) { inclusive = true } }
}
