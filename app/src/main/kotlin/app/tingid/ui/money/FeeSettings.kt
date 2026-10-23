// Settings → Fees: the coin every operation pays its fee in, and what the
// automatic fee comes to now.
package app.tingid.ui.money

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import app.tingid.chain.FeeMarket
import app.tingid.chain.formatAmount
import app.tingid.state.WorkspaceState
import app.tingid.ui.Button
import app.tingid.ui.ButtonKind
import app.tingid.ui.Caption
import app.tingid.ui.Card
import app.tingid.ui.ListItem
import app.tingid.ui.LocalServices
import app.tingid.ui.Pill
import app.tingid.ui.SectionHeader
import app.tingid.ui.Sheet
import app.tingid.ui.Tone

const val FEE_SETTINGS_INFO =
    "Miners take transactions by what they pay for their room in a block, in the coins they accept. Every operation pays its fee in this coin, whatever coin it moves, from Spending unless you choose another account. The amount follows what recent transactions paid for the same room; each operation shows it, and you can change it there."

/** The Fees section of Settings, for the workspace `w`; nothing while no coin is followed. */
@Composable
fun FeeSettings(w: WorkspaceState) {
    val services = LocalServices.current
    val feeCoin = rememberFeeCoin(w)
    val rates by services.feeRates.rates.collectAsState(initial = null)
    var picking by remember { mutableStateOf(false) }
    if (feeCoin == null) return
    val symbol = feeCoin.config.symbol
    val known = rates
    val rate = known?.let { FeeMarket.medianRate(it, feeCoin.hex) }
    val paying = if (known != null) minOf(FeeMarket.SAMPLE, known.count { it.coin == feeCoin.hex }) else 0
    fun amount(share: FeeMarket.Share) = "${formatAmount(FeeMarket.feeFor(rate, share.value), feeCoin.config.decimals)} $symbol"
    SectionHeader("Fees", info = FEE_SETTINGS_INFO)
    Card(padded = false) {
        ListItem(
            "Fee coin",
            subtitle = "${feeCoin.config.name} · $symbol",
            left = { CoinMark(feeCoin, 36.dp) },
            right = if (w.coins.size > 1) ({ Button("Change", onClick = { picking = true }, kind = ButtonKind.Ghost, small = true) }) else null,
        )
    }
    Card {
        Caption(
            when {
                known == null -> "Reading what recent transactions paid…"
                paying > 0 -> "Automatic, from the median of the last $paying paying transaction${if (paying == 1) "" else "s"}: a payment now pays ${amount(FeeMarket.Share.Own)}; a payment in another coin, or an identity change, ${amount(FeeMarket.Share.Bundled)}."
                else -> "Automatic. No recent transaction paid a fee in $symbol, so each pays the smallest amount, ${amount(FeeMarket.Share.Own)}, which still goes before those that pay nothing."
            },
        )
    }
    if (picking) {
        Sheet(onClose = { picking = false }, title = "Fee coin") {
            for (c in w.coins) {
                val on = c.hex == feeCoin.hex
                ListItem(
                    c.config.name,
                    subtitle = c.config.symbol,
                    left = { CoinMark(c, 36.dp) },
                    selected = on,
                    right = if (on) ({ Pill("pays fees", Tone.Primary, small = true) }) else null,
                    onClick = {
                        services.store.setSettings { it.copy(feeCoin = c.hex) }
                        picking = false
                    },
                )
            }
        }
    }
}
