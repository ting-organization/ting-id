// Settings → About: the version this phone runs, and, when asked, whether a
// newer release is out on GitHub. Ting ID does not update itself, so this is
// how a security release is found; GitHub is asked only on the tap, and sees
// this phone's address when it is.
package app.tingid.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.tingid.BuildConfig
import app.tingid.chain.HttpRequest
import app.tingid.chain.UrlConnectionHttp
import app.tingid.chain.parseJson
import app.tingid.chain.strOrNull
import app.tingid.state.Versions
import app.tingid.ui.Button
import app.tingid.ui.ButtonKind
import app.tingid.ui.Caption
import app.tingid.ui.Card
import app.tingid.ui.SectionHeader
import app.tingid.ui.sentence
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

private const val RELEASES = "https://api.github.com/repos/ting-organization/ting-id/releases/latest"

private sealed class Check {
    object Idle : Check()
    object Asking : Check()
    object Newest : Check()
    class Newer(val version: String, val url: String) : Check()
    class Failed(val message: String) : Check()
}

@Composable
fun AboutSettings() {
    val mine = BuildConfig.VERSION_NAME
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<Check>(Check.Idle) }
    val check: () -> Unit = {
        state = Check.Asking
        scope.launch {
            state = try {
                val res = UrlConnectionHttp.exchange(HttpRequest(RELEASES, headers = mapOf("accept" to "application/vnd.github+json"), timeoutMs = 15_000, maxBytes = 1 shl 20))
                if (!res.ok) throw IllegalStateException("GitHub answered ${res.status}")
                val release = parseJson(res.text()) as? JsonObject
                val tag = release?.let { runCatching { it.strOrNull("tag_name") }.getOrNull() } ?: ""
                val url = release?.let { runCatching { it.strOrNull("html_url") }.getOrNull() }?.takeIf { it.startsWith("https://github.com/ting-organization/ting-id/") }
                if (Versions.newer(tag, mine) && url != null) Check.Newer(tag.removePrefix("v"), url) else Check.Newest
            } catch (e: Exception) {
                Check.Failed(e.message ?: "GitHub could not be reached")
            }
        }
    }
    SectionHeader("About")
    Card {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Caption("Ting ID $mine")
            Button("Check for updates", onClick = check, kind = ButtonKind.Ghost, small = true, icon = Icons.Outlined.CloudDownload, loading = state is Check.Asking)
        }
        when (val s = state) {
            is Check.Newest -> Caption("This is the newest release.")
            is Check.Newer -> {
                Caption("Ting ID ${s.version} is out. Updates can carry security fixes: install it over this one, and your users and keys stay.")
                Button("Get Ting ID ${s.version}", onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.url))) }, kind = ButtonKind.Secondary, small = true, icon = Icons.AutoMirrored.Outlined.OpenInNew)
            }
            is Check.Failed -> Caption(sentence(s.message))
            else -> {}
        }
        Caption("Checking asks GitHub, which then sees this phone’s address. Nothing is checked unless you tap.")
    }
}
