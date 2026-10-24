// One coin as the chain has it, with the coin tools switched on: supply,
// issuance, minter and contract, with Follow, and Mint and Move minting when
// one of your accounts is the minter.
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tingid.chain.Coin
import app.tingid.chain.formatAmount
import app.tingid.state.CoinInfo
import app.tingid.state.blocksTime
import app.tingid.text.amountOf
import app.tingid.text.capped
import app.tingid.text.circulating
import app.tingid.text.hasMinter
import app.tingid.text.minterAccount
import app.tingid.text.minterName
import app.tingid.text.originalOf
import app.tingid.text.relativeDuration
import app.tingid.text.supplyModel
import app.tingid.ui.Banner
import app.tingid.ui.BannerTone
import app.tingid.ui.Button
import app.tingid.ui.ButtonKind
import app.tingid.ui.Caption
import app.tingid.ui.CoinToolsRoute
import app.tingid.ui.CoinsTab
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
import app.tingid.ui.MintRoute
import app.tingid.ui.MinterRoute
import app.tingid.ui.Pill
import app.tingid.ui.Screen
import app.tingid.ui.SectionHeader
import app.tingid.ui.Spacing
import app.tingid.ui.Tone
import app.tingid.ui.Type
import app.tingid.ui.manropeStyle
import app.tingid.ui.money.CoinMark
import app.tingid.ui.rememberConfirm
import app.tingid.ui.t
import kotlinx.coroutines.launch
import java.math.BigInteger

@Composable
fun CoinToolsScreen(route: CoinToolsRoute) {
    CoinToolsOnly { CoinDetails(route.id) }
}

private const val MINTING_INFO =
    "The minter is an account. When it belongs to an identity, the right to mint stays with the identity through lost devices and recovery. " +
        "From a time-locked account every mint waits out its lock and can be cancelled until then."

private const val COIN_INFO = "The app checks the contract's code before it shows a coin: every coin here runs the same standard coin program, and only its options differ."

@Composable
private fun CoinDetails(id: String) {
    val services = LocalServices.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val confirm = rememberConfirm()
    val w by services.workspace.state.collectAsState()
    val followed = id in services.store.data.collectAsState().value.settings.coins
    val chainHex = w.info?.chainHex
    val listedIds = toolsListedIds(chainHex)
    val height = w.info?.status?.height
    // Every coin, to tell whether this one copies another.
    val index = rememberToolsIndex(chainHex)
    val ids = remember(index.coins, id, listedIds) { (index.coins + id + listedIds).distinct() }
    val infos = rememberToolsCoins(ids)
    val c = infos.infos[id]

    fun back() {
        if (nav.previousBackStackEntry != null) nav.popBackStack()
        else nav.navigate(CoinsTab) { popUpTo<CoinToolsRoute> { inclusive = true } }
    }

    ConfirmHost(confirm)
    if (c == null) {
        Screen(title = "Coin") {
            if (infos.loading) Loading() else Banner("This chain has no such coin, or a block has not included it yet.", BannerTone.Warning)
            Button("Back", onClick = ::back, kind = ButtonKind.Secondary)
        }
        return
    }

    val listed = c.hex in listedIds
    val copy = originalOf(c, infos.infos.values.toList(), listedIds)
    val mine = minterAccount(c, w.accounts)
    val nextReward = if (height != null) Coin.blockReward(c.config, c.state.issued, height + 1) else BigInteger.ZERO
    val mined = c.config.initialReward.signum() > 0 || c.config.tailReward.signum() > 0
    val left = if (capped(c)) c.config.maxSupply - c.state.issued else null
    val symbol = c.shownSymbol

    fun unfollow() {
        scope.launch {
            val yes = confirm.ask(
                ConfirmOptions(
                    title = "Unfollow ${c.shownName}?",
                    body = "It disappears from this wallet. What you hold stays on chain, and you can follow it again from Coins.",
                    confirm = "Unfollow",
                    back = "Keep following",
                    danger = true,
                ),
            )
            if (yes) services.store.removeCoin(c.hex)
        }
    }

    Screen(header = { Header(c, listed, mine != null, followed, onBack = ::back, onUnfollow = ::unfollow) }) {
        if (copy != null) {
            val which = if (copy.hex in listedIds) "the listed" else "an earlier"
            Banner(
                "Not the original: $which coin ${copy.shownName} (${copy.shownSymbol}) has the same ticker and a name like this one. " +
                    "Anyone can create a coin with any name; only the contract ID tells coins apart.",
                BannerTone.Danger,
                Icons.Outlined.WarningAmber,
            )
            Button("Open the original", onClick = { nav.navigate(CoinToolsRoute(copy.hex)) }, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary, small = true)
        }

        SectionHeader("Supply")
        DetailCard {
            Detail("Issued", first = true) { DetailValue(amountOf(c, c.state.issued)) }
            Detail("Burned") { DetailValue(amountOf(c, c.state.burned)) }
            Detail("Supply cap") { DetailValue(if (capped(c)) amountOf(c, c.config.maxSupply) else "None") }
            if (left != null) Detail("Left to issue") { DetailValue(amountOf(c, left)) }
        }

        SectionHeader("Minting", info = MINTING_INFO)
        DetailCard {
            Detail("Minter", first = true) { DetailValue(minterName(c, w.accounts, w.names)) }
            if (mine != null) {
                Detail("Mints") { DetailValue(if (mine.delay > 0) "wait ${relativeDuration(mine.delay)}, can be cancelled" else "at once") }
            }
        }
        if (!hasMinter(c)) Caption("No one can mint $symbol: new coins come only from mining rewards, if any.")
        if (mine != null) Button("Move minting", onClick = { nav.navigate(MinterRoute(c.hex)) }, kind = ButtonKind.Secondary, icon = Icons.Outlined.Key)

        SectionHeader("Mining")
        if (mined) {
            DetailCard {
                Detail("Reward per block now", first = true) { DetailValue(amountOf(c, nextReward)) }
                Detail("Halves every") {
                    val n = c.config.halvingInterval
                    DetailValue(if (n > 0) "${formatAmount(BigInteger.valueOf(n), 0)} blocks · about ${relativeDuration(blocksTime(n))}" else "never")
                }
                if (c.config.tailReward.signum() > 0) Detail("Lowest reward") { DetailValue(amountOf(c, c.config.tailReward)) }
                Detail("Last claimed") { DetailValue(if (c.state.lastClaimed > c.config.deployHeight) "block ${c.state.lastClaimed}" else "never") }
            }
        } else {
            Caption("Mining pays no $symbol.")
        }

        SectionHeader("Coin", info = COIN_INFO)
        DetailCard {
            Detail("Decimals", first = true) { DetailValue(c.config.decimals.toString()) }
            Detail("Created") { DetailValue("block ${c.config.deployHeight}") }
            Detail("Contract") { IdChip(c.hex) }
        }
        Spacer(Modifier.height(Spacing.lg))
    }
}

/** The dark band: back, the coin's mark and labels, what circulates, and Mint and Follow. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Header(c: CoinInfo, listed: Boolean, mine: Boolean, followed: Boolean, onBack: () -> Unit, onUnfollow: () -> Unit) {
    val p = t
    val services = LocalServices.current
    val nav = LocalNav.current
    InkHeader {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IconButton(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "Back", onClick = onBack, kind = ButtonKind.Raised, size = 36.dp)
            Text(c.shownName, Modifier.weight(1f), style = manropeStyle(15f, FontWeight.Medium), color = p.onHeader, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CoinMark(c, 40.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${c.shownSymbol} · ${supplyModel(c)}", style = manropeStyle(12.5f, FontWeight.SemiBold), color = p.onHeaderMuted)
                if (listed || mine || followed) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (listed) Pill("listed", Tone.Success, small = true, light = true)
                        if (mine) Pill("you mint", Tone.Primary, small = true, light = true)
                        if (followed) Pill("following", small = true, light = true)
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Circulating", style = manropeStyle(12.5f, FontWeight.SemiBold), color = p.onHeaderMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(formatAmount(circulating(c), c.config.decimals), Modifier.alignByBaseline().weight(1f, fill = false), style = Type.amount, color = p.onHeader)
                Text(c.shownSymbol, Modifier.alignByBaseline(), style = manropeStyle(16f, FontWeight.Medium), color = p.onHeaderMuted)
            }
            Text(
                if (capped(c)) "of at most ${amountOf(c, c.config.maxSupply)}" else "no supply cap",
                style = manropeStyle(12.5f, FontWeight.Medium),
                color = p.onHeaderMuted,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (mine) Button("Mint", onClick = { nav.navigate(MintRoute(c.hex)) }, Modifier.weight(1f), kind = ButtonKind.Light, icon = Icons.Outlined.Add)
            if (followed) {
                Button("Following", onClick = onUnfollow, Modifier.weight(1f), kind = ButtonKind.Raised, icon = Icons.Outlined.Check)
            } else {
                Button("Follow", onClick = { services.store.addCoin(c.hex) }, Modifier.weight(1f), kind = if (mine) ButtonKind.Raised else ButtonKind.Light, icon = Icons.Outlined.Add)
            }
        }
    }
}
