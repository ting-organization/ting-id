// What the coin tools' screens share (Settings → Coin tools): they are there
// only with the tools switched on, they read the chain's coins from the coin
// index, and they show a coin's name and symbol through Coin.visibleLabel.
package app.tingid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.tingid.Services
import app.tingid.chain.Coin
import app.tingid.state.CoinIndexView
import app.tingid.state.CoinInfo
import app.tingid.state.CoinInfos
import app.tingid.state.knownCoinsFor
import app.tingid.ui.LocalNav
import app.tingid.ui.LocalServices
import app.tingid.ui.WalletTab
import app.tingid.ui.manropeStyle
import app.tingid.ui.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async

/** `content`, while the coin tools are switched on; without them there is no such screen, and the wallet shows instead. */
@Composable
internal fun CoinToolsOnly(content: @Composable () -> Unit) {
    val services = LocalServices.current
    val nav = LocalNav.current
    val on = services.store.data.collectAsState().value.settings.coinTools
    LaunchedEffect(on) {
        if (!on) nav.navigate(WalletTab) { popUpTo(nav.graph.id) { inclusive = true } }
    }
    if (on) content()
}

private val NO_INDEX = CoinIndexView(emptyList(), 0, reading = false, loaded = false, error = null)

/** The coins deployed on the chain `chainHex`, as far as the index has read; it reads on while shown. */
@Composable
internal fun rememberToolsIndex(chainHex: String?): CoinIndexView {
    val services = LocalServices.current
    return remember(chainHex) { services.coinIndexes.index(chainHex) }.collectAsState(NO_INDEX).value
}

/** The coins `ids` that are coins on this chain, read again with each block; the last ones read stay while others load. */
@Composable
internal fun rememberToolsCoins(ids: List<String>): CoinInfos {
    val services = LocalServices.current
    return remember(ids) { services.coins.coinInfos(ids) }.collectAsState(CoinInfos(emptyMap(), true)).value
}

/** The contracts of the coins listed for the chain `chainHex`. */
internal fun toolsListedIds(chainHex: String?): List<String> = chainHex?.let { h -> knownCoinsFor(h).map { it.contract } } ?: emptyList()

/** A coin's name as shown: a hidden character shows as �, so one coin cannot pass for another. */
internal val CoinInfo.shownName: String get() = Coin.visibleLabel(config.name)

/** A coin's symbol as shown, as [shownName]. */
internal val CoinInfo.shownSymbol: String get() = Coin.visibleLabel(config.symbol)

/**
 * Runs a submission in the app's scope, off the main thread (keys sign
 * there), and waits for it while the screen stays: what was sent is noted
 * even when the screen goes meanwhile.
 */
internal suspend fun <T> Services.submitBeyondScreen(work: suspend () -> T): T = scope.async(Dispatchers.IO) { work() }.await()

/** A field's name above a choice that is not a field, as the fields name theirs. */
@Composable
internal fun ToolsLabel(text: String) = Text(text, style = manropeStyle(12.5f, FontWeight.SemiBold), color = t.muted)

/** The hairline between the rows of a card. */
@Composable
internal fun ToolsHairline() = Spacer(Modifier.fillMaxWidth().height(0.5.dp).background(t.border))
