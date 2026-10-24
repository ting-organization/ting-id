// Handing a coin's minting right to another account, or giving it up for
// good, with the coin tools switched on. Moving it to Savings makes every
// later mint wait out the lock. From a time-locked account the change itself
// waits too, and can be cancelled.
package app.tingid.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import app.tingid.chain.Coin
import app.tingid.chain.unhex
import app.tingid.state.Acting
import app.tingid.state.MinterTarget
import app.tingid.state.minterDraft
import app.tingid.state.submitMinterAction
import app.tingid.text.minterAccount
import app.tingid.text.relativeDuration
import app.tingid.ui.Banner
import app.tingid.ui.BannerTone
import app.tingid.ui.Button
import app.tingid.ui.ButtonKind
import app.tingid.ui.Caption
import app.tingid.ui.ConfirmHost
import app.tingid.ui.ConfirmOptions
import app.tingid.ui.Field
import app.tingid.ui.HoldWhileBusy
import app.tingid.ui.Loading
import app.tingid.ui.LocalActivity
import app.tingid.ui.LocalNav
import app.tingid.ui.LocalServices
import app.tingid.ui.MinterRoute
import app.tingid.ui.Screen
import app.tingid.ui.Segmented
import app.tingid.ui.TxRoute
import app.tingid.ui.confirmOwner
import app.tingid.ui.money.AccountChoice
import app.tingid.ui.money.FeeRow
import app.tingid.ui.money.FeeRun
import app.tingid.ui.money.rememberFee
import app.tingid.ui.money.rememberRunFee
import app.tingid.ui.rememberConfirm
import app.tingid.ui.sentence
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun MinterScreen(route: MinterRoute) {
    CoinToolsOnly { Minter(route.id) }
}

private val TARGETS = listOf(MinterTarget.Mine to "Your account", MinterTarget.Other to "Someone else", MinterTarget.None to "Give it up")

@Composable
private fun Minter(id: String) {
    val services = LocalServices.current
    val activity = LocalActivity.current
    val nav = LocalNav.current
    val ui = rememberCoroutineScope()
    val confirm = rememberConfirm()
    val w by services.workspace.state.collectAsState()
    val infos = rememberToolsCoins(remember(id) { listOf(id) })
    val c = infos.infos[id]
    val minter = c?.let { minterAccount(it, w.accounts) }
    val fee = rememberFee(if (c != null && minter != null) Acting(c, minter) else null)
    val runFee = rememberRunFee(fee, c, minter)
    var target by remember { mutableStateOf(MinterTarget.Mine) }
    var slot by remember { mutableStateOf<Long?>(null) }
    var other by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    HoldWhileBusy(busy)
    var error by remember { mutableStateOf<String?>(null) }

    if (c == null || minter == null) {
        Screen(title = "Move minting") {
            if (c != null) Banner("None of your accounts can mint ${c.shownSymbol}.", BannerTone.Warning) else Loading()
        }
        return
    }

    val symbol = c.shownSymbol
    val delayed = minter.delay > 0
    val d = minterDraft(minter, w.accounts, w.names, target, slot, other, fee.problem)
    val ready = d.ready && w.user != null && w.info != null

    fun submit() {
        val next = d.next
        if (!ready || busy || next == null) return
        // As the screen shows it now: the fee and the workspace move on with each block.
        val now = w
        val paying = fee
        val tip = runFee
        val to = target
        val label = d.label
        ui.launch {
            // Giving minting up, or handing it to someone else, cannot be taken back from here.
            if (to == MinterTarget.None) {
                val yes = confirm.ask(
                    ConfirmOptions(
                        title = "Give up minting $symbol?",
                        body = "No one will ever be able to mint $symbol again, you included.",
                        confirm = "Give up minting",
                        back = "Keep minting",
                        danger = true,
                    ),
                )
                if (!yes) return@launch
            }
            if (to == MinterTarget.Other) {
                val yes = confirm.ask(
                    ConfirmOptions(title = "Hand minting of $symbol to $label?", body = "Only the account $next can mint it afterwards, or hand it on.", confirm = "Hand it over", back = "Back"),
                )
                if (!yes) return@launch
            }
            // A second tap while the question was open does not send twice.
            if (busy) return@launch
            busy = true
            error = null
            try {
                val none = to == MinterTarget.None
                if (!confirmOwner(activity, services, if (none) "Give up minting $symbol" else "Move minting of $symbol", if (none) null else "to $label")) return@launch
                val summary = if (none) "Give up minting $symbol" else "${if (delayed) "Schedule moving" else "Move"} minting of $symbol to $label"
                // Once signed it goes on even if the screen goes: the node may have it.
                val sent = services.submitBeyondScreen {
                    submitMinterAction(services.chain, now, c, minter, Coin.Effect.SetMinter(unhex(next)), tip, paying, summary)
                }
                nav.navigate(TxRoute(sent.txId, unsure = sent.unsure)) { popUpTo<MinterRoute> { inclusive = true } }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = e.message ?: "the change could not be sent"
            } finally {
                busy = false
            }
        }
    }

    ConfirmHost(confirm)
    Screen(title = "Minting of $symbol") {
        Caption("Your ${minter.label} mints $symbol now." + if (delayed) " A change from it waits ${relativeDuration(minter.delay)} and can be cancelled until then." else "")
        Segmented(TARGETS, target) { target = it }
        when (target) {
            MinterTarget.Mine -> if (d.others.isNotEmpty()) {
                AccountChoice(
                    d.others,
                    d.chosen?.slot,
                    onSlot = { slot = it },
                    note = { a -> if (a.delay > 0) "every mint waits ${relativeDuration(a.delay)} and can be cancelled" else "mints at once" },
                )
            } else {
                Caption("You have no other account.")
            }
            MinterTarget.Other -> {
                val focus = remember { FocusRequester() }
                Field(other, { other = it }, Modifier.focusRequester(focus), label = "Account", placeholder = "account or pay link", keyboardType = KeyboardType.Uri)
                LaunchedEffect(Unit) { focus.requestFocus() }
                d.other?.let { Caption(it) }
            }
            MinterTarget.None -> Unit
        }
        FeeRow(fee, boxed = true, run = if (runFee.signum() > 0) FeeRun(runFee, c) else null)
        if (d.problems.isNotEmpty()) Banner(d.problems.joinToString(" ") { sentence(it) }, BannerTone.Warning)
        error?.let { Banner(sentence(it), BannerTone.Danger) }
        Button(
            if (target == MinterTarget.None) "Give up minting" else if (delayed) "Schedule the move" else "Move minting",
            onClick = ::submit,
            kind = if (target == MinterTarget.None) ButtonKind.Danger else ButtonKind.Primary,
            large = true,
            enabled = ready,
            loading = busy,
        )
    }
}
