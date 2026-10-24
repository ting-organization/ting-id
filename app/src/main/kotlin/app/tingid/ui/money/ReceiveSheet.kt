// The Receive sheet: an account's pay link as a QR code and a link to share,
// and, under Details, the link itself and the account to copy (LINKS.md).
package app.tingid.ui.money

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.tingid.chain.Link
import app.tingid.chain.Links
import app.tingid.chain.WHITE_SPACE
import app.tingid.chain.hex
import app.tingid.chain.parseAmount
import app.tingid.state.CoinInfo
import app.tingid.state.UserAccount
import app.tingid.text.relativeDuration
import app.tingid.ui.AccountBox
import app.tingid.ui.Button
import app.tingid.ui.ButtonKind
import app.tingid.ui.Caption
import app.tingid.ui.Field
import app.tingid.ui.LinkShare
import app.tingid.ui.LocalServices
import app.tingid.ui.Radius
import app.tingid.ui.Segmented
import app.tingid.ui.Sheet
import app.tingid.ui.Spacing
import app.tingid.ui.Type
import app.tingid.ui.t

/** `amount` as a link carries it when it is an amount of more than nothing in `c`: without spaces, with a decimal point; else null. */
private fun askedAmount(amount: String, c: CoinInfo?): String? {
    if (c == null || amount.isBlank()) return null
    return try {
        if (parseAmount(amount, c.config.decimals).signum() > 0) amount.trim().replace(Regex("[${WHITE_SPACE}_]"), "").replaceFirst(',', '.') else null
    } catch (e: Exception) {
        null
    }
}

/**
 * The Receive sheet: pick an account, show its pay link as a QR code to scan
 * and a link to share, and the account to copy. It shows while `slot` names
 * one of `accounts`; `onSlot` is told another account picked, `onClose` that
 * the sheet closed. An amount can be asked for, in `coin`, or `amountCoin`
 * when the sheet is for any coin.
 */
@Composable
fun ReceiveSheet(
    accounts: List<UserAccount>,
    slot: Long?,
    onSlot: (Long) -> Unit,
    onClose: () -> Unit,
    coin: CoinInfo? = null,
    amountCoin: CoinInfo? = null,
) {
    val p = t
    val info by LocalServices.current.chain.info.collectAsState()
    var asking by remember { mutableStateOf(false) }
    var amount by remember { mutableStateOf("") }
    var details by remember { mutableStateOf(false) }
    val account = if (slot == null) null else accounts.firstOrNull { it.slot == slot }
    if (account == null) return
    val accountHex = account.account.hex()
    val priced = coin ?: amountCoin
    val asked = askedAmount(amount, priced)
    val chain = info
    val link = if (chain != null) {
        Links.encode(Link.Pay(Links.netOf(chain.status.chainId), accountHex, coin = if (coin != null || asked != null) priced?.hex else null, amount = asked))
    } else {
        null
    }
    val close = {
        asking = false
        amount = ""
        details = false
        onClose()
    }
    Sheet(onClose = close, title = if (coin != null) "Receive ${coin.config.symbol}" else "Receive") {
        if (accounts.size > 1) Segmented(accounts.map { it.slot to it.label }, account.slot, onSlot)
        if (link != null) LinkShare(link, text = false)
        if (priced != null && asking) {
            Field(
                amount,
                { amount = it },
                label = "Ask for an amount (${priced.config.symbol})",
                placeholder = "0",
                keyboardType = KeyboardType.Decimal,
                error = if (amount.isNotBlank() && asked == null) "enter an amount with at most ${priced.config.decimals} decimals" else null,
                hint = "The link asks for this amount; whoever pays can change it.",
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            if (priced != null && !asking) Button("Ask for an amount", onClick = { asking = true }, kind = ButtonKind.Ghost, small = true, icon = Icons.Outlined.Add)
            Button(
                if (details) "Hide details" else "Details",
                onClick = { details = !details },
                kind = ButtonKind.Ghost,
                small = true,
                icon = if (details) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
            )
        }
        if (details) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.md)).background(p.cardAlt).padding(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Caption("The account, to copy")
                AccountBox(accountHex)
                if (link != null) {
                    Caption("The link")
                    Text(link, style = Type.mono.copy(fontSize = Type.mono.fontSize * 0.88f), color = p.muted)
                }
            }
        }
        if (account.delay != 0L) Caption("Deposits are instant; withdrawals from ${account.label} wait ${relativeDuration(account.delay)}.")
    }
}
