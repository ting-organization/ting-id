// A payment once more before it goes: amount, both accounts, when, fees and
// what is left, then the one button that sends it, once the phone has
// checked its owner (Settings → Security). Send stays beneath, and Edit goes
// back to it with everything as entered.
package app.tingid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import app.tingid.chain.formatAmount
import app.tingid.state.PaymentDraft
import app.tingid.state.paymentDrafts
import app.tingid.text.lockOf
import app.tingid.ui.Banner
import app.tingid.ui.BannerTone
import app.tingid.ui.Button
import app.tingid.ui.ButtonKind
import app.tingid.ui.Caption
import app.tingid.ui.Detail
import app.tingid.ui.DetailCard
import app.tingid.ui.DetailValue
import app.tingid.ui.HoldWhileBusy
import app.tingid.ui.LocalActivity
import app.tingid.ui.LocalNav
import app.tingid.ui.LocalServices
import app.tingid.ui.ReviewRoute
import app.tingid.ui.SaveContactSheet
import app.tingid.ui.Screen
import app.tingid.ui.SendTab
import app.tingid.ui.Spacing
import app.tingid.ui.TxRoute
import app.tingid.ui.Type
import app.tingid.ui.confirmOwner
import app.tingid.ui.formatClock
import app.tingid.ui.formatDay
import app.tingid.ui.manropeStyle
import app.tingid.ui.money.CoinMark
import app.tingid.ui.sentence
import app.tingid.ui.t
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.math.BigInteger

@Composable
fun ReviewScreen() {
    val services = LocalServices.current
    val activity = LocalActivity.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val p = t
    val w by services.workspace.state.collectAsState()
    val draft by paymentDrafts.draft.collectAsState()
    var busy by remember { mutableStateOf(false) }
    HoldWhileBusy(busy)
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    val d = draft
    if (d == null) {
        Screen(title = "Review payment") {
            Banner("Nothing to review: start again from Send.", BannerTone.Warning)
            Button("Back", onClick = { nav.edit() }, kind = ButtonKind.Secondary)
        }
        return
    }

    val c = d.coin
    fun amount(v: BigInteger) = "${formatAmount(v, c.config.decimals)} ${c.config.symbol}"
    val toName = w.names[d.to.account]
    val runsAt = d.runsAt
    val chosenAt = d.chosenAt
    val fromSlot = d.from.label.split(" · ").last()
    // A time-locked account: its lock, not the chosen time, decides when it runs.
    val lockDecides = runsAt != null && d.lock > 0 && (chosenAt == null || chosenAt < runsAt)

    fun confirm(payment: PaymentDraft) {
        // A second tap before the first shows as busy sends nothing more.
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                val title = if (runsAt != null) "Confirm the scheduled payment" else "Confirm the payment"
                if (!confirmOwner(activity, services.store, title, "${amount(payment.amount)} to ${toName ?: (payment.to.account.take(10) + "…")}")) {
                    busy = false
                    return@launch
                }
                // Sent in the app's scope: once it starts, it finishes even if this screen goes.
                val sent = services.scope.async { payment.submit() }.await()
                nav.navigate(TxRoute(sent.txId, sent = true, unsure = sent.unsure)) { popUpTo<ReviewRoute> { inclusive = true } }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = e.message ?: "the payment could not be sent"
                busy = false
            }
        }
    }

    Screen(title = "Review payment") {
        Column(Modifier.fillMaxWidth().padding(vertical = Spacing.md), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CoinMark(c, 56.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    formatAmount(d.amount, c.config.decimals),
                    Modifier.alignByBaseline(),
                    style = manropeStyle(38f, FontWeight.ExtraBold, tabular = true).copy(letterSpacing = (-1.2).sp),
                    color = p.text,
                )
                Text(c.config.symbol, Modifier.alignByBaseline(), style = manropeStyle(18f, FontWeight.Bold), color = p.faint)
            }
            Caption(if (runsAt != null) "Runs ${formatDay(runsAt)} at ${formatClock(runsAt)}" else "Goes as soon as a block includes it")
        }

        DetailCard {
            Detail("From", first = true) { DetailValue(d.from.label) }
            Spacer(Modifier.fillMaxWidth().height(1.dp).background(p.border))
            Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("To", Modifier.weight(1f), style = manropeStyle(13.5f, FontWeight.SemiBold), color = p.muted)
                    DetailValue(toName ?: "New recipient")
                }
                SelectionContainer {
                    Text(d.to.account, style = Type.mono.copy(fontSize = 12.5.sp, lineHeight = 18.sp), color = p.text)
                }
                if (toName == null) Button("Save as contact", onClick = { saving = true }, kind = ButtonKind.Ghost, small = true, icon = Icons.Outlined.PersonAdd)
            }
            Detail("When") { DetailValue(if (runsAt != null) "${formatDay(runsAt)}, ${formatClock(runsAt)}" else "Now") }
            Detail("Fee") { DetailValue(d.fee?.removePrefix("Fee ") ?: "None") }
            if (d.runFee.signum() > 0) Detail("Run fee") { DetailValue(amount(d.runFee)) }
            Detail("Total from $fromSlot") { DetailValue(amount(d.total)) }
            Detail("Available after") { DetailValue(amount(d.available - d.total)) }
        }

        if (runsAt != null) {
            Caption(
                (if (lockDecides) "Withdrawals from $fromSlot wait for its ${lockOf(d.lock)}. " else "") +
                    "${amount(d.amount + d.runFee)} is reserved until it runs, and you can cancel it from the wallet until then.",
                Modifier.padding(horizontal = 4.dp),
            )
        }
        d.feeWarning?.let { Banner(it, BannerTone.Warning) }
        if (toName == null) {
            Banner(
                "This account is not in your contacts. Check the address with whoever you pay: a payment cannot be taken back once it runs.",
                BannerTone.Info,
                Icons.Outlined.Person,
            )
        }
        error?.let { Banner(sentence(it), BannerTone.Danger) }
        Button(if (runsAt != null) "Confirm and schedule" else "Confirm and send", onClick = { confirm(d) }, large = true, loading = busy)
        Button("Edit", onClick = { nav.edit() }, kind = ButtonKind.Ghost, enabled = !busy)
    }
    if (saving) SaveContactSheet(d.to.account, onClose = { saving = false })
}

/** Back to Send, with everything as entered; to a new Send when this page was opened on its own. */
private fun NavHostController.edit() {
    if (previousBackStackEntry != null) popBackStack()
    else navigate(SendTab()) { popUpTo<ReviewRoute> { inclusive = true } }
}
