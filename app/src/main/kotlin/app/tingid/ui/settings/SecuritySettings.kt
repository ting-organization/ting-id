// Settings → Security: the phone's own check of its owner when Ting ID opens
// and before payments. Turning either off asks for the check itself, so a
// phone left unlocked cannot just switch it off. Also where the phone keeps
// the keys: its security chip, its trusted environment, or software.
package app.tingid.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.tingid.ui.Banner
import app.tingid.ui.BannerTone
import app.tingid.ui.Caption
import app.tingid.ui.Card
import app.tingid.ui.Divider
import app.tingid.ui.LocalActivity
import app.tingid.ui.LocalServices
import app.tingid.ui.SectionHeader
import app.tingid.ui.Sub
import app.tingid.ui.sentence
import app.tingid.ui.t
import app.tingid.vault.KeyHome
import app.tingid.vault.Owner
import app.tingid.vault.OwnerCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val INFO =
    "The phone checks it is you with your fingerprint, face, or its screen lock’s PIN, pattern or password. Ting ID never sees them: the phone only says yes or no."

@Composable
fun SecuritySettings() {
    val services = LocalServices.current
    val activity = LocalActivity.current
    val settings = services.store.data.collectAsState().value.settings
    val scope = rememberCoroutineScope()
    val available = remember { Owner.available(activity) }
    var problem by remember { mutableStateOf<String?>(null) }
    var home by remember { mutableStateOf<KeyHome?>(null) }
    LaunchedEffect(Unit) { home = withContext(Dispatchers.IO) { runCatching { services.vault.keyHome() }.getOrNull() } }

    fun set(value: Boolean, prompt: String, apply: (Boolean) -> Unit) {
        problem = null
        scope.launch {
            if (!value) {
                when (Owner.check(activity, prompt)) {
                    OwnerCheck.Cancelled -> return@launch
                    OwnerCheck.Failed -> {
                        problem = "the phone did not confirm it is you"
                        return@launch
                    }
                    else -> {}
                }
            }
            apply(value)
        }
    }

    @Composable
    fun row(title: String, hint: String, value: Boolean, prompt: String, apply: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(app.tingid.ui.Spacing.md)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(app.tingid.ui.Spacing.xs)) {
                Sub(title)
                Caption(hint)
            }
            Switch(
                checked = value,
                onCheckedChange = { set(it, prompt, apply) },
                colors = SwitchDefaults.colors(checkedTrackColor = t.primary, uncheckedTrackColor = t.border),
                modifier = Modifier.semantics { contentDescription = title },
            )
        }
    }

    SectionHeader("Security", info = INFO)
    Card {
        if (!available) {
            Caption("This phone has no screen lock. Set a PIN, pattern, password or fingerprint in the phone’s settings, and Ting ID asks for it when it opens and before payments.")
        } else {
            row("Unlock Ting ID", "When it opens, and after a minute away", settings.unlockApp, "Turn off unlocking Ting ID") { v ->
                services.store.setSettings { it.copy(unlockApp = v) }
            }
            Divider()
            row(
                "Confirm payments and changes",
                "Before a payment, an identity change, a recovery approval, minting or signing for a site",
                settings.confirmPayments,
                "Turn off confirming payments and changes",
            ) { v -> services.store.setSettings { it.copy(confirmPayments = v) } }
        }
        when (home) {
            KeyHome.StrongBox -> Caption("Your keys are sealed with a key in the phone’s security chip (StrongBox). Ting ID opens one only to sign, and wipes it right after.")
            KeyHome.TrustedEnvironment -> Caption("Your keys are sealed with a key in the phone’s trusted environment, apart from Android itself. Ting ID opens one only to sign, and wipes it right after.")
            KeyHome.Software -> Caption("This phone seals your keys in software only: it has no security chip or trusted environment for the key that seals them.")
            else -> {}
        }
        problem?.let { Banner(sentence(it), BannerTone.Danger) }
    }
}
