// Minting, with the coin tools switched on, from the account that holds the
// coin's minting right. From a time-locked account the mint is scheduled: it
// waits out the lock, can be cancelled on the account's page until then, and
// the node runs it when due.
package app.tingid.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import app.tingid.chain.Coin
import app.tingid.chain.formatAmount
import app.tingid.chain.trimWhiteSpace
import app.tingid.chain.unhex
import app.tingid.state.Acting
import app.tingid.state.mintDraft
import app.tingid.state.submitMinterAction
import app.tingid.text.amountOf
import app.tingid.text.minterAccount
import app.tingid.text.relativeDuration
import app.tingid.ui.Banner
import app.tingid.ui.BannerTone
import app.tingid.ui.Button
import app.tingid.ui.Caption
import app.tingid.ui.Field
import app.tingid.ui.HoldWhileBusy
import app.tingid.ui.Loading
import app.tingid.ui.LocalActivity
import app.tingid.ui.LocalNav
import app.tingid.ui.LocalServices
import app.tingid.ui.MintRoute
import app.tingid.ui.Screen
import app.tingid.ui.TxRoute
import app.tingid.ui.confirmOwner
import app.tingid.ui.money.AccountChoice
import app.tingid.ui.money.FeeRow
import app.tingid.ui.money.FeeRun
import app.tingid.ui.money.rememberFee
import app.tingid.ui.money.rememberRunFee
import app.tingid.ui.sentence
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.math.BigInteger

@Composable
fun MintScreen(route: MintRoute) {
    CoinToolsOnly { Mint(route.id) }
}

@Composable
private fun Mint(id: String) {
    val services = LocalServices.current
    val activity = LocalActivity.current
    val nav = LocalNav.current
    val ui = rememberCoroutineScope()
    val w by services.workspace.state.collectAsState()
    val infos = rememberToolsCoins(remember(id) { listOf(id) })
    val c = infos.infos[id]
    val minter = c?.let { minterAccount(it, w.accounts) }
    val fee = rememberFee(if (c != null && minter != null) Acting(c, minter) else null)
    val runFee = rememberRunFee(fee, c, minter)
    var toSlot by remember { mutableLongStateOf(0L) }
    var other by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    HoldWhileBusy(busy)
    var error by remember { mutableStateOf<String?>(null) }

    if (c == null || minter == null) {
        Screen(title = "Mint") {
            if (c != null) Banner("None of your accounts can mint ${c.shownSymbol}.", BannerTone.Warning) else Loading()
        }
        return
    }

    val decimals = c.config.decimals
    val symbol = c.shownSymbol
    val delayed = minter.delay > 0
    val d = mintDraft(c, w.accounts, w.names, toSlot, other, amountText, fee.problem)
    val ready = d.ready && w.user != null && w.info != null

    fun mint() {
        val amount = d.amount
        val to = d.to
        if (!ready || busy || amount == null || to == null) return
        // As the screen shows it now: the fee and the workspace move on with each block.
        val now = w
        val paying = fee
        val tip = runFee
        val paid = if (fee.own) fee.fee else BigInteger.ZERO
        val shown = "${formatAmount(amount, decimals)} $symbol"
        val toLabel = d.toLabel
        busy = true
        error = null
        ui.launch {
            try {
                if (!confirmOwner(activity, services, "Mint $shown", "to $toLabel")) return@launch
                val summary = "${if (delayed) "Schedule mint of" else "Mint"} $shown to $toLabel"
                // Once signed it goes on even if the screen goes: the node may have it.
                val sent = services.submitBeyondScreen {
                    submitMinterAction(services.chain, now, c, minter, Coin.Effect.Mint(unhex(to), amount), tip, paying, summary, paid)
                }
                nav.navigate(TxRoute(sent.txId, unsure = sent.unsure)) { popUpTo<MintRoute> { inclusive = true } }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = e.message ?: "the mint could not be sent"
            } finally {
                busy = false
            }
        }
    }

    val focus = remember { FocusRequester() }
    Screen(title = "Mint $symbol") {
        Caption(
            "From your ${minter.label}" +
                (if (delayed) ": the mint waits ${relativeDuration(minter.delay)} and can be cancelled on that account's page until then." else ", at once.") +
                (d.left?.let { " The cap leaves ${amountOf(c, it)}." } ?: ""),
        )
        Field(amountText, { amountText = it }, Modifier.focusRequester(focus), label = "Amount ($symbol)", placeholder = "0", keyboardType = KeyboardType.Decimal)
        LaunchedEffect(Unit) { focus.requestFocus() }
        ToolsLabel("To")
        AccountChoice(
            w.accounts,
            d.ownSlot,
            onSlot = {
                toSlot = it
                other = ""
            },
        )
        Field(other, { other = it }, label = "Or another account", placeholder = "account or pay link", keyboardType = KeyboardType.Uri)
        d.other?.let { Caption(it) }
        FeeRow(fee, boxed = true, run = if (runFee.signum() > 0) FeeRun(runFee, c) else null)
        if (d.problems.isNotEmpty() && (amountText.isNotEmpty() || trimWhiteSpace(other).isNotEmpty())) {
            Banner(d.problems.joinToString(" ") { sentence(it) }, BannerTone.Warning)
        }
        error?.let { Banner(sentence(it), BannerTone.Danger) }
        Button(if (delayed) "Schedule mint" else "Mint", onClick = ::mint, large = true, enabled = ready, loading = busy)
    }
}
