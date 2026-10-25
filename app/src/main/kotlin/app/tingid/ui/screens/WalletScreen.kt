// The wallet home: the coin shown, its total and its accounts on the dark band
// with Send, Receive and Scan, the other coins when there are several, and
// what happened. An account opens its own screen.
package app.tingid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tingid.chain.Links
import app.tingid.chain.formatAmount
import app.tingid.chain.hex
import app.tingid.state.AccountView
import app.tingid.state.ActivityFilter
import app.tingid.state.CoinGroup
import app.tingid.state.CoinInfo
import app.tingid.state.LinkRoutes
import app.tingid.state.LinkSource
import app.tingid.state.OnTheWay
import app.tingid.state.UserKind
import app.tingid.state.coinGroups
import app.tingid.state.onTheWay
import app.tingid.state.scheduledOf
import app.tingid.text.OTHER_CHAIN_USER
import app.tingid.ui.Amount
import app.tingid.ui.Banner
import app.tingid.ui.BannerTone
import app.tingid.ui.Button
import app.tingid.ui.ButtonKind
import app.tingid.ui.Caption
import app.tingid.ui.Card
import app.tingid.ui.CoinRoute
import app.tingid.ui.CoinsTab
import app.tingid.ui.IconCircle
import app.tingid.ui.InkHeader
import app.tingid.ui.ListItem
import app.tingid.ui.LocalNav
import app.tingid.ui.LocalServices
import app.tingid.ui.PhraseRoute
import app.tingid.ui.ProfileButton
import app.tingid.ui.QrScanner
import app.tingid.ui.Radius
import app.tingid.ui.Screen
import app.tingid.ui.SectionHeader
import app.tingid.ui.SendTab
import app.tingid.ui.SettingsTab
import app.tingid.ui.Sheet
import app.tingid.ui.Spacing
import app.tingid.ui.Type
import app.tingid.ui.Welcome
import app.tingid.ui.manropeStyle
import app.tingid.ui.money.ActivityList
import app.tingid.ui.money.CoinGroups
import app.tingid.ui.money.CoinMark
import app.tingid.ui.money.FirstCoins
import app.tingid.ui.money.PendingList
import app.tingid.ui.money.ReceiveSheet
import app.tingid.ui.money.ScheduledSection
import app.tingid.ui.money.rememberAccountViews
import app.tingid.ui.openLink
import app.tingid.ui.openTab
import app.tingid.ui.sentence
import app.tingid.ui.t

private const val SCHEDULED_INFO =
    "Actions of your accounts that run later, in every coin. Check them: anything you did not plan can be cancelled until it runs."

@Composable
fun WalletScreen() {
    val services = LocalServices.current
    val nav = LocalNav.current
    val p = t
    val data by services.store.data.collectAsState()
    val w by services.workspace.state.collectAsState()
    val coinTools = data.settings.coinTools
    val accountViews = rememberAccountViews(w.coins, w.accounts)
    val views = accountViews.views
    val loading = accountViews.loading
    var receiveSlot by remember { mutableStateOf<Long?>(null) }
    var foundSheet by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var coinSheet by remember { mutableStateOf(false) }
    val mine = w.accounts.map { it.account }
    val coinIds = w.coins.map { it.hex }
    val activity = remember(w.scope, coinIds) { services.activities.activity(w.scope, ActivityFilter(w.coins.map { it.id }, identity = true)) }
    val pendingItems = remember(services) { services.pending.items() }
    val pending by pendingItems.collectAsState(initial = emptyList())
    val excluded = coinIds + data.settings.dismissed + listOfNotNull(w.info?.canonical?.registry?.hex())
    val accountsKey = w.accounts.map { it.account.hex() }
    val found by remember(w.scope, accountsKey, excluded) { services.coins.discovered(w.scope, w.accounts, excluded) }.collectAsState(initial = emptyList())

    val user = w.user
    val info = w.info
    val selected = w.accounts.firstOrNull { it.slot == data.activeSlot.toLong() } ?: w.accounts.firstOrNull()
    // A new identity with nothing in it yet: the way to its first coins.
    val holdsNothing = !loading && w.coins.isNotEmpty() && views.values.flatten().all { it.record.available.signum() == 0 && it.record.balance.signum() == 0 }
    val startHere = user != null && user.kind == UserKind.Identity && user.creation != null && info != null && !w.otherChain &&
        (holdsNothing || (w.identity == null && !w.identityLoading))
    val groups = coinGroups(w.coins, views)
    // The coin the band shows: the one used last, else the first followed.
    val shown = groups.firstOrNull { it.coin.hex == data.activeCoin } ?: groups.firstOrNull()

    Screen(header = {
        InkHeader {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Ting ID", Modifier.weight(1f), style = manropeStyle(15f, FontWeight.SemiBold), color = p.onHeader)
                ProfileButton(34.dp)
            }
            if (user != null) {
                if (shown != null) {
                    Balance(
                        shown,
                        views[shown.coin.hex] ?: emptyList(),
                        loading,
                        several = groups.size > 1,
                        onCoins = { coinSheet = true },
                        onAccount = { slot -> nav.navigate(CoinRoute(shown.coin.hex, slot)) },
                        pendingFor = { v -> onTheWay(pending, shown.coin.hex, v.account.account.hex()) },
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    RoundAction("Send", Icons.Outlined.ArrowUpward, light = true, enabled = w.coins.isNotEmpty()) { nav.openTab(SendTab()) }
                    RoundAction("Receive", Icons.Outlined.ArrowDownward, enabled = selected != null) { selected?.let { receiveSlot = it.slot } }
                    RoundAction("Scan", Icons.Outlined.QrCodeScanner) { scanning = true }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Welcome", style = manropeStyle(28f, FontWeight.SemiBold).copy(letterSpacing = (-0.6).sp), color = p.onHeader)
                    Text("Your money and your identity, kept on this phone.", style = manropeStyle(14f, FontWeight.Medium).copy(lineHeight = 20.sp), color = p.onHeaderMuted)
                }
            }
        }
    }) {
        if (w.otherChain) Banner(OTHER_CHAIN_USER, BannerTone.Warning)
        if (user != null && user.phrase != null && !user.phraseBackedUp) {
            val text = buildAnnotatedString {
                append("Write down your recovery phrase: until you do, only this phone can restore ${if (user.kind == UserKind.Identity) "the identity" else "the account"}. ")
                withLink(LinkAnnotation.Clickable("phrase", TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline))) { nav.navigate(PhraseRoute(user.id)) }) {
                    append("Write it down")
                }
            }
            WarningBanner(text)
        }
        if (user != null && user.kind == UserKind.Identity && info != null && !w.otherChain && w.identity == null && !w.identityLoading) {
            Banner("Waiting for the next block to create this identity.", BannerTone.Warning)
        }
        if (user == null) Welcome()
        if (startHere) FirstCoins(user, Links.netOf(info.status.chainId), w.coins.firstOrNull())

        // One coin is all on the band; several are listed here too, each with its accounts.
        if (user != null && w.coins.size != 1) {
            SectionHeader("Coins", action = if (coinTools) "All coins" else "Manage", onAction = { nav.openTab(if (coinTools) CoinsTab else SettingsTab) })
            if (w.coins.isNotEmpty()) {
                CoinGroups(
                    groups,
                    loading,
                    onOpen = { c, slot -> nav.navigate(CoinRoute(c.hex, slot)) },
                    pendingFor = { c, v -> onTheWay(pending, c.hex, v.account.account.hex()) },
                )
            } else {
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        IconCircle(Icons.Outlined.Dns, p.primary, p.primarySoft)
                        Column(Modifier.weight(1f)) {
                            Text(if (w.coinsLoading) "Loading coins…" else "No coins followed yet", style = manropeStyle(15f, FontWeight.Medium), color = p.text)
                            Caption("Add the coins you use in Settings.")
                        }
                    }
                    if (!w.coinsLoading) Button("Add a coin", onClick = { nav.openTab(SettingsTab) }, kind = ButtonKind.Secondary, small = true, icon = Icons.Outlined.Add)
                }
            }
        }

        if (found.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button) { foundSheet = true }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            ) {
                Icon(Icons.Outlined.AutoAwesome, null, tint = p.muted, modifier = Modifier.size(14.dp))
                Caption("You also hold ${found.size} other coin${if (found.size == 1) "" else "s"}")
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = p.muted, modifier = Modifier.size(14.dp))
            }
        }

        if (user != null) ScheduledSection(w, scheduledOf(w.coins, views), acting = null, info = SCHEDULED_INFO)

        if (user != null) PendingList(pending, onRetry = services.pending::retry, onDismiss = services.pending::dismiss, onSpeedUp = services.pending::speedUp)

        if (user != null && w.coins.isNotEmpty()) {
            SectionHeader("Activity")
            ActivityList(activity, w.coinById, w.names, mine)
        }
    }

    ReceiveSheet(
        accounts = w.accounts,
        slot = receiveSlot,
        onSlot = { receiveSlot = it },
        onClose = { receiveSlot = null },
        amountCoin = data.activeCoin?.let { w.coinById[it] } ?: w.coins.firstOrNull(),
    )
    if (scanning) {
        QrScanner(
            onClose = { scanning = false },
            title = "Scan a code",
            onScan = { text ->
                // Every Ting link opens its screen, which asks before anything happens.
                try {
                    val target = LinkRoutes.routeOf(text, LinkSource.Inside)
                    scanning = false
                    nav.openLink(target)
                    null
                } catch (e: Exception) {
                    sentence(e.message ?: "this is not a Ting link")
                }
            },
        )
    }

    if (coinSheet) {
        Sheet(onClose = { coinSheet = false }, title = "Coins") {
            for (g in groups) {
                key(g.coin.hex) {
                    ListItem(
                        g.coin.config.name,
                        subtitle = "${if (loading) "…" else formatAmount(g.total, g.coin.config.decimals)} ${g.coin.config.symbol}",
                        left = { CoinMark(g.coin) },
                        selected = g.coin.hex == shown?.coin?.hex,
                        onClick = {
                            services.store.setActiveCoin(g.coin.hex)
                            coinSheet = false
                        },
                    )
                }
            }
            Button(
                "Manage coins",
                onClick = {
                    coinSheet = false
                    nav.openTab(if (coinTools) CoinsTab else SettingsTab)
                },
                kind = ButtonKind.Ghost,
                small = true,
            )
        }
    }

    if (foundSheet) {
        Sheet(onClose = { foundSheet = false }, title = "Coins you hold") {
            Caption("Coins your accounts hold that you do not follow.")
            for (f in found) {
                key(f.info.hex) {
                    ListItem(
                        f.info.config.name,
                        subtitle = "${formatAmount(f.total, f.info.config.decimals)} ${f.info.config.symbol}",
                        left = { CoinMark(f.info) },
                        right = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                Button(
                                    "Follow",
                                    onClick = {
                                        services.store.addCoin(f.info.hex)
                                        if (found.size == 1) foundSheet = false
                                    },
                                    kind = ButtonKind.Secondary,
                                    small = true,
                                )
                                Button(
                                    "Hide",
                                    onClick = {
                                        services.store.dismissCoin(f.info.hex)
                                        if (found.size == 1) foundSheet = false
                                    },
                                    kind = ButtonKind.Ghost,
                                    small = true,
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * The band's balance: the coin shown, its total, and a tile for each of its
 * accounts with what it has available. With `several` coins, the coin's name
 * opens the choice of coins (`onCoins`); a tile opens its account.
 */
@Composable
private fun Balance(
    group: CoinGroup,
    accounts: List<AccountView>,
    loading: Boolean,
    several: Boolean,
    onCoins: () -> Unit,
    onAccount: (slot: Long) -> Unit,
    pendingFor: (AccountView) -> OnTheWay,
) {
    val p = t
    val coin = group.coin
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                Modifier.then(if (several) Modifier.clip(RoundedCornerShape(Radius.sm)).clickable(role = Role.Button, onClick = onCoins) else Modifier),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CoinMark(coin, 18.dp)
                Text(coin.config.name, style = manropeStyle(13.5f, FontWeight.Medium), color = p.onHeaderMuted)
                if (several) Icon(Icons.Outlined.KeyboardArrowDown, "Choose a coin", tint = p.onHeaderMuted, modifier = Modifier.size(18.dp))
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Amount(
                    if (loading) "…" else formatAmount(group.total, coin.config.decimals),
                    Modifier.alignByBaseline(),
                    style = Type.amount,
                    color = p.onHeader,
                )
                Text(coin.config.symbol, Modifier.alignByBaseline(), style = manropeStyle(16f, FontWeight.Medium), color = p.onHeaderMuted)
            }
        }
        // A single account is the total already.
        if (accounts.size > 1) {
            for (row in accounts.chunked(2)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    for (v in row) AccountTile(coin, v, loading, pendingFor(v), { onAccount(v.account.slot) }, Modifier.weight(1f))
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** An account on the band: its name, a lock when its withdrawals wait, what it has available, and what is reserved or on the way. */
@Composable
private fun AccountTile(coin: CoinInfo, view: AccountView, loading: Boolean, pending: OnTheWay, onClick: () -> Unit, modifier: Modifier) {
    val p = t
    val dec = coin.config.decimals
    val reserved = view.record.locked
    val note = listOfNotNull(
        if (reserved.signum() > 0) "${formatAmount(reserved, dec)} reserved" else null,
        if (pending.count > 0) "${formatAmount(pending.amount, dec)} on the way" else null,
    ).joinToString(" · ")
    Column(
        modifier.clip(RoundedCornerShape(Radius.md)).background(p.headerRaised).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (view.account.delay != 0L) Icon(Icons.Outlined.Lock, null, tint = p.onHeaderMuted, modifier = Modifier.size(13.dp))
            Text(view.account.label, style = manropeStyle(12.5f, FontWeight.Medium), color = p.onHeaderMuted)
        }
        Amount(if (loading) "…" else formatAmount(view.record.available, dec), style = manropeStyle(17f, FontWeight.SemiBold, tabular = true), color = p.onHeader)
        if (note.isNotEmpty()) Text(note, style = manropeStyle(11.5f, FontWeight.Medium), color = p.onHeaderMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A round action on the band, its name under it: `light` for the main one. */
@Composable
private fun RoundAction(label: String, icon: ImageVector, light: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val p = t
    Column(
        Modifier.clip(RoundedCornerShape(Radius.md)).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }.padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(if (light) Color.White else p.headerRaised), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (light) Color(0xFF0B1220) else p.onHeader, modifier = Modifier.size(22.dp))
        }
        Text(label, style = manropeStyle(12.5f, FontWeight.Medium), color = p.onHeader)
    }
}

/** A warning as the app's banners show it, with a link in its text. */
@Composable
private fun WarningBanner(text: AnnotatedString) {
    val p = t
    val shape = RoundedCornerShape(Radius.md)
    Row(Modifier.fillMaxWidth().clip(shape).background(p.warningSoft).border(Dp.Hairline, p.warningLine, shape).padding(Spacing.md), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Outlined.ErrorOutline, null, tint = p.warning, modifier = Modifier.size(18.dp).padding(top = 1.dp))
        Text(text, style = manropeStyle(14f, FontWeight.Medium).copy(lineHeight = 20.sp), color = p.warning, modifier = Modifier.weight(1f))
    }
}
