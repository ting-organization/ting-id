// Every coin on the chain, with the coin tools switched on in Settings: the
// listed ones first, then the ones you follow, then the newest. A coin opens
// its details; Create coin deploys a new one.
package app.tingid.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tingid.chain.formatAmount
import app.tingid.chain.trimWhiteSpace
import app.tingid.state.CoinFilter
import app.tingid.state.coinRows
import app.tingid.text.capped
import app.tingid.text.circulating
import app.tingid.text.minterAccount
import app.tingid.text.originalOf
import app.tingid.text.supplyModel
import app.tingid.ui.Banner
import app.tingid.ui.BannerTone
import app.tingid.ui.Button
import app.tingid.ui.ButtonKind
import app.tingid.ui.Caption
import app.tingid.ui.Card
import app.tingid.ui.CoinToolsRoute
import app.tingid.ui.Empty
import app.tingid.ui.Field
import app.tingid.ui.ListItem
import app.tingid.ui.Loading
import app.tingid.ui.LocalNav
import app.tingid.ui.LocalServices
import app.tingid.ui.NewCoinRoute
import app.tingid.ui.Pill
import app.tingid.ui.Screen
import app.tingid.ui.Segmented
import app.tingid.ui.Spacing
import app.tingid.ui.Tone
import app.tingid.ui.money.CoinMark
import app.tingid.ui.sentence

@Composable
fun CoinsScreen() {
    CoinToolsOnly { CoinBrowser() }
}

private val FILTERS = listOf(CoinFilter.All to "All", CoinFilter.Following to "Following", CoinFilter.Yours to "You mint")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CoinBrowser() {
    val services = LocalServices.current
    val nav = LocalNav.current
    val w by services.workspace.state.collectAsState()
    val followed = services.store.data.collectAsState().value.settings.coins
    val chainHex = w.info?.chainHex
    val height = w.info?.status?.height
    val index = rememberToolsIndex(chainHex)
    // Followed coins count even before the index reaches their block.
    val ids = remember(index.coins, followed) { (index.coins + followed).distinct() }
    val infos = rememberToolsCoins(ids)
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(CoinFilter.All) }

    val listedIds = toolsListedIds(chainHex)
    // Every coin in the order deployed, to tell an original from its copies.
    val all = infos.infos.values.toList()
    val rows = coinRows(all, filter, query, followed, listedIds, w.accounts)
    val searching = trimWhiteSpace(query).isNotEmpty()

    Screen(
        title = "Coins",
        tab = true,
        right = {
            Button("Create coin", onClick = { nav.navigate(NewCoinRoute) }, kind = ButtonKind.Light, small = true, icon = Icons.Outlined.Add, enabled = w.user != null && w.info != null)
        },
    ) {
        Field(query, { query = it }, placeholder = "Search by name, symbol or contract ID")
        Segmented(FILTERS, filter) { filter = it }
        index.error?.let { Banner(sentence(it), BannerTone.Danger) }
        if (rows.isNotEmpty()) {
            Card(padded = false) {
                Column {
                    rows.forEachIndexed { i, c ->
                        val listed = c.hex in listedIds
                        val following = c.hex in followed
                        val copy = originalOf(c, all, listedIds)
                        val mine = minterAccount(c, w.accounts)
                        val decimals = c.config.decimals
                        val supply = formatAmount(circulating(c), decimals) + if (capped(c)) " of ${formatAmount(c.config.maxSupply, decimals)}" else ""
                        if (i > 0) ToolsHairline()
                        ListItem(
                            "${c.shownName} · ${c.shownSymbol}",
                            subtitle = "${supplyModel(c)} · $supply",
                            left = { CoinMark(c, 36.dp) },
                            right = if (listed || copy != null || mine != null || following) {
                                {
                                    FlowRow(
                                        Modifier.widthIn(max = 140.dp),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        if (listed) Pill("listed", Tone.Success, small = true)
                                        if (copy != null) Pill("not the original", Tone.Danger, small = true)
                                        if (mine != null) Pill("you mint", Tone.Primary, small = true)
                                        if (following && !listed) Pill("following", small = true)
                                    }
                                }
                            } else {
                                null
                            },
                            chevron = true,
                            onClick = { nav.navigate(CoinToolsRoute(c.hex)) },
                        )
                    }
                }
            }
        } else if (infos.loading || !index.loaded) {
            Loading()
        } else {
            Empty(if (searching || filter != CoinFilter.All) "No coins match" else "No coins yet", icon = Icons.Outlined.Layers)
        }
        Caption(
            if (index.reading) "Reading the chain for coins… block ${index.readTo} of ${height ?: "…"}" else "Every coin created up to block ${index.readTo}.",
            Modifier.padding(horizontal = Spacing.xs),
        )
    }
}
