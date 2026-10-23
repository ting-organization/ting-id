// Typing a recovery phrase: one numbered field per word, the list's words
// offered for the one being typed, and a pasted phrase spread over the
// fields (core: PhraseEntry). Keyboards are told not to learn or suggest the
// words: they are a secret.
package app.tingid.ui.identity

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tingid.chain.Phrase
import app.tingid.state.PhraseEntry
import app.tingid.ui.Button
import app.tingid.ui.ButtonKind
import app.tingid.ui.Caption
import app.tingid.ui.ListItem
import app.tingid.ui.Radius
import app.tingid.ui.Sheet
import app.tingid.ui.Spacing
import app.tingid.ui.Sub
import app.tingid.ui.Type
import app.tingid.ui.manropeStyle
import app.tingid.ui.sentence
import app.tingid.ui.t

/**
 * The words of a recovery phrase, typed. `words` holds one entry per field,
 * empty where nothing is typed yet; the caller keeps them in plain `remember`
 * state only, clears them when its screen leaves, and calls `SecureWindow()`
 * and `NoAutofill()` while this shows.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PhraseInput(words: List<String>, onChange: (List<String>) -> Unit) {
    val p = t
    val width = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    val columns = if (width >= 600.dp) 3 else 2
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    // One per field; a new length makes new fields.
    val requesters = remember(words.size) { List(words.size) { FocusRequester() } }
    var focused by remember { mutableStateOf<Int?>(null) }
    var lengths by remember { mutableStateOf(false) }

    fun close() {
        focusManager.clearFocus()
        keyboard?.hide()
    }

    fun focus(i: Int) {
        // A field that is not on screen yet (the length just changed) cannot take focus.
        if (i < requesters.size) runCatching { requesters[i].requestFocus() } else close()
    }

    fun enter(i: Int, raw: String) {
        val entered = PhraseEntry.enter(words, i, raw)
        onChange(entered.words)
        when (entered.move) {
            PhraseEntry.Move.Close -> close()
            PhraseEntry.Move.Next -> focus(i + 1)
            null -> {}
        }
    }

    fun pick(i: Int, word: String) {
        onChange(words.mapIndexed { j, w -> if (j == i) word else w })
        focus(i + 1)
    }

    val filled = words.count { it.isNotEmpty() }
    val problem = PhraseEntry.problem(words, focused)
    val typing = focused?.let { words.getOrNull(it) } ?: ""
    val offers = if (typing.isNotEmpty() && !PhraseEntry.finishedWord(typing)) Phrase.suggest(typing) else emptyList()

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Sub("Recovery phrase", Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            if (filled > 0) Button("Clear", onClick = { onChange(PhraseEntry.fields(words.size)) }, kind = ButtonKind.Ghost, small = true)
            Row(
                Modifier.clickable(role = Role.Button) { lengths = true }.semantics { contentDescription = "${words.size} words, change" }.padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text("${words.size} words", style = manropeStyle(13.5f, FontWeight.Bold), color = p.primary)
                Icon(Icons.Outlined.KeyboardArrowDown, null, tint = p.primary, modifier = Modifier.size(14.dp))
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        for (row in words.indices.chunked(columns)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                for (i in row) {
                    val word = words[i]
                    val bad = word.isNotEmpty() && (if (i == focused) Phrase.suggest(word, 1).isEmpty() else !PhraseEntry.isPhraseWord(word))
                    WordField(
                        i = i,
                        word = word,
                        last = i == words.size - 1,
                        focused = i == focused,
                        bad = bad,
                        requester = requesters[i],
                        onValue = { enter(i, it) },
                        onFocus = { has -> if (has) focused = i else if (focused == i) focused = null },
                        onNext = { focus(i + 1) },
                    )
                }
                // The last row keeps the columns' width.
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
            val at = focused
            if (at != null && at in row && typing.isNotEmpty()) {
                if (offers.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (w in offers) Button(w, onClick = { pick(at, w) }, kind = ButtonKind.Secondary, small = true)
                    }
                } else {
                    Text("No recovery phrase word starts with these letters.", style = Type.caption, color = p.danger)
                }
            }
        }
    }
    if (problem != null) Text(sentence(problem), style = Type.caption, color = p.danger)
    else Caption("$filled of ${words.size} words")

    if (lengths) {
        Sheet(onClose = { lengths = false }, title = "Number of words") {
            Caption("Ting ID makes phrases of 12 words. Phrases of up to 24 words work too.")
            for (n in Phrase.LENGTHS) {
                ListItem(
                    "$n words",
                    selected = n == words.size,
                    right = if (n == words.size) ({ Icon(Icons.Outlined.Check, null, tint = p.primary, modifier = Modifier.size(18.dp)) }) else null,
                    onClick = {
                        onChange(PhraseEntry.fields(n, words))
                        lengths = false
                    },
                )
            }
        }
    }
}

/**
 * One word's field, its number at the left. Its keyboard is the one of the
 * app's secret fields (`Field(secret = true)`): no learning, no suggestions,
 * no corrections, no capitals.
 */
@Composable
private fun RowScope.WordField(
    i: Int,
    word: String,
    last: Boolean,
    focused: Boolean,
    bad: Boolean,
    requester: FocusRequester,
    onValue: (String) -> Unit,
    onFocus: (Boolean) -> Unit,
    onNext: () -> Unit,
) {
    val p = t
    val shape = RoundedCornerShape(Radius.md)
    Row(
        Modifier.weight(1f).clip(shape).background(p.cardAlt).border(1.dp, if (bad) p.danger else if (focused) p.primary else p.border, shape).padding(start = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${i + 1}", style = manropeStyle(12f, FontWeight.SemiBold), color = p.muted, textAlign = TextAlign.End, modifier = Modifier.widthIn(min = 18.dp))
        BasicTextField(
            value = word,
            onValueChange = onValue,
            singleLine = true,
            textStyle = manropeStyle(16f, FontWeight.SemiBold).copy(color = p.text),
            cursorBrush = SolidColor(p.primary),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Password,
                imeAction = if (last) ImeAction.Done else ImeAction.Next,
            ),
            keyboardActions = KeyboardActions(onNext = { onNext() }, onDone = { onNext() }),
            modifier = Modifier.weight(1f).focusRequester(requester).onFocusChanged { onFocus(it.isFocused) }
                .semantics { contentDescription = "Word ${i + 1}" }.padding(horizontal = 8.dp, vertical = 9.dp),
        )
    }
}
