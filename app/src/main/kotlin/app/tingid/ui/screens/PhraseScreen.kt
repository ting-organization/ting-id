// A user's recovery phrase: shown after the phone confirms its owner, or
// right after it was made, with screenshots blocked. Writing it down is
// confirmed by typing three of the words back; until then the wallet keeps
// reminding.
package app.tingid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import app.tingid.chain.trimWhiteSpace
import app.tingid.state.PhraseCheck
import app.tingid.state.PhraseLock
import app.tingid.state.UserKind
import app.tingid.ui.Banner
import app.tingid.ui.BannerTone
import app.tingid.ui.Button
import app.tingid.ui.ButtonKind
import app.tingid.ui.Caption
import app.tingid.ui.Card
import app.tingid.ui.Field
import app.tingid.ui.IdChip
import app.tingid.ui.LocalActivity
import app.tingid.ui.LocalNav
import app.tingid.ui.LocalServices
import app.tingid.ui.NoAutofill
import app.tingid.ui.PhraseRoute
import app.tingid.ui.ProposeRoute
import app.tingid.ui.Radius
import app.tingid.ui.Screen
import app.tingid.ui.SecureWindow
import app.tingid.ui.Spacing
import app.tingid.ui.Sub
import app.tingid.ui.Type
import app.tingid.ui.WalletTab
import app.tingid.ui.manropeStyle
import app.tingid.ui.sentence
import app.tingid.ui.t
import app.tingid.vault.NotConfirmed
import app.tingid.vault.Owner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun PhraseScreen(route: PhraseRoute) {
    SecureWindow()
    NoAutofill()
    val services = LocalServices.current
    val activity = LocalActivity.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val id = route.id
    val user = services.store.data.collectAsState().value.users.firstOrNull { it.id == id }
    // Words just made show without asking the phone again; they are held here only, never saved.
    var words by remember(id) { mutableStateOf(services.phrases.freshWords(id)?.use { it.words() }) }
    // The places asked back, picked when checking starts; none while the words show.
    var places by remember { mutableStateOf<List<Int>?>(null) }
    var answers by remember { mutableStateOf(List(PhraseCheck.CHECKS) { "" }) }
    val noLock = remember { services.phrases.phraseLock() == PhraseLock.Sealed }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Words just made are held only while this screen is open; afterwards showing them asks the phone.
    DisposableEffect(id) {
        onDispose {
            services.phrases.forgetFresh(id)
            words = null
            places = null
            answers = List(PhraseCheck.CHECKS) { "" }
        }
    }

    fun leave() {
        services.phrases.forgetFresh(id)
        // `next=add`: a new phrase for an identity, whose key a change then adds.
        val keyId = services.store.data.value.users.firstOrNull { it.id == id }?.phraseKeyId
        if (route.next == "add" && keyId != null) nav.navigate(ProposeRoute(kind = "addKey", key = keyId)) { popUpTo<PhraseRoute> { inclusive = true } }
        else nav.back()
    }

    fun show() {
        val u = user ?: return
        busy = true
        error = null
        scope.launch {
            try {
                val stored = services.phrases.readPhrase(u, Owner.prompt(activity)) ?: throw IllegalStateException("this phone keeps no recovery phrase for this user")
                words = stored.use { it.words() }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // The person stepped back from the phone's check: nothing to say.
                if (!(e is NotConfirmed && e.message == "cancelled")) error = e.message ?: "the phone could not show the words"
            } finally {
                busy = false
            }
        }
    }

    fun startCheck() {
        // Picked by the system's generator, so no one knows beforehand which words are asked.
        answers = List(PhraseCheck.CHECKS) { "" }
        places = PhraseCheck.places(words?.size ?: 12)
    }

    fun check() {
        val w = words ?: return
        val at = places ?: return
        if (!PhraseCheck.matches(w, at, answers)) {
            error = "one of the words does not match: look at your copy again"
            return
        }
        error = null
        services.store.updateUser(id) { it.copy(phraseBackedUp = true) }
        leave()
    }

    Screen(title = "Recovery phrase") {
        if (user?.phrase == null) {
            Banner("This phone keeps no recovery phrase for this user.", BannerTone.Warning)
            Button("Back", onClick = ::leave, kind = ButtonKind.Secondary)
        } else {
            if (noLock) Banner("This phone has no screen lock: anyone holding it can open Ting ID and see these words.", BannerTone.Warning)
            error?.let { Banner(sentence(it), BannerTone.Danger) }
            Card {
                Sub("${user.name}’s recovery phrase")
                Caption(
                    "Anyone with these words can act for ${if (user.kind == UserKind.Identity) "this identity" else "this account"}. Write them on paper, in this order, and keep it " +
                        "somewhere safe. Never type them into a website, and never share a photo of them.",
                )
                user.identityId?.let {
                    Caption("Write down its ID with them: restoring by ID, or a recovery by your guardians, needs it.")
                    IdChip(it, label = "ID", full = true)
                }
            }

            val shown = words
            val asked = places
            when {
                shown == null -> Button("Show the words", onClick = ::show, large = true, icon = Icons.Outlined.Visibility, loading = busy)
                asked == null -> {
                    WordGrid(shown)
                    if (user.phraseBackedUp) {
                        Button("Done", onClick = ::leave, kind = ButtonKind.Secondary)
                    } else {
                        Button("I wrote them down", onClick = ::startCheck, large = true)
                        Button("Later", onClick = ::leave, Modifier.align(Alignment.CenterHorizontally), kind = ButtonKind.Ghost)
                    }
                }
                else -> Card {
                    Sub("Check your copy")
                    Caption("Type these words from what you wrote down.")
                    asked.forEachIndexed { i, place ->
                        Field(answers[i], { v -> answers = answers.mapIndexed { j, a -> if (j == i) v else a } }, label = "Word ${place + 1}", secret = true)
                    }
                    Button("Check", onClick = ::check, enabled = answers.none { trimWhiteSpace(it).isEmpty() })
                    Button("Show the words again", onClick = { places = null }, Modifier.align(Alignment.CenterHorizontally), kind = ButtonKind.Ghost)
                }
            }
        }
    }
}

/** The words in two columns, numbered, as they are written down. */
@Composable
private fun WordGrid(words: List<String>) {
    val p = t
    val shape = RoundedCornerShape(Radius.md)
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        for (row in words.indices.chunked(2)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                for (i in row) {
                    Row(
                        Modifier.weight(1f).clip(shape).background(p.card).border(Dp.Hairline, p.border, shape).padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("${i + 1}", style = manropeStyle(12f, FontWeight.SemiBold), color = p.faint, textAlign = TextAlign.End, modifier = Modifier.width(20.dp))
                        Text(words[i], style = Type.body.copy(fontWeight = FontWeight.Medium), color = p.text)
                    }
                }
                if (row.size < 2) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** Back to the screen before, or to the wallet when a link opened this one. */
private fun NavHostController.back() {
    if (previousBackStackEntry != null) popBackStack()
    else navigate(WalletTab) { popUpTo(graph.id) { inclusive = true } }
}
