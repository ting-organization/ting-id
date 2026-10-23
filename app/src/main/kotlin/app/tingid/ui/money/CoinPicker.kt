// Coins as marks, the wallet's coin cards with the accounts under each, and
// a sheet to pick the account to send from.
package app.tingid.ui.money

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import app.tingid.chain.formatAmount
import app.tingid.state.AccountView
import app.tingid.state.CoinGroup
import app.tingid.state.CoinInfo
import app.tingid.state.OnTheWay
import app.tingid.state.coinColor
import app.tingid.text.lockOf
import app.tingid.ui.Caption
import app.tingid.ui.Card
import app.tingid.ui.Sheet
import app.tingid.ui.Spacing
import app.tingid.ui.Type
import app.tingid.ui.manropeStyle
import app.tingid.ui.parseColor
import app.tingid.ui.t

/** A colour mixed toward white by `amount` (0..1), so a coin's colour reads on dark cards. */
private fun lighten(c: Color, amount: Float): Color =
    Color(c.red + (1 - c.red) * amount, c.green + (1 - c.green) * amount, c.blue + (1 - c.blue) * amount)

/** A coin's mark: a listed coin's own, else its symbol in a ring of its colour. */
@Composable
fun CoinMark(coin: CoinInfo, size: Dp = 36.dp) {
    val icon = coinIconOf(coin.hex)
    if (icon != null) {
        CoinIcon(icon, size)
        return
    }
    val base = parseColor(coinColor(coin.hex))
    val color = if (t.dark) lighten(base, 0.35f) else base
    val symbol = coin.config.symbol.ifEmpty { coin.config.name }.take(4).uppercase()
    Box(Modifier.size(size).border(2.dp, color, CircleShape), contentAlignment = Alignment.Center) {
        Text(symbol, style = manropeStyle(size.value * (if (symbol.length > 3) 0.22f else 0.27f), FontWeight.ExtraBold), color = color, maxLines = 1)
    }
}

/** What an account is: instant, or how long its withdrawals wait. */
fun accountNote(view: AccountView): String = if (view.account.delay != 0L) lockOf(view.account.delay) else "Instant"

/** One account of a coin: its name and lock, what it has available, and what is reserved or on the way. `symbol`: the symbol beside the amount. */
@Composable
private fun AccountLine(
    coin: CoinInfo,
    view: AccountView,
    onClick: () -> Unit,
    selected: Boolean = false,
    first: Boolean = false,
    pending: OnTheWay? = null,
    symbol: Boolean = false,
) {
    val p = t
    val reserved = view.record.locked
    val dec = coin.config.decimals
    Column {
        if (!first) Hairline()
        PressRow(onClick, PaddingValues(vertical = 11.dp, horizontal = Spacing.lg), selected = selected, gap = 10.dp) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(view.account.label, style = manropeStyle(14f, FontWeight.SemiBold), color = p.text)
                val note = buildAnnotatedString {
                    append(accountNote(view))
                    if (reserved.signum() > 0) withStyle(SpanStyle(color = p.warning)) { append(" · ${formatAmount(reserved, dec)} reserved") }
                    if (pending != null && pending.count > 0) append(" · ${formatAmount(pending.amount, dec)} on the way")
                }
                Text(note, style = Type.caption, color = p.muted)
            }
            Text(
                formatAmount(view.record.available, dec) + if (symbol) " ${coin.config.symbol}" else "",
                style = manropeStyle(15f, FontWeight.SemiBold, tabular = true),
                color = p.text,
            )
            if (selected) Icon(Icons.Outlined.Check, null, tint = p.primary, modifier = Modifier.size(18.dp))
            else Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = p.faint, modifier = Modifier.size(16.dp))
        }
    }
}

/** A coin's heading: its mark and name, and the total of its accounts, "…" until every one has loaded. */
@Composable
private fun CoinHead(group: CoinGroup, total: Boolean, loading: Boolean = false) {
    val p = t
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CoinMark(group.coin)
        Column(Modifier.weight(1f)) {
            Text(group.coin.config.name, style = manropeStyle(15f, FontWeight.Bold), color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!total) Caption(group.coin.config.symbol)
        }
        if (total) {
            Text(
                if (loading) "…" else formatAmount(group.total, group.coin.config.decimals),
                style = manropeStyle(20f, FontWeight.ExtraBold, tabular = true).copy(letterSpacing = (-0.3 / 20).em),
                color = p.text,
                maxLines = 1,
            )
        }
    }
}

/**
 * The followed coins as cards, each listing the accounts that hold it; an
 * account opens its screen. `onOpen` gets the coin and the account's slot;
 * `pendingFor` says what is on the way from an account (state/Watch `onTheWay`).
 */
@Composable
fun CoinGroups(
    groups: List<CoinGroup>,
    loading: Boolean,
    onOpen: (coin: CoinInfo, slot: Long) -> Unit,
    pendingFor: ((coin: CoinInfo, view: AccountView) -> OnTheWay)? = null,
) {
    for (g in groups) {
        Card(padded = false) {
            Column {
                CoinHead(g, total = true, loading = loading)
                Hairline()
                g.views.forEachIndexed { i, v ->
                    AccountLine(g.coin, v, onClick = { onOpen(g.coin, v.account.slot) }, first = i == 0, pending = pendingFor?.invoke(g.coin, v))
                }
                if (g.views.isEmpty()) {
                    Caption(if (loading) "Loading balances…" else "No accounts yet", Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md))
                }
            }
        }
    }
}

/** An account of a coin, as the send screen holds its choice: the coin's hex and the account's slot. */
data class SelectedAccount(val coin: String, val slot: Long)

/**
 * Choosing the account to send from: every account of every followed coin,
 * coin by coin. A sheet: the screen shows it while choosing, and `onClose`
 * ends that; choosing an account calls `onSelect`, then `onClose`.
 */
@Composable
fun AccountPicker(
    onClose: () -> Unit,
    groups: List<CoinGroup>,
    selected: SelectedAccount?,
    onSelect: (coin: CoinInfo, slot: Long) -> Unit,
) {
    val p = t
    Sheet(onClose = onClose, title = "Send from") {
        for (g in groups) {
            val shape = RoundedCornerShape(16.dp)
            Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, p.border, shape)) {
                CoinHead(g, total = false)
                Hairline()
                g.views.forEachIndexed { i, v ->
                    AccountLine(
                        g.coin,
                        v,
                        first = i == 0,
                        symbol = true,
                        selected = selected?.coin == g.coin.hex && selected.slot == v.account.slot,
                        onClick = {
                            onSelect(g.coin, v.account.slot)
                            onClose()
                        },
                    )
                }
            }
        }
        if (groups.isEmpty()) Caption("No coins followed yet; add them in Settings.")
    }
}
