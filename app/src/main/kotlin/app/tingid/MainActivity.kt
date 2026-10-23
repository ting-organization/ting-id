// The one activity. It draws edge to edge, keeps its screens out of the recent
// apps' previews, and takes Ting links from other apps (only those the app
// opens from outside: LinkRoutes).
package app.tingid

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import app.tingid.state.LinkRoutes
import app.tingid.state.LinkTarget
import app.tingid.ui.AppShell
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : FragmentActivity() {
    /** A link another app opened Ting ID with, until the shell takes it. */
    val incoming = MutableStateFlow<Pair<LinkTarget, Long>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The recent apps show no picture of what was on screen: balances, names, a phrase.
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        if (savedInstanceState == null) take(intent)
        val services = (application as TingApp).services
        setContent { AppShell(services, this) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        take(intent)
    }

    private fun take(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val text = intent.dataString ?: return
        // A `tingid:` link is the same request under the app's own scheme.
        val link = if (text.startsWith("tingid:", ignoreCase = true)) "ting:" + text.substring(7) else text
        LinkRoutes.fromOutside(link)?.let { incoming.value = it to System.currentTimeMillis() }
    }
}
