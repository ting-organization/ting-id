// Sending coins: the account to send from, the amount, the recipient (typed,
// picked, or from a pay link scanned or opened), when it goes and the fee.
// Review shows it once more and sends it, with the submit made here.
package app.tingid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import app.tingid.chain.Coin
import app.tingid.chain.Id
import app.tingid.chain.Link
import app.tingid.chain.LinkError
import app.tingid.chain.Links
import app.tingid.chain.WHITE_SPACE
import app.tingid.chain.formatAmount
import app.tingid.chain.hex
import app.tingid.chain.isHex
import app.tingid.chain.nowSeconds
import app.tingid.chain.shortId
import app.tingid.chain.trimWhiteSpace
import app.tingid.chain.unhex
import app.tingid.state.Acting
import app.tingid.state.Chain
import app.tingid.state.LinkRoutes
import app.tingid.state.LinkSource
import app.tingid.state.LinkTarget
import app.tingid.state.OnTheWay
import app.tingid.state.PaymentDraft
import app.tingid.state.PaymentFee
import app.tingid.state.PaymentInput
import app.tingid.state.Sent
import app.tingid.state.SubmittedTx
import app.tingid.state.UserKind
import app.tingid.state.When
import app.tingid.state.checkPayment
import app.tingid.state.coinGroups
import app.tingid.state.fetchIdentity
import app.tingid.state.groupTyped
import app.tingid.state.linkedDeviceKeys
import app.tingid.state.onTheWay
import app.tingid.state.paymentDrafts
import app.tingid.state.recipients
import app.tingid.state.sent
import app.tingid.state.ungroupTyped
import app.tingid.text.relativeDuration
import app.tingid.ui.Avatar
import app.tingid.ui.Banner
import app.tingid.ui.BannerTone
import app.tingid.ui.Button
import app.tingid.ui.ButtonKind
import app.tingid.ui.Caption
import app.tingid.ui.Card
import app.tingid.ui.ConfirmHost
import app.tingid.ui.ConfirmOptions
import app.tingid.ui.Field
import app.tingid.ui.IconButton
import app.tingid.ui.IdentityTab
import app.tingid.ui.ListItem
import app.tingid.ui.LocalNav
import app.tingid.ui.LocalServices
import app.tingid.ui.QrScanner
import app.tingid.ui.Radius
import app.tingid.ui.ReviewRoute
import app.tingid.ui.SaveContactSheet
import app.tingid.ui.Screen
import app.tingid.ui.SendTab
import app.tingid.ui.Sheet
import app.tingid.ui.Spacing
import app.tingid.ui.Sub
import app.tingid.ui.TABULAR
import app.tingid.ui.Type
import app.tingid.ui.Welcome
import app.tingid.ui.WhenChoice
import app.tingid.ui.WhenPicker
import app.tingid.ui.manropeStyle
import app.tingid.ui.money.AccountPicker
import app.tingid.ui.money.CoinMark
import app.tingid.ui.money.FeeRow
import app.tingid.ui.money.FeeRun
import app.tingid.ui.money.SelectedAccount
import app.tingid.ui.money.accountNote
import app.tingid.ui.money.rememberAccountViews
import app.tingid.ui.money.rememberFee
import app.tingid.ui.money.withFee
import app.tingid.ui.openLink
import app.tingid.ui.rememberConfirm
import app.tingid.ui.sentence
import app.tingid.ui.t
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import kotlin.math.min

/** Whether the recipient `to` was found to be an identity's ID rather than an account. */
private class IdCheck(val to: String, val identity: Boolean)

/**
 * The send form. It lives as long as its screen is in the back stack, not
 * only while it shows: Review opens over it, Edit comes back to everything
 * as entered, and the payment sent from Review empties it.
 */
private class SendForm(route: SendTab) : ViewModel() {
    var to by mutableStateOf("")
    var amount by mutableStateOf("")
    var choice by mutableStateOf(WhenChoice.Now)
    var at by mutableLongStateOf(When.defaultLater(nowSeconds()))
    var codeNotice by mutableStateOf<String?>(null)

    /** The coin a pay link asked for: nothing is sent in another one. */
    var codeCoin by mutableStateOf<String?>(null)

    /** What the pay link said the payment is for. */
    var note by mutableStateOf<String?>(null)

    /** The coin and account opened with or chosen here, over the ones used last. */
    var coin by mutableStateOf(route.coin)
    var slot by mutableStateOf(route.slot)

    // Whether the recipient is an identity's ID rather than an account: IDs are shared too, and coins
    // sent to one reach no account anyone can spend from.
    var idCheck by mutableStateOf<IdCheck?>(null)

    /** The route last taken in, so a changed one is taken once. */
    var route: SendTab = route

    /** The pay link last applied, with when it was opened: the same link opened again applies again. */
    var appliedCode: String? = null

    init {
        takeTo(route.to)
    }

    /** Opened for a recipient, as a contact's Send does. */
    fun takeTo(account: String?) {
        if (account != null && isHex(account, 32)) to = trimWhiteSpace(account).lowercase()
    }

    fun forgetCode() {
        codeNotice = null
        codeCoin = null
        note = null
    }

    /** Sent: the form is empty for the next payment. */
    fun clear() {
        amount = ""
        to = ""
        choice = WhenChoice.Now
        at = When.defaultLater(nowSeconds())
        forgetCode()
    }
}

/** Where a payment stands between Send and the chain: the submit sends it once. */
private const val IDLE = 0
private const val SENDING = 1
private const val SENT = 2

@Composable
fun SendScreen(route: SendTab) {
    val services = LocalServices.current
    val nav = LocalNav.current
    val p = t
    val form = viewModel { SendForm(route) }
    val data by services.store.data.collectAsState()
    val w by services.workspace.state.collectAsState()
    val cache by services.caches.current.collectAsState()
    val confirm = rememberConfirm()
    // The coin and account opened from, else the ones used last, else the first followed coin's Spending.
    val selectedCoin = form.coin?.let { w.coinById[it] } ?: data.activeCoin?.let { w.coinById[it] } ?: w.coins.firstOrNull()
    val allViews = rememberAccountViews(w.coins, w.accounts).views
    val views = selectedCoin?.let { allViews[it.hex] } ?: emptyList()
    var fromSheet by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    val from = views.firstOrNull { it.account.slot == (form.slot ?: data.activeSlot.toLong()) } ?: views.firstOrNull()
    // The fee goes in the fee coin from Settings. A time-locked account pays none
    // itself: nothing leaves it at once (the coin reverts `delayed fee`), so an
    // instant account pays for it, Spending by default.
    val fee = rememberFee(if (selectedCoin != null && from != null) Acting(selectedCoin, from.account) else null)
    val pendingItems = remember(services) { services.pending.items() }
    val pending by pendingItems.collectAsState(initial = emptyList())
    val decimals = selectedCoin?.config?.decimals ?: 0
    val symbol = selectedCoin?.config?.symbol ?: ""
    val registry = w.info?.canonical?.registry

    // A route with other parameters, on this same form, is taken in once.
    LaunchedEffect(route) {
        if (form.route == route) return@LaunchedEffect
        if (route.coin != form.route.coin || route.slot != form.route.slot) {
            form.coin = route.coin
            form.slot = route.slot
        }
        if (route.to != form.route.to) form.takeTo(route.to)
        form.route = route
    }

    LaunchedEffect(form.to, registry?.hex(), w.node) {
        val to = form.to
        if (registry == null || !isHex(to, 32) || w.names.containsKey(to)) return@LaunchedEffect
        try {
            val identity = fetchIdentity(w.node, registry, unhex(to))
            form.idCheck = IdCheck(to, identity != null)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
        }
    }

    // A pay link, scanned or pasted, fills the recipient, the amount it asks
    // for and, when it names a followed coin, picks that coin. Gives what is
    // wrong with it, if anything.
    val applyCode: (String) -> String? = apply@{ text ->
        val link = try {
            Links.parseOf<Link.Pay>(text, "pay")
        } catch (e: Exception) {
            return@apply sentence(e.message ?: "this is not a pay link")
        }
        val info = w.info
        if (info != null && link.net != Links.netOf(info.status.chainId)) return@apply "This link is for another Ting network."
        form.to = link.to
        form.codeNotice = null
        form.codeCoin = link.coin
        form.note = link.note
        link.amount?.let { form.amount = it }
        val asked = link.coin
        if (asked != null && asked != selectedCoin?.hex) {
            if (w.coinById[asked] != null) {
                services.store.setActiveCoin(asked)
                form.coin = asked
                form.slot = from?.account?.slot ?: 0L
            } else {
                form.codeNotice = "The link asks for coin ${shortId(asked)}, which you do not follow. Follow it in Settings to send it."
            }
        }
        null
    }
    val currentApplyCode by rememberUpdatedState(applyCode)

    val scanned: (String) -> String? = { text ->
        // A pay link fills this form; any other link opens its own screen.
        try {
            val target = LinkRoutes.routeOf(text, LinkSource.Inside)
            if (target !is LinkTarget.Send) {
                scanning = false
                nav.openLink(target)
                null
            } else {
                val problem = applyCode(text)
                if (problem == null) scanning = false
                problem
            }
        } catch (e: LinkError) {
            sentence(e.message ?: "this is not a Ting link")
        }
    }

    // A pay link scanned elsewhere, or opened from outside the app, arrives as
    // `code`, with when it was opened: the same link opened again applies again.
    LaunchedEffect(route.code, route.at) {
        val code = route.code ?: return@LaunchedEffect
        val opening = "${route.at} $code"
        if (form.appliedCode == opening) return@LaunchedEffect
        form.appliedCode = opening
        // What was typed stays unless the person takes the new code instead.
        val typed = form.amount.isNotBlank() || form.to.isNotBlank()
        val yes = !typed || confirm.ask(
            ConfirmOptions(title = "Use the new pay link?", body = "It replaces the recipient and amount you entered.", confirm = "Use the link", back = "Keep what I entered"),
        )
        if (!yes) return@LaunchedEffect
        form.amount = ""
        currentApplyCode(code)?.let { form.codeNotice = it }
    }

    // What payments still on their way take from the account is not there to send again.
    val outgoing = if (from != null && selectedCoin != null) onTheWay(pending, selectedCoin.hex, from.account.account.hex()) else OnTheWay(0, BigInteger.ZERO)
    val later = form.choice == WhenChoice.Later
    val idCheck = form.idCheck
    val check = checkPayment(
        PaymentInput(
            amount = form.amount,
            to = form.to,
            coin = selectedCoin,
            from = from,
            outgoing = outgoing.amount,
            fee = PaymentFee(fee.fee, fee.own, fee.problem, fee.usual, fee.feeCoin, fee.options, fee.payer, selectedCoin?.let { fee.runTip(it) } ?: BigInteger.ZERO),
            later = later,
            at = form.at,
            now = nowSeconds(),
            toIsIdentity = idCheck != null && idCheck.to == form.to && idCheck.identity,
            codeCoin = form.codeCoin,
        ),
    )
    val recipientSet = isHex(form.to, 32)
    // The name this device knows the recipient by: a user's account or a contact.
    val toLabel = if (recipientSet) w.names[form.to] else null

    /** Hands the payment to the review page, with the submit that sends it from there. */
    fun review() {
        val user = w.user ?: return
        val info = w.info ?: return
        val c = selectedCoin ?: return
        val account = from?.account ?: return
        val amount = check.parsed ?: return
        if (!check.ready) return
        val to = form.to
        val name = toLabel
        val chosenAt = if (later) form.at else null
        val lock = check.lock
        val willSchedule = check.willSchedule
        val runFee = check.runFee
        val needed = check.needed
        val keys = w.keys
        val identity = w.identity
        val paying = fee
        val chain = services.chain
        val store = services.store
        // It runs at the later of the chosen time and the end of the account's lock.
        fun runsAt(now: Long) = maxOf(chosenAt ?: 0L, now + lock)
        val progress = AtomicInteger(IDLE)
        paymentDrafts.setDraft(
            PaymentDraft(
                coin = c,
                amount = amount,
                from = PaymentDraft.From("${user.name} · ${account.label}", account.account.hex()),
                to = PaymentDraft.To(to),
                runsAt = if (willSchedule) runsAt(nowSeconds()) else null,
                chosenAt = chosenAt,
                lock = lock,
                fee = paying.summary,
                feeWarning = check.feeWarning,
                runFee = runFee,
                total = needed,
                available = check.availableNow,
                submit = {
                    if (!progress.compareAndSet(IDLE, SENDING)) {
                        throw IllegalStateException(if (progress.get() == SENT) "this payment was sent already" else "this payment is being sent")
                    }
                    try {
                        if (chosenAt != null && chosenAt <= nowSeconds() + 60) throw IllegalStateException("the chosen time has passed: go back and choose a later one")
                        val signer = chain.resolveSigner(user, account.slot, info.canonical, keys, identity)
                        val action = Coin.Action(Coin.Effect.Transfer(unhex(to), amount), Coin.Timing(tip = runFee, notBefore = chosenAt ?: 0L))
                        val payer = paying.resolve()
                        var done: Chain.Submitted? = null
                        // With its answer lost, the payment may be in the node: it is followed as sent, never signed again.
                        val out = sent { chain.submitCoinAction(info.status.chainId, c.id, signer, action, payer = payer).also { done = it }.txId }
                        progress.set(SENT)
                        val signed = if (out.unsure) out.action else done?.let { it.account to it.nonce }
                        var queued = false
                        if (willSchedule && signed != null) {
                            // The node runs it when due: hand it the run now.
                            queued = try {
                                chain.queueRun(c.id, signed.first, signed.second, runsAt(nowSeconds()))
                                true
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                false
                            }
                        }
                        val summary = "${if (willSchedule) "Schedule" else "Send"} ${formatAmount(amount, c.config.decimals)} ${c.config.symbol} to ${name ?: shortId(to)}" +
                            if (willSchedule && !queued) " · run it from the wallet when due" else ""
                        store.addSubmitted(
                            SubmittedTx(
                                id = out.txId,
                                at = nowSeconds(),
                                summary = withFee(summary, paying),
                                userId = user.id,
                                coin = c.hex,
                                account = account.account.hex(),
                                amount = needed.toString(),
                                to = to,
                            ),
                        )
                        // Sent: the form is empty for the next payment.
                        withContext(Dispatchers.Main) { form.clear() }
                        Sent(out.txId, out.unsure)
                    } catch (e: Exception) {
                        progress.compareAndSet(SENDING, IDLE)
                        throw e
                    }
                },
            ),
        )
        nav.navigate(ReviewRoute)
    }

    // The question about a new pay link can come whatever the screen shows.
    ConfirmHost(confirm)
    val user = w.user
    if (user == null) {
        Screen(title = "Send") { Welcome() }
        return
    }
    // An identity this phone signs for no key of cannot send from here yet.
    val identity = w.identity
    val info = w.info
    if (user.kind == UserKind.Identity && identity != null && info != null && linkedDeviceKeys(info.canonical, identity, w.keys).isEmpty()) {
        Screen(title = "Send") {
            Card {
                Sub("This phone can’t act for ${user.name} yet")
                Caption("Its balances show on the wallet, but sending needs a key the identity lists. Add this phone from one that has access, or recover the identity.")
                Button("Open Identity", onClick = { nav.toTab(IdentityTab) }, kind = ButtonKind.Secondary, small = true, icon = Icons.Outlined.Fingerprint)
            }
        }
        return
    }

    Screen(title = "Send") {
        if (selectedCoin != null) {
            CardRows(
                Modifier.clip(RoundedCornerShape(Radius.lg)).clickable(role = Role.Button) { fromSheet = true }
                    .semantics { contentDescription = "Change the account to send from" },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CoinMark(selectedCoin, 38.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text("${user.name} · ${from?.account?.label ?: "…"}", style = manropeStyle(14.5f, FontWeight.Bold), color = p.text)
                        val line = if (from != null) {
                            buildAnnotatedString {
                                withStyle(SpanStyle(fontFeatureSettings = TABULAR)) { append(formatAmount(check.availableNow, decimals)) }
                                append(" $symbol available")
                                if (outgoing.count > 0) append(" (${if (outgoing.count == 1) "a payment" else "${outgoing.count} payments"} on the way)")
                                append(" · ${accountNote(from)}")
                            }
                        } else {
                            AnnotatedString("Loading…")
                        }
                        Text(line, style = Type.caption, color = p.muted)
                    }
                    Icon(Icons.Outlined.KeyboardArrowDown, null, tint = p.faint, modifier = Modifier.size(18.dp))
                }
                if (from != null && from.account.delay != 0L) {
                    Caption("Withdrawals from ${from.account.label} wait ${relativeDuration(from.account.delay)}; the fee comes from an instant account.")
                }
            }
        }

        AmountEntry(
            amount = form.amount,
            onAmount = { form.amount = it },
            symbol = symbol,
            dotCaution = check.dotCaution,
            // The most that leaves the fee, and a scheduled run's fee, payable.
            max = if (from != null && check.maxAmount.signum() > 0) formatAmount(check.maxAmount, decimals).replace(Regex("[$WHITE_SPACE]"), "") else null,
        )

        CardRows {
            if (recipientSet) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(toLabel ?: "#", "", 40.dp, soft = true)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Caption("To")
                        Text(toLabel ?: "New recipient", style = manropeStyle(15f, FontWeight.Bold), color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(form.to, style = Type.mono.copy(fontSize = 12.sp), color = p.faint)
                        form.note?.let { Caption("For: $it", maxLines = 2) }
                        if (idCheck != null && idCheck.to == form.to && idCheck.identity && registry != null) {
                            Button(
                                "This is an identity ID: send to its Spending",
                                onClick = { form.to = Id.account(registry, unhex(form.to), 0).hex() },
                                kind = ButtonKind.Ghost,
                                small = true,
                                icon = Icons.Outlined.SwapHoriz,
                            )
                        }
                        if (toLabel == null) Button("Save as contact", onClick = { naming = true }, kind = ButtonKind.Ghost, small = true, icon = Icons.Outlined.PersonAdd)
                    }
                    Button(
                        "Change",
                        onClick = {
                            form.to = ""
                            form.forgetCode()
                        },
                        kind = ButtonKind.Ghost,
                        small = true,
                    )
                }
            } else {
                Caption("To")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(
                        form.to,
                        { v ->
                            if (Links.isLink(v)) {
                                applyCode(v)?.let { form.codeNotice = it }
                            } else {
                                form.to = trimWhiteSpace(v).lowercase()
                                form.forgetCode()
                            }
                        },
                        Modifier.weight(1f),
                        placeholder = "Account or pay link",
                        keyboardType = KeyboardType.Ascii,
                    )
                    IconButton(Icons.Outlined.People, "Recipients", onClick = { picker = true })
                    IconButton(Icons.Outlined.QrCodeScanner, "Scan a code", onClick = { scanning = true })
                }
            }
        }

        WhenPicker(form.choice, form.at, check.lock, onChoice = { form.choice = it }, onAt = { form.at = it })

        Box(Modifier.padding(horizontal = 4.dp)) {
            FeeRow(fee, run = if (selectedCoin != null && check.runFee.signum() > 0) FeeRun(check.runFee, selectedCoin) else null)
        }

        val codeNotice = form.codeNotice
        val codeCoin = form.codeCoin
        if (codeNotice != null) {
            Banner(codeNotice, BannerTone.Warning)
        } else if (check.coinMismatch && codeCoin != null) {
            Banner("The pay link asks for ${w.coinById[codeCoin]?.config?.symbol ?: shortId(codeCoin)}; choose that coin to send.", BannerTone.Warning)
        }
        if (check.problems.isNotEmpty() && (form.amount.isNotEmpty() || form.to.isNotEmpty())) {
            Banner(check.problems.joinToString(" ") { sentence(it) }, BannerTone.Warning)
        }
        check.feeWarning?.let { Banner(it, BannerTone.Warning) }
        Button("Review", onClick = ::review, large = true, enabled = check.ready)
    }

    if (fromSheet) {
        AccountPicker(
            onClose = { fromSheet = false },
            groups = coinGroups(w.coins, allViews, every = true),
            selected = if (selectedCoin != null && from != null) SelectedAccount(selectedCoin.hex, from.account.slot) else null,
            onSelect = { c, slot ->
                services.store.setActiveCoin(c.hex)
                services.store.setActiveSlot(slot.toInt())
                form.coin = c.hex
                form.slot = slot
                // The link's coin stays as long as its recipient does: changing the account does not lift it.
            },
        )
    }
    if (scanning) QrScanner(onClose = { scanning = false }, onScan = scanned)
    if (picker) {
        val list = recipients(data.users, data.keys, data.contacts.filter { it.chain == null || it.chain == cache?.chain }, info?.canonical, user.id, from?.account?.slot)
        Sheet(onClose = { picker = false }, title = "Recipient") {
            if (list.isEmpty()) Caption("No contacts yet.")
            for (r in list) {
                key(r.account + r.label) {
                    ListItem(
                        r.label,
                        subtitle = r.sub,
                        left = { Avatar(r.label, r.color, 36.dp) },
                        onClick = {
                            form.to = r.account
                            form.forgetCode()
                            picker = false
                        },
                    )
                }
            }
        }
    }
    if (recipientSet && naming) SaveContactSheet(form.to, onClose = { naming = false })
}

/** A card with rows in it, less tall than the usual card. */
@Composable
private fun CardRows(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier, padded = false) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(Spacing.sm), content = content)
    }
}

/**
 * The amount, large, with the coin's symbol beside it: as wide as its figures,
 * in smaller figures when long, its whole part grouped as it is typed. `max`:
 * the most the account can send, as the Max button fills it in.
 */
@Composable
private fun AmountEntry(amount: String, onAmount: (String) -> Unit, symbol: String, dotCaution: String?, max: String?) {
    val p = t
    val fontScale = LocalDensity.current.fontScale
    val shown = groupTyped(amount.ifEmpty { "0" })
    val size = if (shown.length > 9) 38f else 52f
    val marks = shown.count { it !in '0'..'9' }
    // As wide as its figures, so the symbol sits beside them.
    val width = min(320f * fontScale, ceil(((shown.length - marks) * 0.58f + marks * 0.3f) * size * fontScale) + 12f)
    val style = manropeStyle(size, FontWeight.ExtraBold, tabular = true).copy(color = p.text, letterSpacing = (-2).sp, textAlign = TextAlign.Center)
    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.md), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            BasicTextField(
                value = amount,
                onValueChange = { onAmount(ungroupTyped(it)) },
                modifier = Modifier.width(width.dp).alignByBaseline().semantics { contentDescription = "Amount in $symbol" },
                textStyle = style,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                visualTransformation = GroupedAmount,
                cursorBrush = SolidColor(p.primary),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.Center) {
                        if (amount.isEmpty()) Text("0", style = style.copy(color = p.faint))
                        inner()
                    }
                },
            )
            Text(symbol, Modifier.alignByBaseline(), style = manropeStyle(20f, FontWeight.Bold), color = p.faint)
        }
        if (dotCaution != null) Caption(dotCaution)
        if (max != null) {
            val shape = RoundedCornerShape(999.dp)
            Text(
                "Max",
                Modifier.clip(shape).background(p.card).border(1.dp, p.border, shape)
                    .clickable(role = Role.Button) { onAmount(max) }
                    .semantics { contentDescription = "Send the most this account can" }
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                style = manropeStyle(12.5f, FontWeight.Bold),
                color = p.muted,
            )
        }
    }
}

/** An amount shown with its whole part in groups of three, as typed: only spaces are added. */
private object GroupedAmount : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val shown = groupTyped(raw)
        if (shown == raw) return TransformedText(text, OffsetMapping.Identity)
        // Where each typed character shows, and how many typed ones come before each shown one.
        val toShown = IntArray(raw.length + 1)
        val toRaw = IntArray(shown.length + 1)
        var i = 0
        for (j in shown.indices) {
            toRaw[j] = i
            if (i < raw.length && shown[j] == raw[i]) toShown[i++] = j
        }
        toShown[raw.length] = shown.length
        toRaw[shown.length] = raw.length
        return TransformedText(
            AnnotatedString(shown),
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int) = toShown[offset.coerceIn(0, raw.length)]
                override fun transformedToOriginal(offset: Int) = toRaw[offset.coerceIn(0, shown.length)]
            },
        )
    }
}

/** Opens a tab as the tab bar does, each tab keeping what it showed. */
private fun NavHostController.toTab(route: Any) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
