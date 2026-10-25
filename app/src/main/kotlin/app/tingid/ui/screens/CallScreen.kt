// A program's call (TCR-1.md), from a call link: the program, the operation
// in the program's own words, and apart from them what Ting ID checked itself:
// the account that acts, what leaves it, and the fee. A call that pays is
// approved by its payment; one that does not, by a signature of its own.
package app.tingid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import app.tingid.chain.Link
import app.tingid.chain.Links
import app.tingid.chain.SlotValue
import app.tingid.chain.contractAccount
import app.tingid.chain.formatAmount
import app.tingid.chain.hex
import app.tingid.chain.nowSeconds
import app.tingid.chain.shortId
import app.tingid.state.Acting
import app.tingid.state.CallRequest
import app.tingid.state.SubmittedTx
import app.tingid.state.readCallRequest
import app.tingid.state.sent
import app.tingid.text.CallPart
import app.tingid.text.Names
import app.tingid.text.callLine
import app.tingid.text.callSentence
import app.tingid.text.listItemLine
import app.tingid.text.relativeDuration
import app.tingid.text.strangeAccounts
import app.tingid.ui.Banner
import app.tingid.ui.BannerTone
import app.tingid.ui.Button
import app.tingid.ui.ButtonKind
import app.tingid.ui.Caption
import app.tingid.ui.CallRoute
import app.tingid.ui.Card
import app.tingid.ui.Detail
import app.tingid.ui.DetailCard
import app.tingid.ui.DetailValue
import app.tingid.ui.HoldWhileBusy
import app.tingid.ui.IconCircle
import app.tingid.ui.Loading
import app.tingid.ui.LocalActivity
import app.tingid.ui.LocalNav
import app.tingid.ui.LocalServices
import app.tingid.ui.Mono
import app.tingid.ui.Pill
import app.tingid.ui.Radius
import app.tingid.ui.Screen
import app.tingid.ui.Spacing
import app.tingid.ui.Sub
import app.tingid.ui.Tone
import app.tingid.ui.TxRoute
import app.tingid.ui.Type
import app.tingid.ui.WalletTab
import app.tingid.ui.confirmOwner
import app.tingid.ui.formatTime
import app.tingid.ui.manropeStyle
import app.tingid.ui.money.FeeRow
import app.tingid.ui.money.rememberAccountViews
import app.tingid.ui.money.rememberFee
import app.tingid.ui.money.withFee
import app.tingid.ui.sentence
import app.tingid.ui.t
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicBoolean

/** Another link opened over this one starts afresh. */
@Composable
fun CallScreen(route: CallRoute) {
    key(route.code, route.at) { CallFlow(route.code ?: "") }
}

private sealed class Reading {
    data object Loading : Reading()
    class Failed(val message: String) : Reading()
    class Ready(val request: CallRequest) : Reading()
}

@Composable
private fun CallFlow(code: String) {
    val services = LocalServices.current
    val nav = LocalNav.current
    val w by services.workspace.state.collectAsState()
    val chainId = w.info?.status?.chainId
    val parsed = remember(code) { runCatching { Links.parseOf<Link.Call>(code, "call") } }
    var reading by remember { mutableStateOf<Reading>(Reading.Loading) }
    // Checked against the chain before anything of it shows (core: Calls).
    LaunchedEffect(code, chainId?.hex()) {
        val link = parsed.getOrElse {
            reading = Reading.Failed(it.message ?: "this is not a call link")
            return@LaunchedEffect
        }
        val id = chainId ?: return@LaunchedEffect
        reading = try {
            Reading.Ready(readCallRequest(w.node, services.store, link, id))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Reading.Failed(e.message ?: "the call could not be checked")
        }
    }
    when (val r = reading) {
        is Reading.Ready -> key(r.request.link.values.hex()) { Review(r.request) }
        is Reading.Failed -> Screen(title = "Program call") {
            Card {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = Spacing.sm),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    IconCircle(Icons.Outlined.ErrorOutline, t.danger, t.dangerSoft, 48.dp)
                    Sub("This call can’t be made")
                    Caption(sentence(r.message), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(Spacing.xs))
                    Button("Close", onClick = { nav.back() }, kind = ButtonKind.Secondary)
                }
            }
        }
        Reading.Loading -> Screen(title = "Program call") {
            Card {
                Loading()
                Caption("Checking the call against the chain…", Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Review(request: CallRequest) {
    val p = t
    val services = LocalServices.current
    val activity = LocalActivity.current
    val nav = LocalNav.current
    val ui = rememberCoroutineScope()
    val w by services.workspace.state.collectAsState()
    val link = request.link
    val payment = request.payment

    // Who may act: an account without a delay, or with one when the program schedules; the one the link names, if it names one.
    val choices = w.accounts.filter { (it.delay == 0L || link.delayed) && (link.from == null || it.account.hex() == link.from) }
    var chosen by remember { mutableStateOf<Long?>(null) }
    val account = choices.firstOrNull { it.slot == chosen } ?: choices.firstOrNull { it.delay == 0L } ?: choices.firstOrNull()
    val delayed = account != null && account.delay > 0
    // An account without a delay pays its fee inside its payment or fee payment, in the link's coin, when it pays fees in that coin.
    val acting = (payment?.coin ?: request.feeCoin)?.takeIf { account != null && account.delay == 0L }
    val fee = rememberFee(if (acting != null && account != null) Acting(acting, account) else null)
    val views = rememberAccountViews(listOfNotNull(payment?.coin), listOfNotNull(account)).views
    val available = payment?.let { views[it.coin.hex]?.firstOrNull()?.record?.available }
    val ownFee = if (payment != null && fee.own) fee.fee else BigInteger.ZERO
    val decimals = payment?.coin?.config?.decimals ?: 0
    val symbol = payment?.coin?.config?.symbol ?: ""

    // The program's own account is one the app knows: it follows from the program's ID.
    val names = w.names + (contractAccount(request.program).hex() to "this program’s own account")
    val paymentText = payment?.let { "${formatAmount(it.amount, decimals)} $symbol" }
    val parts = callSentence(link.template, link.decoded, paymentText, names, request.coins, ::formatTime)
    val line = callLine(parts)
    val strangers = strangeAccounts(link.decoded, names)
    val recipient = payment?.to?.hex()

    var busy by remember { mutableStateOf(false) }
    HoldWhileBusy(busy)
    val going = remember { AtomicBoolean(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // A call that just appeared, perhaps under the finger, cannot be approved by the same tap.
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(1000)
        armed = true
    }

    val problems = buildList {
        if (w.user == null) {
            add("add a user first")
        } else if (account == null) {
            val from = link.from
            add(if (from != null) "this call is for the account ${shortId(from)}, which is not one of yours" else "this user has no account that can make this call")
        }
        if (payment != null && available != null && payment.amount + ownFee > available) add("only ${formatAmount(available, decimals)} $symbol available")
        fee.problem?.let(::add)
    }
    val ready = problems.isEmpty() && w.info != null && account != null

    fun approve() {
        val now = w
        val user = now.user
        val info = now.info
        if (!ready || user == null || info == null) return
        val acting = account
        // A second tap before the first shows as busy sends nothing more.
        if (!going.compareAndSet(false, true)) return
        busy = true
        error = null
        ui.launch {
            val shown = try {
                if (!confirmOwner(activity, services, "Approve for this program", line)) {
                    going.set(false)
                    busy = false
                    return@launch
                }
                // Sent from the app's own scope: once the phone confirmed, leaving the screen stops nothing.
                services.scope.async(Dispatchers.IO) {
                    val signer = services.chain.resolveSigner(user, acting.slot, info.canonical, now.keys, now.identity)
                    val pays = payment?.let { it.coin.id to it.effect }
                    val r = sent {
                        services.chain.submitProgramCall(info.status.chainId, request.program, link.template.hash, link.values, signer, pays, request.feeCoin?.id, fee.resolve()).txId
                    }
                    services.store.addSubmitted(
                        SubmittedTx(
                            id = r.txId,
                            at = nowSeconds(),
                            summary = withFee(line, fee),
                            userId = user.id,
                            coin = payment?.coin?.hex,
                            account = payment?.let { acting.account.hex() },
                            amount = payment?.let { (it.amount + ownFee).toString() },
                            to = recipient,
                        ),
                    )
                    r
                }.await()
            } catch (e: Exception) {
                // Left while sending: the screen is gone, and what was begun goes on.
                if (e is CancellationException) throw e
                error = e.message ?: "it could not be sent"
                going.set(false)
                busy = false
                return@launch
            }
            nav.navigate(TxRoute(shown.txId, sent = true, unsure = shown.unsure, back = link.returnTo)) { popUpTo<CallRoute> { inclusive = true } }
        }
    }

    Screen(title = "Program call") {
        // Who asks: a program, by its ID, which Ting ID cannot vouch for.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val shape = RoundedCornerShape(Radius.md)
            Box(Modifier.size(40.dp).clip(shape).background(p.card).border(Dp.Hairline, p.border, shape), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Extension, null, tint = p.muted, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("A program Ting ID does not know", style = manropeStyle(15f, FontWeight.Medium), color = p.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Caption("Program ${shortId(request.program, 8, 6)}")
            }
            Pill("First use", Tone.Warning, small = true)
        }
        Banner(
            "Ting ID has no name for this program, so what it says below is its own claim. Check its ID with whoever gave you the link.",
            BannerTone.Warning,
        )

        // What the program says it does, in its own words, with the values as Ting ID reads them.
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.lg)).background(p.cardAlt).padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("The program says", style = Type.caption, color = p.faint)
            val text = buildAnnotatedString {
                for (part in parts) {
                    when (part) {
                        is CallPart.Words -> append(part.text)
                        is CallPart.Value -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = if (part.strange) p.warning else p.text)) { append(part.text) }
                    }
                }
            }
            Text(text, style = Type.heading, color = p.text)
            link.template.fields.zip(link.decoded).forEach { (slot, value) ->
                if (value is SlotValue.ListOf && value.items.isNotEmpty()) ListItems(slot.name, value, request, names)
            }
            for (hex in strangers) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Caption("Not a contact")
                    Mono(hex)
                }
            }
        }

        // What Ting ID checked itself: who acts, what leaves the account, until when.
        DetailCard {
            Detail("From", first = true) { DetailValue(account?.label ?: "—") }
            when {
                payment == null -> Detail("Pays") { DetailValue("Nothing but the fee") }
                recipient == null -> Detail("Burns") { DetailValue("${formatAmount(payment.amount, decimals)} $symbol") }
                else -> {
                    Detail("Pays") { DetailValue("${formatAmount(payment.amount, decimals)} $symbol") }
                    Detail("To") { DetailValue(names[recipient] ?: shortId(recipient)) }
                }
            }
            if (delayed) Detail("Takes effect") { DetailValue("in ${relativeDuration(account.delay)}, cancellable until then") }
            Detail("Can be used") { DetailValue("for an hour after you approve") }
        }
        if (recipient != null && !names.containsKey(recipient)) {
            Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Caption("Paid to an account that is not a contact")
                Mono(recipient)
            }
        }

        if (choices.size > 1) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Caption("Act as")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    for (a in choices) {
                        val selected = a.slot == account?.slot
                        val shape = RoundedCornerShape(999.dp)
                        Text(
                            if (a.delay > 0) "${a.label} · waits ${relativeDuration(a.delay)}" else a.label,
                            style = manropeStyle(13.5f, FontWeight.SemiBold),
                            color = p.text,
                            modifier = Modifier.clip(shape).background(p.card).border(Dp.Hairline, if (selected) p.ink else p.border, shape)
                                .clickable(enabled = !busy, role = Role.RadioButton) { chosen = a.slot }
                                .semantics { contentDescription = if (selected) "${a.label}, acting" else "Act as ${a.label}" }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }

        Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            FeeRow(fee, label = "Fee")
        }

        if (problems.isNotEmpty()) Banner(problems.joinToString(" ") { sentence(it) }, BannerTone.Warning)
        error?.let { Banner(sentence(it), BannerTone.Danger) }
        Button("Approve", onClick = ::approve, icon = Icons.Outlined.Fingerprint, large = true, loading = busy, enabled = ready && armed)
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Button("Decline", onClick = { nav.back() }, kind = ButtonKind.Ghost, enabled = !busy)
        }
        Spacer(Modifier.height(Spacing.lg))
    }
}

/** A list's items, each field by its name, shown when opened. */
@Composable
private fun ListItems(name: String, value: SlotValue.ListOf, request: CallRequest, names: Names) {
    val p = t
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.clickable(role = Role.Button) { open = !open },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                if (open) "Hide the ${value.items.size} $name" else "Show the ${value.items.size} $name",
                style = manropeStyle(13.5f, FontWeight.SemiBold),
                color = p.primary,
            )
            Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = p.primary, modifier = Modifier.size(18.dp))
        }
        if (open) {
            for (item in value.items.take(LIST_SHOWN)) Caption(listItemLine(value.fields, item, names, request.coins, ::formatTime))
            if (value.items.size > LIST_SHOWN) Caption("and ${value.items.size - LIST_SHOWN} more")
        }
    }
}

/** The most items of a list shown at once. */
private const val LIST_SHOWN = 200

/** Back to the screen before, or to the wallet when a link opened this one. */
private fun NavHostController.back() {
    if (previousBackStackEntry != null) popBackStack()
    else navigate(WalletTab) { popUpTo(graph.id) { inclusive = true } }
}
