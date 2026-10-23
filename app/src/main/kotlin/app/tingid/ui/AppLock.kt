// Ting ID behind the phone's own check of its owner (Settings → Security):
// asked when the app opens, and when it comes back after a minute away. The
// lock covers everything, sheets and dialogs included, and keeps the screen
// the person was on beneath it.
package app.tingid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.tingid.vault.Owner
import app.tingid.vault.OwnerCheck
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How long the app may be away before it asks again. */
private const val AWAY_MS = 60_000L

@Composable
fun AppLock() {
    val services = LocalServices.current
    val activity = LocalActivity.current
    val on = services.store.data.collectAsState().value.settings.unlockApp
    // Locked from the start while the check is on, until the phone says it cannot check.
    var locked by remember { mutableStateOf(on && Owner.available(activity)) }
    var problem by remember { mutableStateOf<String?>(null) }
    var awaySince by remember { mutableStateOf<Long?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun unlock() {
        problem = null
        when (Owner.check(activity, "Unlock Ting ID")) {
            OwnerCheck.Confirmed, OwnerCheck.Unavailable -> locked = false
            OwnerCheck.Failed -> problem = "The phone did not confirm it is you."
            OwnerCheck.Cancelled -> {}
        }
    }

    // At the start, a phone that can check asks at once.
    LaunchedEffect(Unit) {
        if (locked) unlock()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (awaySince == null) awaySince = System.currentTimeMillis()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        val since = awaySince
        awaySince = null
        // Away long enough, unless the phone confirmed its owner meanwhile: a check's PIN screen
        // takes the app to the background too. A check still finishing is waited for, a few seconds at most.
        if (!on || since == null || System.currentTimeMillis() - since < AWAY_MS) return@LifecycleEventEffect
        scope.launch {
            var i = 0
            while (i++ < 10 && Owner.checking) delay(500)
            if (Owner.confirmedAt > since || !Owner.available(activity)) return@launch
            locked = true
            unlock()
        }
    }

    if (!on || !locked) return
    val p = t
    // A dialog, so the lock covers sheets and other dialogs too.
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler { activity.moveTaskToBack(true) }
        Column(
            Modifier.fillMaxSize().background(p.header).clickable(remember { MutableInteractionSource() }, indication = null) {}.padding(Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.lg, Alignment.CenterVertically),
        ) {
            Logo(64.dp)
            Text("Ting ID is locked", style = manropeStyle(22f, FontWeight.ExtraBold), color = p.onHeader)
            Text(problem ?: "Unlock with your fingerprint, face or screen lock.", style = manropeStyle(14f, FontWeight.Medium), color = p.onHeaderMuted, textAlign = TextAlign.Center)
            Button("Unlock", onClick = { scope.launch { unlock() } }, kind = ButtonKind.Light, icon = Icons.Outlined.LockOpen)
        }
    }
}
