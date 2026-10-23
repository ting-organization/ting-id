// Small behaviours screens share: the owner check before what moves coins
// or changes who controls an identity, holding a screen while it sends, and
// showing a file the app checked.
package app.tingid.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import app.tingid.state.Store
import app.tingid.vault.Owner
import app.tingid.vault.OwnerCheck
import java.io.File

/**
 * The owner check before an operation that moves coins or changes who controls
 * an identity (Settings → Security, "Confirm payments and changes"): payments,
 * identity changes, recovery approvals, minting and signing for sites. True
 * to go on, false when the person cancelled; throws when the phone did not
 * confirm. A phone without a screen lock has nothing to ask.
 */
suspend fun confirmOwner(activity: FragmentActivity, store: Store, title: String, detail: String? = null): Boolean {
    if (!store.data.value.settings.confirmPayments) return true
    return when (Owner.check(activity, title, detail)) {
        OwnerCheck.Failed -> throw IllegalStateException("the phone did not confirm it is you")
        OwnerCheck.Cancelled -> false
        OwnerCheck.Confirmed, OwnerCheck.Unavailable -> true
    }
}

/**
 * A screen that is sending something stays until it is done: going back
 * mid-way would leave the person not knowing whether it went, and the screen
 * that would tell them gone.
 */
@Composable
fun HoldWhileBusy(busy: Boolean) {
    BackHandler(enabled = busy) {}
}

/** A file name safe to write: no folders, no characters a file system refuses. */
fun safeName(name: String): String = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().ifEmpty { "file" }

/**
 * Shows a file the app has checked, such as the one a statement covers,
 * through the system's sheet to open or keep it. The file goes to the app's
 * cache only, shared for reading through the app's file provider.
 */
fun openFile(activity: FragmentActivity, bytes: ByteArray, name: String, mediaType: String) {
    val dir = File(activity.cacheDir, "shared").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() }
    val file = File(dir, safeName(name))
    file.writeBytes(bytes)
    val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mediaType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    activity.startActivity(Intent.createChooser(view, name))
}
