// Transactions this device sent that no block has included yet, and ones that
// failed: where each stands, with Retry and Dismiss where they help.
package app.tingid.ui.money

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.tingid.state.PendingItem
import app.tingid.state.Watch
import app.tingid.text.describeWatch
import app.tingid.ui.Button
import app.tingid.ui.ButtonKind
import app.tingid.ui.Caption
import app.tingid.ui.Card
import app.tingid.ui.IconCircle
import app.tingid.ui.LocalNav
import app.tingid.ui.SectionHeader
import app.tingid.ui.Spacing
import app.tingid.ui.TxRoute
import app.tingid.ui.manropeStyle
import app.tingid.ui.t
import app.tingid.ui.toneColors
import kotlinx.coroutines.launch

/**
 * The waiting and failed submissions `items` (Pending.items), under a
 * heading; nothing when there are none. `onRetry` sends one again (Pending.retry),
 * `onDismiss` puts one away (Pending.dismiss).
 */
@Composable
fun PendingList(items: List<PendingItem>, onRetry: suspend (id: String) -> String?, onDismiss: (id: String) -> Unit) {
    val p = t
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    if (items.isEmpty()) return
    SectionHeader(
        "Waiting (${items.size})",
        icon = Icons.Outlined.Schedule,
        info = "Transactions this device sent that no block has included yet. If the node loses one, the app sends the same transaction again; it can never run twice.",
    )
    Card(padded = false) {
        Column {
            items.forEachIndexed { i, (tx, watch, canRetry) ->
                val d = describeWatch(watch, tx.at)
                val (bg, fg) = toneColors(toneOf(d.tone))
                val stuck = watch is Watch.Failed || watch == Watch.Lost
                if (i > 0) Hairline()
                PressRow({ nav.navigate(TxRoute(tx.id)) }, PaddingValues(vertical = 12.dp, horizontal = Spacing.lg)) {
                    IconCircle(iconOf(d.icon), fg, bg)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(tx.summary, style = manropeStyle(14.5f, FontWeight.Bold), color = p.text)
                        Caption(d.detail, maxLines = 3)
                    }
                }
                if (stuck) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = Spacing.lg, end = Spacing.lg, bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (canRetry) {
                            Button(
                                "Retry",
                                onClick = {
                                    scope.launch {
                                        busy = tx.id
                                        try {
                                            onRetry(tx.id)
                                        } finally {
                                            busy = null
                                        }
                                    }
                                },
                                kind = ButtonKind.Secondary,
                                small = true,
                                icon = Icons.Outlined.Refresh,
                                loading = busy == tx.id,
                            )
                        }
                        Button("Dismiss", onClick = { onDismiss(tx.id) }, kind = ButtonKind.Ghost, small = true)
                    }
                }
            }
        }
    }
}
