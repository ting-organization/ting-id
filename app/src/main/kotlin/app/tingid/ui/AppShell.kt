// The app around its screens: the theme, the saved state's load error, the
// tabs and the screens over them, the nodes' status while the app is in
// front, links from other apps, and the lock.
package app.tingid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.tingid.MainActivity
import app.tingid.Services
import app.tingid.chain.PoolNotice
import app.tingid.state.LinkTarget
import app.tingid.text.nodeNotice
import app.tingid.ui.screens.CoinScreen
import app.tingid.ui.screens.CoinToolsScreen
import app.tingid.ui.screens.CoinsScreen
import app.tingid.ui.screens.IdentityScreen
import app.tingid.ui.screens.MintScreen
import app.tingid.ui.screens.MinterScreen
import app.tingid.ui.screens.NewCoinScreen
import app.tingid.ui.screens.NewUserScreen
import app.tingid.ui.screens.PeopleScreen
import app.tingid.ui.screens.PhraseScreen
import app.tingid.ui.screens.ProposeScreen
import app.tingid.ui.screens.RecoveryScreen
import app.tingid.ui.screens.ReviewScreen
import app.tingid.ui.screens.SendScreen
import app.tingid.ui.screens.SettingsScreen
import app.tingid.ui.screens.SignScreen
import app.tingid.ui.screens.SponsorScreen
import app.tingid.ui.screens.TxScreen
import app.tingid.ui.screens.WalletScreen

/** The app's services, for every screen. */
val LocalServices = staticCompositionLocalOf<Services> { error("no services") }

/** The activity, for the owner's check (BiometricPrompt needs it) and the window. */
val LocalActivity = staticCompositionLocalOf<FragmentActivity> { error("no activity") }

/** Where screens go. */
val LocalNav = staticCompositionLocalOf<NavHostController> { error("no navigation") }

@Composable
fun AppShell(services: Services, activity: MainActivity) {
    val data by services.store.data.collectAsState()
    val loaded by services.store.loaded.collectAsState()
    val loadError by services.store.loadError.collectAsState()
    TingTheme(data.settings.theme) {
        // Dark icons on the light page, light ones in the dark theme and over a dark header band.
        val dark = t.dark
        val lightBars = !dark && InkHeaders.count == 0
        SideEffect {
            WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                isAppearanceLightStatusBars = lightBars
                isAppearanceLightNavigationBars = !dark
            }
        }
        CompositionLocalProvider(LocalServices provides services, LocalActivity provides activity) {
            Box(Modifier.fillMaxSize().background(t.background)) {
                when {
                    loadError != null -> LoadError(loadError!!)
                    loaded -> Main(services, activity)
                    else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Loading() }
                }
            }
        }
    }
}

@Composable
private fun Main(services: Services, activity: MainActivity) {
    val nav = rememberNavController()
    // The nodes' status is asked while the app is in front.
    LifecycleStartEffect(services.chain) {
        val watch = services.chain.watchStatus()
        onStopOrDispose { watch.close() }
    }
    val incoming by activity.incoming.collectAsState()
    LaunchedEffect(incoming) {
        val (target, at) = incoming ?: return@LaunchedEffect
        activity.incoming.value = null
        nav.navigate(routeOf(target, at))
    }
    var version1Notice by remember { mutableStateOf(services.removedVersion1) }
    CompositionLocalProvider(LocalNav provides nav, LocalNotices provides { Notices() }) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                NavHost(nav, startDestination = WalletTab) {
                    composable<WalletTab> { WalletScreen() }
                    composable<SendTab> { SendScreen(it.toRoute()) }
                    composable<CoinsTab> { CoinsScreen() }
                    composable<IdentityTab> { IdentityScreen() }
                    composable<PeopleTab> { PeopleScreen() }
                    composable<SettingsTab> { SettingsScreen() }
                    composable<CoinRoute> { CoinScreen(it.toRoute()) }
                    composable<CoinToolsRoute> { CoinToolsScreen(it.toRoute()) }
                    composable<NewCoinRoute> { NewCoinScreen() }
                    composable<MintRoute> { MintScreen(it.toRoute()) }
                    composable<MinterRoute> { MinterScreen(it.toRoute()) }
                    composable<NewUserRoute> { NewUserScreen(it.toRoute()) }
                    composable<ReviewRoute> { ReviewScreen() }
                    composable<SponsorRoute> { SponsorScreen(it.toRoute()) }
                    composable<TxRoute> { TxScreen(it.toRoute()) }
                    composable<ProposeRoute> { ProposeScreen(it.toRoute()) }
                    composable<RecoveryRoute> { RecoveryScreen(it.toRoute()) }
                    composable<SignRoute> { SignScreen(it.toRoute()) }
                    composable<PhraseRoute> { PhraseScreen(it.toRoute()) }
                }
            }
            TabBar(nav, coinTools = services.store.data.collectAsState().value.settings.coinTools)
        }
        AppLock()
        if (version1Notice) {
            Sheet(onClose = { version1Notice = false }, title = "Ting ID starts fresh") {
                Body("This version of Ting ID keeps keys in a new, safer way, so it could not take over what the earlier version kept on this phone, and removed it.", muted = true)
                Body("To use your identity here again, choose Add a user, then restore it with your recovery phrase. Your identity and your coins are on chain and are not affected.", muted = true)
                Button("OK", onClick = { version1Notice = false })
            }
        }
    }
}

private fun routeOf(target: LinkTarget, at: Long): Any = when (target) {
    is LinkTarget.Send -> SendTab(code = target.code, at = at)
    is LinkTarget.Sign -> SignRoute(code = target.code, at = at)
    is LinkTarget.Sponsor -> SponsorRoute(code = target.code, at = at)
    is LinkTarget.AddDevice -> ProposeRoute(kind = "addKey", code = target.code, at = at)
    is LinkTarget.HelpRecover -> RecoveryRoute(mode = "help", code = target.code, at = at)
}

/** Opens a link scanned or pasted in the app, which opens every kind. */
fun NavHostController.openLink(target: LinkTarget) = navigate(routeOf(target, System.currentTimeMillis()))

/** The notices on every screen: the nodes, and a change the phone did not save. */
@Composable
private fun Notices() {
    val services = LocalServices.current
    val saveError by services.store.saveError.collectAsState()
    val error by services.chain.error.collectAsState()
    val info by services.chain.info.collectAsState()
    val pool by services.chain.pool.collectAsState()
    val notice by pool.notice.collectAsState()
    if (saveError != null) {
        Banner(
            "Ting ID could not save your latest changes: they are lost if the app closes. Free some space on the phone. ($saveError)",
            BannerTone.Danger,
            Icons.Outlined.Save,
        )
    }
    // The one message on every screen about the nodes: none answers, or their answers did not check out.
    val message = nodeNotice(info?.status?.height, error, notice)
    if (message != null) {
        val distrusted = notice is PoolNotice.Distrusted && error == null
        Banner(message, if (distrusted) BannerTone.Danger else BannerTone.Warning, if (error != null) Icons.Outlined.CloudOff else Icons.Outlined.Shield)
    }
}

private class Tab(val route: Any, val label: String, val icon: ImageVector)

@Composable
private fun TabBar(nav: NavHostController, coinTools: Boolean) {
    val entry by nav.currentBackStackEntryAsState()
    val destination = entry?.destination
    val tabs = listOfNotNull(
        Tab(WalletTab, "Wallet", Icons.Outlined.AccountBalanceWallet),
        Tab(SendTab(), "Send", Icons.AutoMirrored.Outlined.Send),
        if (coinTools) Tab(CoinsTab, "Coins", Icons.Outlined.Layers) else null,
        Tab(IdentityTab, "Identity", Icons.Outlined.Fingerprint),
        Tab(PeopleTab, "People", Icons.Outlined.People),
        Tab(SettingsTab, "Settings", Icons.Outlined.Settings),
    )
    val onTab = destination != null && tabs.any { destination.hasRoute(it.route::class) }
    if (!onTab) return
    val p = t
    Column(Modifier.fillMaxWidth().background(p.card)) {
        Spacer(Modifier.fillMaxWidth().height(1.dp).background(p.border))
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 6.dp)) {
            for (tab in tabs) {
                val selected = destination.hasRoute(tab.route::class)
                val color = if (selected) p.ink else p.faint
                Column(
                    Modifier.weight(1f).clickable(role = Role.Tab) {
                        nav.navigate(tab.route) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }.padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Icon(tab.icon, null, tint = color, modifier = Modifier.size(24.dp))
                    Text(tab.label, style = manropeStyle(11f, FontWeight.Bold), color = color)
                }
            }
        }
    }
}
