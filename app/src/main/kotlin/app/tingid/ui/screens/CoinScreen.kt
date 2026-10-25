// One account in one coin, as opened from the wallet: what it holds, its
// scheduled actions and activity; what the coin itself is opens in a sheet.
package app.tingid.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.VisibilityOff
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import app.tingid.chain.equal
import app.tingid.chain.formatAmount
import app.tingid.chain.hex
import app.tingid.state.Acting
import app.tingid.state.ActivityFilter
import app.tingid.state.onTheWay
import app.tingid.state.scheduledOf
import app.tingid.text.capped
import app.tingid.text.circulating
import app.tingid.text.lockOf
import app.tingid.ui.Banner
import app.tingid.ui.BannerTone
import app.tingid.ui.Button
import app.tingid.ui.ButtonKind
import app.tingid.ui.CoinRoute
import app.tingid.ui.CoinToolsRoute
import app.tingid.ui.ConfirmHost
import app.tingid.ui.ConfirmOptions
import app.tingid.ui.Detail
import app.tingid.ui.DetailCard
import app.tingid.ui.DetailValue
import app.tingid.ui.IconButton
import app.tingid.ui.IdChip
import app.tingid.ui.InkHeader
import app.tingid.ui.Loading
import app.tingid.ui.LocalNav
import app.tingid.ui.LocalServices
import app.tingid.ui.Pill
import app.tingid.ui.Screen
import app.tingid.ui.SectionHeader
import app.tingid.ui.SendTab
import app.tingid.ui.Sheet
import app.tingid.ui.Spacing
import app.tingid.ui.Tone
import app.tingid.ui.Type
import app.tingid.ui.WalletTab
import app.tingid.ui.manropeStyle
import app.tingid.ui.money.ActivityList
import app.tingid.ui.money.PendingList
import app.tingid.ui.money.ReceiveSheet
import app.tingid.ui.money.ScheduledSection
import app.tingid.ui.money.rememberAccountViews
import app.tingid.ui.rememberConfirm
import app.tingid.ui.t
import kotlinx.coroutines.launch
import java.math.BigInteger

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CoinScreen(route: CoinRoute) {
    val services = LocalServices.current
    val nav = LocalNav.current
    val p = t
    val scope = rememberCoroutineScope()
    val data by services.store.data.collectAsState()
    val w by services.workspace.state.collectAsState()
    val c = w.coinById[route.id]
    // The account the wallet opened; Spending when the route names none.
    val account = w.accounts.firstOrNull { it.slot == (route.slot ?: 0L) } ?: w.accounts.firstOrNull()
    val coins = listOfNotNull(c)
    val accounts = listOfNotNull(account)
    val accountViews = rememberAccountViews(coins, accounts)
    val view = c?.let { accountViews.views[it.hex]?.firstOrNull() }
    val activity = remember(w.scope, c?.hex, account?.account?.hex()) {
        services.activities.activity(w.scope, ActivityFilter(coins.map { it.id }, accounts.map { it.account }, identity = false))
    }
    val pendingItems = remember(services) { services.pending.items() }
    val pending by pendingItems.collectAsState(initial = emptyList())
    var receiving by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    val confirm = rememberConfirm()
    val scheduled = scheduledOf(coins, accountViews.views)

    if (c == null || account == null) {
        Screen(title = c?.config?.name ?: "Coin") {
            if (w.coinsLoading || w.user == null) Loading()
            else Banner(if (c != null) "This account is not available." else "This coin is not followed any more.", BannerTone.Warning)
        }
        return
    }

    val locked = account.delay > 0
    // This account's submissions in this coin that no block has included yet.
    val accountHex = account.account.hex()
    val waiting = pending.filter { it.tx.coin == c.hex && it.tx.account == accountHex }
    val out = onTheWay(waiting, c.hex, accountHex)
    val available = view?.record?.available ?: BigInteger.ZERO
    val reserved = view?.record?.locked ?: BigInteger.ZERO
    val decimals = c.config.decimals
    val symbol = c.config.symbol

    // Send starts from this account.
    val send = {
        services.store.setActiveCoin(c.hex)
        services.store.setActiveSlot(account.slot.toInt())
        nav.navigate(SendTab(coin = c.hex, slot = account.slot))
    }

    Screen(header = {
        InkHeader {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                IconButton(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "Back", onClick = { nav.back() }, kind = ButtonKind.Raised, size = 36.dp)
                Text(
                    "${c.config.name} · ${account.label}",
                    Modifier.weight(1f),
                    style = manropeStyle(15f, FontWeight.Medium),
                    color = p.onHeader,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Button("About", onClick = { about = true }, kind = ButtonKind.Raised, small = true, icon = Icons.Outlined.Layers)
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Available", style = manropeStyle(12.5f, FontWeight.SemiBold), color = p.onHeaderMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (accountViews.loading && view == null) "…" else formatAmount(available, decimals), Modifier.alignByBaseline(), style = Type.amount, color = p.onHeader)
                    Text(symbol, Modifier.alignByBaseline(), style = manropeStyle(16f, FontWeight.Medium), color = p.onHeaderMuted)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill(if (locked) lockOf(account.delay) else "Instant", small = true, light = true)
                    if (reserved.signum() > 0) Pill("${formatAmount(reserved, decimals)} reserved", Tone.Warning, small = true, light = true)
                    if (out.count > 0) Pill("${formatAmount(out.amount, decimals)} on the way", small = true, light = true)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button("Send", onClick = send, kind = ButtonKind.Light, icon = Icons.Outlined.ArrowUpward, enabled = w.user != null, modifier = Modifier.weight(1f))
                Button("Receive", onClick = { receiving = true }, kind = ButtonKind.Raised, icon = Icons.Outlined.ArrowDownward, modifier = Modifier.weight(1f))
            }
        }
    }) {
        ScheduledSection(w, scheduled, Acting(c, account))

        if (w.user != null) PendingList(waiting, onRetry = services.pending::retry, onDismiss = services.pending::dismiss, onSpeedUp = services.pending::speedUp)

        if (w.user != null) {
            SectionHeader("Activity")
            ActivityList(activity, w.coinById, w.names, accounts.map { it.account })
        }
        Spacer(Modifier.fillMaxWidth().height(Spacing.lg))
    }

    if (about) {
        Sheet(onClose = { about = false }, title = c.config.name) {
            DetailCard {
                Detail("Symbol", first = true) { DetailValue("$symbol · $decimals decimals") }
                Detail("Circulating") { DetailValue(formatAmount(circulating(c), decimals)) }
                Detail("Supply cap") { DetailValue(if (capped(c)) formatAmount(c.config.maxSupply, decimals) else "None") }
                if (c.config.initialReward.signum() > 0) {
                    Detail("Block reward") { DetailValue(formatAmount(c.config.initialReward, decimals)) }
                }
                Detail("Contract") { IdChip(c.hex) }
            }
            if (data.settings.coinTools) {
                Button(
                    if (equal(c.state.minter, account.account)) "Coin details and minting" else "Coin details",
                    onClick = {
                        about = false
                        nav.navigate(CoinToolsRoute(c.hex))
                    },
                    kind = ButtonKind.Secondary,
                    icon = Icons.Outlined.Layers,
                )
            }
            Button(
                "Unfollow this coin",
                onClick = {
                    about = false
                    scope.launch {
                        val yes = confirm.ask(
                            ConfirmOptions(
                                title = "Unfollow ${c.config.name}?",
                                body = "It disappears from this wallet. What you hold stays on chain, and you can follow it again from Settings.",
                                confirm = "Unfollow",
                                back = "Keep following",
                                danger = true,
                            ),
                        )
                        if (!yes) return@launch
                        services.store.removeCoin(c.hex)
                        nav.back()
                    }
                },
                kind = ButtonKind.Danger,
                icon = Icons.Outlined.VisibilityOff,
            )
        }
    }

    ReceiveSheet(accounts = accounts, slot = if (receiving) account.slot else null, onSlot = {}, onClose = { receiving = false }, coin = c)
    ConfirmHost(confirm)
}

/** Back to the screen before, or to the wallet when nothing is before this one. */
private fun NavHostController.back() {
    if (previousBackStackEntry != null) popBackStack()
    else navigate(WalletTab) { popUpTo(graph.id) { inclusive = true } }
}
