// The recovery phrase of the active user, near the end of Settings: whether
// the words restore it, a reminder to write them down, and making one for an
// identity that has none, or whose phrase key a recovery or change replaced.
package app.tingid.ui.identity

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import app.tingid.chain.Canonical
import app.tingid.chain.Id
import app.tingid.chain.equal
import app.tingid.chain.unhex
import app.tingid.state.IdentityInfo
import app.tingid.state.KeyMeta
import app.tingid.state.User
import app.tingid.state.UserKind
import app.tingid.state.linkedKeyId
import app.tingid.ui.Banner
import app.tingid.ui.BannerTone
import app.tingid.ui.Button
import app.tingid.ui.ButtonKind
import app.tingid.ui.Caption
import app.tingid.ui.Card
import app.tingid.ui.LocalActivity
import app.tingid.ui.LocalNav
import app.tingid.ui.LocalServices
import app.tingid.ui.PhraseRoute
import app.tingid.ui.ProposeRoute
import app.tingid.ui.SectionHeader
import app.tingid.ui.formatTime
import app.tingid.ui.sentence
import app.tingid.vault.NotConfirmed
import app.tingid.vault.Owner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val INFO =
    "Twelve words that restore this identity on a new phone. Your guardians can restore it too, and replace the words if someone else got hold of them."

@Composable
fun PhraseCard(user: User, identity: IdentityInfo?, c: Canonical?, keys: List<KeyMeta>, loading: Boolean = false) {
    val services = LocalServices.current
    val activity = LocalActivity.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    // With several users, whose words these are.
    val several = services.store.data.collectAsState().value.users.size > 1
    val title = if (several) "${user.name}’s recovery phrase" else "Recovery phrase"
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val key = keys.firstOrNull { it.id == user.phraseKeyId }
    val show = { nav.navigate(PhraseRoute(user.id)) }

    // A new phrase: its words to write down first, then the change that adds its key.
    val add: () -> Unit = {
        busy = true
        error = null
        scope.launch {
            try {
                services.keys.newUserPhrase(user, Owner.prompt(activity))
                services.store.updateUser(user.id) { it.copy(phraseToAdd = true) }
                nav.navigate(PhraseRoute(user.id, next = "add"))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // The person stepped back from the phone's check: nothing to say.
                if (!(e is NotConfirmed && e.message == "cancelled")) error = e.message ?: "the words could not be kept"
            } finally {
                busy = false
            }
        }
    }

    // A bare key account has no phrase: its key is the account.
    if (user.kind == UserKind.Key) {
        SectionHeader(title)
        Card { Caption("This account has no recovery phrase: its key exists only on this phone.") }
        return
    }

    val listed = identity != null && c != null && key != null && linkedKeyId(c, identity, key) != null
    // A change adding the phrase's key that has not applied yet.
    val pending = if (identity != null && key != null) {
        val pub = unhex(key.pub)
        identity.pending.firstOrNull { p -> p.record.operations.any { it is Id.Operation.AddKey && equal(it.key.params, pub) } }
    } else {
        null
    }
    val showTitle = if (user.phraseBackedUp) "Show recovery phrase" else "Write it down"

    SectionHeader(title, info = INFO)
    Card {
        error?.let { Banner(sentence(it), BannerTone.Danger) }
        when {
            user.phrase != null && listed -> {
                Caption(if (user.phraseBackedUp) "The words restore this identity on a new phone." else "Write the words down, so they can restore this identity.")
                Button(showTitle, onClick = show, kind = if (user.phraseBackedUp) ButtonKind.Secondary else ButtonKind.Primary, icon = Icons.Outlined.Lock)
            }
            user.phrase != null && pending != null -> {
                Caption("The words restore this identity once the change adding their key applies, ${formatTime(pending.record.effectiveAt)}.")
                Button(showTitle, onClick = show, kind = ButtonKind.Secondary, icon = Icons.Outlined.Lock)
            }
            // New words whose change was never proposed, as when the screen was left before.
            user.phrase != null && identity != null && user.phraseToAdd && key != null -> {
                Caption("Your new words are kept on this phone, but their key is not added to the identity yet, so they do not restore it.")
                Button("Add their key", onClick = { nav.navigate(ProposeRoute(kind = "addKey", key = key.id)) }, icon = Icons.Outlined.Add)
            }
            user.phrase != null && identity != null -> {
                Caption("The words on this phone no longer restore this identity: a recovery or a change replaced their key.")
                Button("Make a new recovery phrase", onClick = add, kind = ButtonKind.Secondary, icon = Icons.Outlined.Add, loading = busy)
            }
            identity != null -> {
                val guarded = identity.core.guardianCount > 0
                Caption(
                    if (guarded) "No recovery phrase on this phone. Your guardians can still restore the identity."
                    else "No recovery phrase on this phone, and no guardians: if this phone is lost, so is the identity. Add a recovery phrase.",
                )
                Button("Add a recovery phrase", onClick = add, kind = if (guarded) ButtonKind.Secondary else ButtonKind.Primary, icon = Icons.Outlined.Add, loading = busy)
            }
            loading -> Caption("Reading the identity…")
            // Not on the chain yet, or no node answers: the words are on this phone all the same.
            user.phrase != null -> {
                Caption("The words restore this identity once a block includes it.")
                Button(showTitle, onClick = show, kind = ButtonKind.Secondary, icon = Icons.Outlined.Lock)
            }
            else -> Caption("No recovery phrase on this phone.")
        }
    }
}
