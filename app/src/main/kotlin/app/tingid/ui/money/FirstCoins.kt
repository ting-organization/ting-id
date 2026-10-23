// For an identity made on this phone that holds no coins yet: a create link
// to hand to someone who has some (LINKS.md). They send coins to start with,
// and create the identity too if no block has yet.
package app.tingid.ui.money

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tingid.chain.Link
import app.tingid.chain.Links
import app.tingid.chain.unhex
import app.tingid.state.CoinInfo
import app.tingid.state.User
import app.tingid.ui.Button
import app.tingid.ui.ButtonKind
import app.tingid.ui.Caption
import app.tingid.ui.Card
import app.tingid.ui.IconCircle
import app.tingid.ui.LinkShare
import app.tingid.ui.Sub
import app.tingid.ui.t

/**
 * The way to an identity's first coins: `user` made here (nothing shows for
 * one without its creation input), `net` the chain's network tag
 * (Links.netOf), `coin` the coin to ask for, if any.
 */
@Composable
fun FirstCoins(user: User, net: String, coin: CoinInfo?) {
    val p = t
    var open by remember { mutableStateOf(false) }
    val creation = user.creation ?: return
    val link = remember(creation, net, user.name, coin?.hex) { Links.encode(Link.Create(net, unhex(creation), name = user.name, coin = coin?.hex)) }
    val symbol = coin?.config?.symbol ?: "coins"
    Card {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconCircle(Icons.Outlined.CardGiftcard, p.accent, p.accentSoft)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Sub("Get your first coins")
                Caption(
                    "Ask someone who has $symbol to scan this or open the link in Ting ID. They can send you some to start with, and set up your identity if no block has yet.",
                )
            }
        }
        if (open) LinkShare(link)
        else Button("Show the link", onClick = { open = true }, kind = ButtonKind.Secondary, small = true, icon = Icons.Outlined.QrCode, modifier = Modifier.fillMaxWidth())
    }
}
