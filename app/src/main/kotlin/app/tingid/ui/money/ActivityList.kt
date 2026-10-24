// The user's activity as a card: the newest first, by day, a page at a time.
package app.tingid.ui.money

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tingid.chain.hex
import app.tingid.state.ActivityPage
import app.tingid.state.CoinInfo
import app.tingid.text.Names
import app.tingid.text.describeActivity
import app.tingid.text.groupMinerPayments
import app.tingid.ui.Avatar
import app.tingid.ui.Amount
import app.tingid.ui.Button
import app.tingid.ui.ButtonKind
import app.tingid.ui.Caption
import app.tingid.ui.Card
import app.tingid.ui.Empty
import app.tingid.ui.IconCircle
import app.tingid.ui.Loading
import app.tingid.ui.LocalNav
import app.tingid.ui.LocalServices
import app.tingid.ui.Spacing
import app.tingid.ui.TxRoute
import app.tingid.ui.dayLabel
import app.tingid.ui.formatClock
import app.tingid.ui.manropeStyle
import app.tingid.ui.t
import app.tingid.ui.toneColors
import app.tingid.state.ActivityList as Activity

/** Each item's day heading: shown above the first item of a day; an item of unknown time stays under the one before it. */
private fun dayHeadings(days: List<String?>): List<String?> {
    var last: String? = null
    return days.map { day ->
        if (day == null || day == last) {
            null
        } else {
            last = day
            day
        }
    }
}

/**
 * The newest activity first, by day, a page at a time: each row names who it
 * was with, what happened and when, and opens its transaction. `activity` is
 * a list's view of the user's activity (Activities.activity, remembered by the
 * screen): the screen shows more as it is scrolled to the end by calling its
 * `showMore`, and once everything found is shown, older blocks are searched on
 * request. `coins`: the coins known, by hex; `mine`: the user's accounts.
 */
@Composable
fun ActivityList(activity: Activity, coins: Map<String, CoinInfo>, names: Names, mine: List<ByteArray>) {
    val p = t
    val nav = LocalNav.current
    val advanced = LocalServices.current.store.data.collectAsState().value.settings.advanced
    val page by activity.page.collectAsState(initial = ActivityPage(emptyList(), loading = true, searching = false, hidden = 0, searchedTo = null))
    val loading = page.loading
    val searchedTo = page.searchedTo
    val items = groupMinerPayments(page.items, ::dayLabel)
    val headings = dayHeadings(items.map { if (it.time != 0L) dayLabel(it.time) else null })
    Card(padded = false) {
        Column {
            if (loading && items.isEmpty()) Loading()
            if (!loading && items.isEmpty()) {
                if (searchedTo == null) {
                    Empty("No activity yet", "Payments, scheduled actions and identity changes show up here.", Icons.Outlined.Receipt)
                } else {
                    Empty("No recent activity", "Nothing in the blocks since block $searchedTo.", Icons.Outlined.Receipt)
                }
            }
            items.forEachIndexed { i, item ->
                val d = describeActivity(item, coins, names, mine, advanced)
                val heading = headings[i]
                val c = coins[item.contract.hex()]
                // A party this phone has no name for shows as the action, its account in the line under it.
                val party = d.party?.takeIf { it.known || it.coin }
                if (heading != null) {
                    Text(
                        heading.uppercase(),
                        Modifier.padding(start = Spacing.lg, end = Spacing.lg, top = if (i == 0) 12.dp else 16.dp, bottom = 2.dp),
                        style = manropeStyle(11.5f, FontWeight.Medium).copy(letterSpacing = 0.6.sp),
                        color = p.faint,
                    )
                } else if (i > 0) {
                    Hairline()
                }
                PressRow({ nav.navigate(TxRoute(item.transaction)) }, PaddingValues(vertical = 11.dp, horizontal = Spacing.lg)) {
                    when {
                        party?.coin == true && c != null -> CoinMark(c, 38.dp)
                        party != null -> Avatar(party.name, "", 38.dp, soft = true)
                        else -> {
                            val (bg, fg) = toneColors(toneOf(d.tone))
                            IconCircle(iconOf(d.icon), fg, bg, 38.dp)
                        }
                    }
                    val what = (if (party != null) d.short else d.detail)?.takeIf { it.isNotEmpty() }
                    val time = if (item.time != 0L) formatClock(item.time) else null
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(party?.name ?: d.title, style = manropeStyle(14.5f, FontWeight.Medium), color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (what != null || time != null) Caption(listOfNotNull(what, time).joinToString(" · "), maxLines = 2)
                    }
                    val amount = d.amount
                    val planned = d.planned
                    if (amount != null) {
                        val tone = d.amountTone
                        Amount(amount, style = manropeStyle(14.5f, FontWeight.Medium, tabular = true), color = if (tone != null) toneColors(toneOf(tone)).second else p.text)
                    } else if (planned != null) {
                        Amount(planned, style = manropeStyle(14.5f, FontWeight.Medium, tabular = true), color = p.faint)
                    }
                }
            }
            if (page.hidden > 0) {
                Hairline()
                Box(Modifier.fillMaxWidth().padding(Spacing.sm), contentAlignment = Alignment.Center) {
                    Button("Show more", onClick = activity::showMore, kind = ButtonKind.Ghost, small = true)
                }
            } else if (page.searching && !loading) {
                if (items.isNotEmpty()) Hairline()
                Row(
                    Modifier.fillMaxWidth().padding(Spacing.md),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(color = p.muted, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    Caption("Searching older blocks…")
                }
            } else if (searchedTo != null && !loading) {
                if (items.isNotEmpty()) Hairline()
                Column(Modifier.fillMaxWidth().padding(Spacing.sm), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Button("Load older activity", onClick = activity::searchOlder, kind = ButtonKind.Ghost, small = true, icon = Icons.Outlined.Schedule)
                    Caption("Searched back to block $searchedTo")
                }
            }
        }
    }
}
