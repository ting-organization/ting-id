// The fee of an operation as one line: what it pays and from which account,
// with a sheet to change the amount or the paying account.
package app.tingid.ui.money

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import app.tingid.chain.formatAmount
import app.tingid.state.CoinInfo
import app.tingid.state.FEE_INFO
import app.tingid.state.PayerOption
import app.tingid.state.RUN_INFO
import app.tingid.ui.Button
import app.tingid.ui.ButtonKind
import app.tingid.ui.Caption
import app.tingid.ui.Card
import app.tingid.ui.Field
import app.tingid.ui.Info
import app.tingid.ui.ListItem
import app.tingid.ui.Pill
import app.tingid.ui.Sheet
import app.tingid.ui.Spacing
import app.tingid.ui.Tone
import app.tingid.ui.manropeStyle
import app.tingid.ui.sentence
import app.tingid.ui.t
import java.math.BigInteger

/** What a scheduled action pays again when it runs, in the coin it moves: shown added to the fee. */
class FeeRun(val amount: BigInteger, val coin: CoinInfo)

/** The fee, with a scheduled action's run added: one sum in the same coin, else both. */
private fun feeText(fee: BigInteger, c: CoinInfo, run: FeeRun?): String {
    fun one(v: BigInteger, x: CoinInfo) = "${formatAmount(v, x.config.decimals)} ${x.config.symbol}"
    if (run == null || run.amount.signum() == 0) return one(fee, c)
    return if (run.coin.hex == c.hex) one(fee + run.amount, c) else "${one(fee, c)} + ${one(run.amount, run.coin)}"
}

private fun balance(o: PayerOption): String = o.available?.let { "${formatAmount(it, o.coin.config.decimals)} ${o.coin.config.symbol}" } ?: "…"

/**
 * The fee line of an operation, from [rememberFee]. `boxed`: the line sits in
 * a card of its own. `label`: what the line is called, "Fee" unless said.
 * `run`: what a scheduled action pays again when it runs ([rememberRunFee]),
 * shown added to the fee; null for none.
 */
@Composable
fun FeeRow(fee: FeeChoice, boxed: Boolean = false, label: String = "Fee", run: FeeRun? = null) {
    val p = t
    var open by remember { mutableStateOf(false) }
    val box: @Composable (@Composable ColumnScope.() -> Unit) -> Unit = { content ->
        if (boxed) Card(content = content) else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs), content = content)
    }
    val payer = fee.payer
    if (!fee.canPay || payer == null) {
        box { Caption("No fee: this phone signs for no account yet, so it goes unpaid.") }
        return
    }
    val symbol = payer.coin.config.symbol
    box {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(label, style = manropeStyle(13.5f, FontWeight.Medium), color = p.muted)
            Text(
                if (fee.fee.signum() > 0) "${feeText(fee.fee, payer.coin, run)} · ${payer.account.label}" else "none",
                Modifier.weight(1f),
                style = manropeStyle(13.5f, FontWeight.Bold, tabular = true),
                color = p.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Info("Fee", if (run != null && run.amount.signum() > 0) "$FEE_INFO $RUN_INFO" else FEE_INFO)
            Button("Change", onClick = { open = true }, kind = ButtonKind.Ghost, small = true)
        }
        fee.notice?.let { ToneCaption(it, p.warning) }
        fee.problem?.let { ToneCaption(sentence(it), p.danger) }
    }
    if (open) {
        Sheet(onClose = { open = false }, title = "Fee") {
            Field(
                fee.amount,
                fee::setAmount,
                label = "Fee ($symbol)",
                placeholder = "0",
                keyboardType = KeyboardType.Decimal,
                error = fee.problem,
                hint = if (fee.automatic) "Automatic: what recent transactions paid for the same room in a block." else null,
            )
            if (!fee.automatic) Button("Use the automatic fee", onClick = fee::useAutomatic, kind = ButtonKind.Ghost, small = true, icon = Icons.Outlined.Refresh)
            if (fee.bundled) {
                Caption("Paid in $symbol beside this operation. It takes a second signature, so the transaction needs about 60% more room in a block, and pays for it.")
            }
            if (fee.options.size > 1) {
                Caption("Paid from")
                for (o in fee.options) {
                    val on = o === payer
                    ListItem(
                        if (fee.multiCoin) "${o.account.label} · ${o.coin.config.symbol}" else o.account.label,
                        subtitle = "${balance(o)} available",
                        selected = on,
                        right = if (on) ({ Pill("pays", Tone.Primary, small = true) }) else null,
                        onClick = { fee.choose(o) },
                    )
                }
            }
            Button("Done", onClick = { open = false })
        }
    }
}
