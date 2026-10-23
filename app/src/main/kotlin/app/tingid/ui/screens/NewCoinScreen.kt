// Creating a coin, with the coin tools switched on: a name, a symbol and one
// of three ways new coins come about. A fixed supply goes to one of your
// accounts; a mined coin pays block rewards that halve; a minted coin lets one
// of your accounts mint, Savings by default so that every mint waits out its
// lock and can be cancelled. The form itself is state/CoinTools' coinDraft.
package app.tingid.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
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
import app.tingid.chain.trimWhiteSpace
import app.tingid.state.CoinForm
import app.tingid.state.CoinKind
import app.tingid.state.coinDraft
import app.tingid.state.createCoin
import app.tingid.text.relativeDuration
import app.tingid.ui.Banner
import app.tingid.ui.BannerTone
import app.tingid.ui.Body
import app.tingid.ui.Button
import app.tingid.ui.Caption
import app.tingid.ui.Card
import app.tingid.ui.CardTone
import app.tingid.ui.Field
import app.tingid.ui.HoldWhileBusy
import app.tingid.ui.LocalNav
import app.tingid.ui.LocalServices
import app.tingid.ui.NewCoinRoute
import app.tingid.ui.Screen
import app.tingid.ui.Segmented
import app.tingid.ui.Spacing
import app.tingid.ui.Sub
import app.tingid.ui.TxRoute
import app.tingid.ui.money.AccountBadge
import app.tingid.ui.money.AccountChoice
import app.tingid.ui.sentence
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun NewCoinScreen() {
    CoinToolsOnly { CreateCoin() }
}

private val KINDS = listOf(CoinKind.Fixed to "Fixed supply", CoinKind.Mined to "Mined", CoinKind.Minted to "You mint")

@Composable
private fun CreateCoin() {
    val services = LocalServices.current
    val nav = LocalNav.current
    val ui = rememberCoroutineScope()
    val w by services.workspace.state.collectAsState()
    val advanced = services.store.data.collectAsState().value.settings.advanced
    var form by remember { mutableStateOf(CoinForm()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    HoldWhileBusy(busy)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    val d = coinDraft(form, w.accounts, advanced)
    val ready = d.ready && w.user != null && w.info != null
    // The symbol as typed, in the fields' labels; a hidden character in it shows.
    val symbol = Coin.visibleLabel(trimWhiteSpace(form.symbol))
    val inSymbol = if (symbol.isNotEmpty()) " ($symbol)" else ""

    fun create() {
        val options = d.options
        if (!ready || options == null || busy) return
        val now = w
        busy = true
        error = null
        ui.launch {
            try {
                // The deployment goes on even if the screen goes: the node may have it.
                val txId = services.submitBeyondScreen { createCoin(services.chain, now, options) }
                nav.navigate(TxRoute(txId)) { popUpTo<NewCoinRoute> { inclusive = true } }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = e.message ?: "the coin could not be created"
            } finally {
                busy = false
            }
        }
    }

    Screen(title = "Create a coin") {
        Field(form.name, { form = form.copy(name = it) }, Modifier.focusRequester(focus), label = "Name", placeholder = "Garden Tokens")
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Field(form.symbol, { form = form.copy(symbol = it) }, Modifier.weight(2f), label = "Symbol", placeholder = "GRDN")
            Field(form.decimals, { form = form.copy(decimals = it) }, Modifier.weight(1f), label = "Decimals", keyboardType = KeyboardType.Number)
        }
        ToolsLabel("New coins come from")
        Segmented(KINDS, form.kind) { form = form.copy(kind = it) }

        when (form.kind) {
            CoinKind.Fixed -> {
                Field(form.supply, { form = form.copy(supply = it) }, label = "Total supply$inSymbol", placeholder = "1000000", keyboardType = KeyboardType.Decimal)
                ToolsLabel("Goes to")
                AccountChoice(w.accounts, d.to?.slot, onSlot = { form = form.copy(toSlot = it) })
            }
            CoinKind.Mined -> {
                Field(form.reward, { form = form.copy(reward = it) }, label = "Reward per block$inSymbol", placeholder = "50", keyboardType = KeyboardType.Decimal)
                Field(form.halving, { form = form.copy(halving = it) }, label = "Halves every (blocks)", hint = "0 for a reward that never changes", keyboardType = KeyboardType.Number)
                if (advanced) {
                    Field(
                        form.tail,
                        { form = form.copy(tail = it) },
                        label = "Lowest reward (optional)",
                        placeholder = "0",
                        keyboardType = KeyboardType.Decimal,
                        hint = "Paid forever once the halvings go below it; the coin then has no cap.",
                    )
                }
                Caption("Miners earn the reward once they add the coin to what they claim.")
            }
            CoinKind.Minted -> {
                ToolsLabel("Who mints")
                AccountChoice(
                    w.accounts,
                    d.minter?.slot,
                    onSlot = { form = form.copy(minterSlot = it) },
                    note = { a -> if (a.delay > 0) "each mint waits ${relativeDuration(a.delay)} and can be cancelled: a stolen key cannot mint at once" else "mints at once" },
                    badge = d.savings?.let { AccountBadge(it.slot, "recommended") },
                )
                Field(
                    form.cap,
                    { form = form.copy(cap = it) },
                    label = "Supply cap (optional)",
                    placeholder = "none",
                    keyboardType = KeyboardType.Decimal,
                    hint = "Leave empty for no cap. Minting can be handed to another account or given up later.",
                )
            }
        }

        if (d.problems.isNotEmpty() && form.started) Banner(d.problems.joinToString(" ") { sentence(it) }, BannerTone.Warning)
        error?.let { Banner(sentence(it), BannerTone.Danger) }
        val options = d.options
        val review = d.review
        if (ready && options != null && review != null) {
            Card(tone = CardTone.Alt) {
                Sub("${options.name} · ${options.symbol}")
                Body(review)
                Caption("Its name, symbol and supply rules can never change. Creating a coin pays no fee; it waits for room in a block.")
            }
        }
        Button("Create coin", onClick = ::create, large = true, enabled = ready, loading = busy)
    }
}
