// Picking one of the user's accounts, each with what its lock means.
package app.tingid.ui.money

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tingid.state.UserAccount
import app.tingid.text.relativeDuration
import app.tingid.ui.IconCircle
import app.tingid.ui.ListItem
import app.tingid.ui.Pill
import app.tingid.ui.Tone
import app.tingid.ui.t

/** A short label on one account, such as "recommended". */
class AccountBadge(val slot: Long, val label: String)

/**
 * The user's `accounts` as rows to pick one: `slot` is the one picked (null
 * for none) and `onSlot` is told the slot tapped. `note`: what choosing an
 * account means, in place of its lock. `badge`: a label on one account.
 */
@Composable
fun AccountChoice(
    accounts: List<UserAccount>,
    slot: Long?,
    onSlot: (Long) -> Unit,
    note: ((UserAccount) -> String)? = null,
    badge: AccountBadge? = null,
) {
    val p = t
    val shape = RoundedCornerShape(16.dp)
    Column(Modifier.fillMaxWidth().clip(shape).border(Dp.Hairline, p.border, shape).background(p.card)) {
        accounts.forEachIndexed { i, a ->
            val locked = a.delay > 0
            if (i > 0) Hairline()
            ListItem(
                a.label,
                subtitle = note?.invoke(a) ?: if (locked) "withdrawals wait ${relativeDuration(a.delay)}" else "instant",
                left = {
                    IconCircle(
                        if (locked) Icons.Outlined.Lock else Icons.Outlined.AccountBalanceWallet,
                        if (locked) p.accent else p.primary,
                        if (locked) p.accentSoft else p.primarySoft,
                        32.dp,
                    )
                },
                selected = a.slot == slot,
                right = if (badge?.slot == a.slot) ({ Pill(badge.label, Tone.Success, small = true) }) else null,
                onClick = { onSlot(a.slot) },
            )
        }
    }
}
