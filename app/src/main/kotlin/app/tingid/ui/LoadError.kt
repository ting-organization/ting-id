// What the app shows when its saved state could not be read. Nothing is
// overwritten until the person chooses to start fresh.
package app.tingid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight

@Composable
fun LoadError(message: String) {
    val services = LocalServices.current
    val p = t
    var confirming by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(
        Modifier.fillMaxSize().background(p.background).windowInsetsPadding(WindowInsets.statusBars).verticalScroll(rememberScrollState()).padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Text("Ting ID could not read its saved data", style = manropeStyle(22f, FontWeight.ExtraBold), color = p.text)
        Banner(sentence(message), BannerTone.Danger)
        Caption("Nothing has been changed: your users and settings stay as they were saved, and your keys stay in the phone's keystore. Closing Ting ID and opening it again tries once more.")
        Button("Start fresh", onClick = { confirming = true }, kind = ButtonKind.Danger, small = true)
        error?.let { Banner(sentence(it), BannerTone.Danger) }
    }
    if (confirming) {
        Confirm(
            title = "Start fresh?",
            body = "Ting ID starts without users, contacts or settings. The keys it can still find in the saved data stay listed. Identities and funds stay on chain.",
            confirm = "Start fresh",
            back = "Keep the saved data",
            danger = true,
            typeToConfirm = "start fresh",
            onDone = { yes ->
                confirming = false
                if (yes) {
                    try {
                        services.store.startFresh()
                    } catch (e: Exception) {
                        error = e.message ?: "the saved data could not be removed"
                    }
                }
            },
        )
    }
}
