// A transaction followed until a block settles it: where it stands, the
// payment it made, its block and result, and what happened in it.
package app.tingid.ui.screens

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.QuestionMark
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.navigation.NavHostController
import app.tingid.chain.Id
import app.tingid.chain.TransactionLookup
import app.tingid.chain.equal
import app.tingid.chain.formatAmount
import app.tingid.chain.fromUtf8
import app.tingid.chain.hex
import app.tingid.chain.nowSeconds
import app.tingid.chain.shortId
import app.tingid.chain.unhex
import app.tingid.state.ActivityItem
import app.tingid.state.TxPayment
import app.tingid.state.TxStage
import app.tingid.state.isFeeEvent
import app.tingid.state.lastOccurrence
import app.tingid.state.paymentOf
import app.tingid.state.printable
import app.tingid.state.scheduledIn
import app.tingid.state.txEvents
import app.tingid.state.txStage
import app.tingid.text.describeActivity
import app.tingid.text.statusWords
import app.tingid.ui.AccountBox
import app.tingid.ui.Banner
import app.tingid.ui.BannerTone
import app.tingid.ui.Button
import app.tingid.ui.ButtonKind
import app.tingid.ui.Caption
import app.tingid.ui.Card
import app.tingid.ui.IconCircle
import app.tingid.ui.IdChip
import app.tingid.ui.Loading
import app.tingid.ui.LocalActivity
import app.tingid.ui.LocalNav
import app.tingid.ui.LocalServices
import app.tingid.ui.Screen
import app.tingid.ui.Spacing
import app.tingid.ui.Sub
import app.tingid.ui.TABULAR
import app.tingid.ui.Tone
import app.tingid.ui.TxRoute
import app.tingid.ui.Type
import app.tingid.ui.WalletTab
import app.tingid.ui.formatTime
import app.tingid.ui.manropeStyle
import app.tingid.ui.money.iconOf
import app.tingid.ui.money.toneOf
import app.tingid.ui.sentence
import app.tingid.ui.t
import app.tingid.ui.toneColors
import java.text.NumberFormat

/** The head of the screen for each stage: its mark, its words and its tone. */
private class Head(val icon: ImageVector, val label: String, val tone: Tone)

@Composable
fun TxScreen(route: TxRoute) {
    val services = LocalServices.current
    val activity = LocalActivity.current
    val nav = LocalNav.current
    val p = t
    val data by services.store.data.collectAsState()
    val w by services.workspace.state.collectAsState()
    // `sent`: opened right after sending, so leaving goes to the wallet.
    // `unsure`: the node's answer to the submission was lost.
    val leave: () -> Unit = {
        if (route.sent) nav.toWallet()
        else if (nav.previousBackStackEntry != null) nav.popBackStack()
        else nav.toWallet()
    }
    BackHandler(enabled = route.sent) { leave() }
    val submitted = data.submitted.firstOrNull { it.id == route.id }

    // Asked every few seconds until a block settles it. A lookup that fails
    // keeps the last one found and says why.
    var lookup by remember(route.id) { mutableStateOf<TransactionLookup?>(null) }
    var error by remember(route.id) { mutableStateOf<String?>(null) }
    var missing by remember(route.id) { mutableStateOf(false) }
    LaunchedEffect(route.id, w.node) {
        services.chain.transaction(route.id).collect { r ->
            if (r.error != null) {
                error = r.error
            } else {
                lookup = r.lookup
                missing = r.missing
                error = null
            }
        }
    }

    val mine = w.accounts.map { it.account }
    val occurrence = lastOccurrence(lookup)
    val receipt = occurrence?.receipt
    val deferred = lookup?.deferred
    val events = txEvents(receipt, w.coinById)
    // A payment for later is in a block once scheduled; it runs at its time, in another transaction.
    val scheduled = scheduledIn(events)
    val stage = txStage(lookup, missing, events)
    val runsAt = scheduled?.executeAt ?: 0L
    val head = when (stage) {
        TxStage.Ok -> Head(Icons.Outlined.Check, "Confirmed", Tone.Success)
        TxStage.Scheduled -> Head(Icons.Outlined.CalendarMonth, if (runsAt > nowSeconds()) "Scheduled · runs ${formatTime(runsAt)}" else "Scheduled for ${formatTime(runsAt)}", Tone.Accent)
        TxStage.Failed -> Head(Icons.Outlined.Close, "Failed", Tone.Danger)
        TxStage.Pending -> Head(Icons.Outlined.Schedule, "Waiting for a block", Tone.Warning)
        TxStage.Deferred -> Head(Icons.Outlined.HourglassEmpty, "Held for its time", Tone.Accent)
        TxStage.Unknown -> Head(Icons.Outlined.QuestionMark, "Unknown to the node", Tone.Neutral)
        TxStage.Loading -> Head(Icons.Outlined.MoreHoriz, "Looking it up…", Tone.Neutral)
    }
    val (headBg, headFg) = toneColors(head.tone)
    val to = submitted?.to
    val payment = paymentOf(events, mine, w.coinById) ?: if (to != null) TxPayment("To", unhex(to)) else null
    val paymentName = payment?.let { w.names[it.account.hex()] }
    // What it was: as this phone sent it, else the payment the chain shows, such as one received.
    val headline = submitted?.summary ?: payment?.let { pay ->
        val who = paymentName ?: shortId(pay.account, 6, 4)
        val amount = pay.amount
        val coin = pay.coin
        val what = if (amount != null && coin != null) "${formatAmount(amount, coin.config.decimals)} ${coin.config.symbol} " else ""
        "$what${if (pay.label == "From") "from" else "to"} $who".replaceFirstChar { it.uppercase() }
    } ?: "Transaction"

    Screen(title = "Transaction") {
        Column(Modifier.fillMaxWidth().padding(vertical = Spacing.lg), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            IconCircle(head.icon, headFg, headBg, 72.dp)
            Text(headline, style = Type.heading, color = p.text, textAlign = TextAlign.Center)
            Text(head.label, style = manropeStyle(14f, FontWeight.Medium), color = headFg, textAlign = TextAlign.Center)
        }
        if (route.unsure && (stage == TxStage.Pending || stage == TxStage.Unknown || stage == TxStage.Loading)) {
            Banner(
                "No answer from the node. It may still go through: it is listed under Waiting on the wallet, and Ting ID sends it again unchanged if the node lost it. Don’t send it again.",
                BannerTone.Warning,
            )
        }
        error?.let { Banner(sentence(it), BannerTone.Danger) }
        if (stage == TxStage.Loading) Loading()
        if (stage == TxStage.Deferred && deferred != null) {
            val last = deferred.lastResult
            Banner(
                "Held by the node until ${formatTime(deferred.notBefore)}, then included as soon as it succeeds${if (last != null) " (last attempt: $last)" else ""}.",
                BannerTone.Info,
                Icons.Outlined.HourglassEmpty,
            )
        }
        if (stage == TxStage.Unknown && !route.unsure) {
            val problem = submitted?.problem
            if (problem != null) {
                Banner(sentence(problem), BannerTone.Danger)
            } else {
                Banner(
                    "The node does not know this transaction. One just sent may still be on its way; one the node dropped is sent again from Waiting on the wallet.",
                    BannerTone.Info,
                    Icons.AutoMirrored.Outlined.HelpOutline,
                )
            }
        }
        if (stage == TxStage.Failed && receipt != null) {
            Banner(sentence(if (printable(receipt.result)) fromUtf8(receipt.result) else statusWords(receipt.statusName)), BannerTone.Danger)
        }

        if (payment != null) {
            Card {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Caption(payment.label, Modifier.weight(1f))
                    Text(paymentName ?: "Not in your contacts", style = Type.body.copy(fontWeight = FontWeight.Medium), color = p.text)
                }
                if (paymentName != null) {
                    IdChip(payment.account.hex(), label = "Account")
                } else {
                    AccountBox(payment.account.hex())
                }
                val amount = payment.amount
                val coin = payment.coin
                if (amount != null && coin != null) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Caption("Amount", Modifier.weight(1f))
                        Text(
                            "${formatAmount(amount, coin.config.decimals)} ${coin.config.symbol}",
                            style = Type.body.copy(fontWeight = FontWeight.Medium, fontFeatureSettings = TABULAR),
                            color = p.text,
                        )
                    }
                }
            }
        }

        Card(padded = false) {
            Column {
                TxDetail("Transaction", first = true) { IdChip(route.id) }
                if (occurrence != null) TxDetail("Block") { Text(occurrence.height.toString(), style = Type.body, color = p.text) }
                if (receipt != null && w.advanced) {
                    val numbers = NumberFormat.getIntegerInstance(LocalConfiguration.current.locales[0])
                    TxDetail("Cost") { Caption("${receipt.used.joinToString(" / ") { numbers.format(it) }} C / S / D / G") }
                }
                if (receipt != null && receipt.status == 0 && receipt.result.size == 32) TxDetail("Result") { IdChip(receipt.result.hex()) }
                if (receipt != null && receipt.status == 0 && printable(receipt.result) && receipt.result.size != 32) {
                    TxDetail("Result") { Caption(fromUtf8(receipt.result)) }
                }
            }
        }

        if (receipt != null && receipt.events.isNotEmpty()) {
            Sub("What happened")
            Card(padded = false) {
                Column {
                    val registry = w.info?.canonical?.registry
                    events.forEachIndexed { i, e ->
                        val identity = if (e.coin == null && registry != null && equal(e.contract, registry)) Id.decodeEvent(e.payload) else null
                        val item = ActivityItem(
                            height = occurrence.height,
                            time = 0,
                            transaction = route.id,
                            contract = e.contract,
                            coin = e.coin,
                            identity = identity,
                            fee = isFeeEvent(events, i),
                        )
                        val d = if (item.coin != null || item.identity != null) describeActivity(item, w.coinById, w.names, mine, w.advanced) else null
                        val (bg, fg) = toneColors(d?.tone?.let(::toneOf) ?: Tone.Neutral)
                        if (i > 0) Spacer(Modifier.fillMaxWidth().height(0.5.dp).background(p.border))
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            IconCircle(d?.icon?.let(::iconOf) ?: Icons.Outlined.Circle, fg, bg)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(d?.title ?: "Event from ${e.contract.hex().take(12)}…", style = Type.body.copy(fontWeight = FontWeight.Medium), color = p.text)
                                d?.detail?.let { Caption(it) }
                            }
                            val amount = d?.amount
                            if (amount != null) {
                                val tone = d.amountTone
                                Text(
                                    amount,
                                    style = Type.body.copy(fontWeight = FontWeight.Medium, fontFeatureSettings = TABULAR),
                                    color = if (tone != null) toneColors(toneOf(tone)).second else p.text,
                                )
                            }
                        }
                    }
                }
            }
        }
        // Only offered, never opened by itself: the link has no origin, so the address is the page's claim.
        route.back?.let { back ->
            val host = runCatching { back.toUri().host }.getOrNull() ?: back
            Button("Open $host", onClick = {
                runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, "$back#ting-tx=${route.id}".toUri())) }
            }, icon = Icons.AutoMirrored.Outlined.OpenInNew)
        }
        Button(if (route.sent) "Back to wallet" else "Done", onClick = leave, kind = ButtonKind.Secondary)
        Spacer(Modifier.fillMaxWidth().height(Spacing.lg))
    }
}

/** A label and its value, one row of the transaction's card; the first has no line above it. */
@Composable
private fun TxDetail(label: String, first: Boolean = false, value: @Composable () -> Unit) {
    val p = t
    Column {
        if (!first) Spacer(Modifier.fillMaxWidth().height(0.5.dp).background(p.border))
        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Caption(label, Modifier.weight(1f))
            value()
        }
    }
}

/** To the wallet, closing what was opened on the way here. */
private fun NavHostController.toWallet() {
    if (!popBackStack<WalletTab>(inclusive = false)) navigate(WalletTab) { popUpTo(graph.id) { inclusive = true } }
}
