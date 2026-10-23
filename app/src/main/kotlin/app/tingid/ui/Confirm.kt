// Confirmation as a modal sheet. Serious actions ask first; none asks inline.
// A screen keeps a `ConfirmState` (rememberConfirm), shows its `ConfirmHost`,
// and `confirm.ask(…)` suspends until the person confirms (true) or steps
// back (false). `ConfirmSheet` is the same sheet for a screen that shows more
// in it, such as the fee it pays, kept up to date while it is open.
package app.tingid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class ConfirmOptions(
    val title: String,
    /** One short line on what happens; most confirmations need none. */
    val body: String? = null,
    /** The confirming button, named for the action ("Cancel action", "Delete key"). */
    val confirm: String,
    /** The button that steps back; never "Cancel", which may be the action itself. */
    val back: String = "Back",
    val danger: Boolean = false,
    /** For what cannot be undone: the person types this phrase before the confirming button, solid red, works. */
    val typeToConfirm: String? = null,
)

class ConfirmState {
    var options by mutableStateOf<ConfirmOptions?>(null)
        private set
    private var waiting: CancellableContinuation<Boolean>? = null

    /** Opens the sheet; true when the person confirms. */
    suspend fun ask(o: ConfirmOptions): Boolean = suspendCancellableCoroutine { cont ->
        waiting?.let { if (it.isActive) it.resume(false) }
        waiting = cont
        options = o
        cont.invokeOnCancellation { if (waiting === cont) options = null }
    }

    fun answer(yes: Boolean) {
        val c = waiting
        waiting = null
        options = null
        if (c != null && c.isActive) c.resume(yes)
    }
}

@Composable
fun rememberConfirm(): ConfirmState = remember { ConfirmState() }

/** The sheet of `state`, while it asks. */
@Composable
fun ConfirmHost(state: ConfirmState) {
    val o = state.options ?: return
    Confirm(o.title, o.body, o.confirm, o.back, o.danger, o.typeToConfirm) { state.answer(it) }
}

/** A confirmation sheet; `onDone` gets true when the person confirms, false when they step back. */
@Composable
fun Confirm(
    title: String,
    body: String? = null,
    confirm: String,
    back: String = "Back",
    danger: Boolean = false,
    typeToConfirm: String? = null,
    onDone: (Boolean) -> Unit,
) {
    var typed by remember { mutableStateOf("") }
    val ready = typeToConfirm == null || typed.trim().lowercase() == typeToConfirm.lowercase()
    ConfirmSheet(ConfirmOptions(title, body, confirm, back, danger, typeToConfirm), onDone, ready) {
        if (typeToConfirm != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.Top) {
                Icon(Icons.Outlined.WarningAmber, null, tint = t.danger, modifier = Modifier.size(22.dp))
                if (body != null) Body(body, Modifier.weight(1f), muted = true)
            }
            Field(typed, { typed = it }, label = "Type “$typeToConfirm” to confirm")
        }
    }
}

/**
 * The confirmation sheet itself: the title, the body unless the content says
 * more, the content, then the two buttons. `ready`: whether the confirming
 * button works yet.
 */
@Composable
fun ConfirmSheet(options: ConfirmOptions, onAnswer: (Boolean) -> Unit, ready: Boolean = true, busy: Boolean = false, content: @Composable ColumnScope.() -> Unit = {}) {
    Sheet(onClose = { onAnswer(false) }, title = options.title) {
        if (options.body != null && options.typeToConfirm == null) Body(options.body, muted = true)
        content()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Button(options.back, onClick = { onAnswer(false) }, kind = ButtonKind.Secondary, modifier = Modifier.weight(1f))
            Button(
                options.confirm,
                onClick = { onAnswer(true) },
                kind = if (options.typeToConfirm != null) ButtonKind.Destructive else if (options.danger) ButtonKind.Danger else ButtonKind.Primary,
                enabled = ready,
                loading = busy,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
