// Pieces several screens show: the welcome for a phone with no user yet, the
// profile button and its sheet, saving a contact, and when a payment goes.
package app.tingid.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tingid.chain.shortId
import app.tingid.state.Contact
import app.tingid.state.User
import app.tingid.state.UserKind
import app.tingid.state.When
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

private class Feature(val icon: ImageVector, val title: String, val text: String)

private val FEATURES = listOf(
    Feature(Icons.Outlined.AccountBalanceWallet, "Spending", "Pay and get paid at once."),
    Feature(Icons.Outlined.Lock, "Savings", "Withdrawals wait a few days, and you can stop them meanwhile."),
    Feature(Icons.Outlined.VerifiedUser, "Recovery", "Your recovery phrase and your guardians get you back in on a new phone."),
)

/** What Ting ID gives, for a phone with no user yet, and the two ways in: a new identity, or one restored. */
@Composable
fun Welcome() {
    val nav = LocalNav.current
    val p = t
    Card(padded = false) {
        Column {
            FEATURES.forEachIndexed { i, f ->
                if (i > 0) Spacer(Modifier.fillMaxWidth().height(1.dp).background(p.border))
                ListItem(f.title, subtitle = f.text, left = { IconCircle(f.icon, p.primary, p.primarySoft) })
            }
        }
    }
    Button("Create an identity", onClick = { nav.navigate(NewUserRoute(mode = "new")) }, large = true, icon = Icons.Outlined.Add)
    Button("Restore an identity", onClick = { nav.navigate(NewUserRoute(mode = "restore")) }, kind = ButtonKind.Secondary, icon = Icons.Outlined.Refresh)
    Caption("Restore with your recovery phrase, or with your identity’s ID from another of your phones.", Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
}

fun userKindLabel(kind: UserKind): String = if (kind == UserKind.Identity) "Identity" else "Simple account"

/** What a user is, in one line: its identity's short ID, or a simple account. */
fun userLine(u: User): String = if (u.kind == UserKind.Identity) "Identity ${shortId(u.identityId ?: "", 6, 4)}" else "Simple account"

/** The profile button: the active user's avatar, opening the profile sheet. */
@Composable
fun ProfileButton(size: Dp = 38.dp) {
    val services = LocalServices.current
    val active = services.store.data.collectAsState().value.activeUser
    var open by remember { mutableStateOf(false) }
    val p = t
    Box(Modifier.clickable(role = Role.Button) { open = true }.semantics { contentDescription = active?.let { "Users: ${it.name}" } ?: "Users" }) {
        if (active != null) Avatar(active.name, active.color, size)
        else Box(Modifier.size(size).clip(CircleShape).background(p.cardAlt), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Person, null, tint = p.muted, modifier = Modifier.size(size * 0.5f))
        }
    }
    if (open) ProfileSheet(onClose = { open = false })
}

/** Rows in one bordered box, a hairline between them. */
@Composable
fun RowGroup(rows: List<@Composable () -> Unit>) {
    val p = t
    val shape = RoundedCornerShape(Radius.lg)
    Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, p.border, shape)) {
        rows.forEachIndexed { i, row ->
            if (i > 0) Spacer(Modifier.fillMaxWidth().height(1.dp).background(p.border))
            row()
        }
    }
}

/** The user, the way to its identity and the settings, the other users to switch to, and the way to add one. */
@Composable
fun ProfileSheet(onClose: () -> Unit) {
    val services = LocalServices.current
    val nav = LocalNav.current
    val data by services.store.data.collectAsState()
    val active = data.activeUser
    val others = data.users.filter { it.id != active?.id }
    val p = t
    fun go(route: Any) {
        onClose()
        nav.navigate(route)
    }
    Sheet(onClose = onClose) {
        if (active != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Avatar(active.name, active.color, 52.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(active.name, style = Type.heading, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val id = active.identityId
                    if (active.kind == UserKind.Identity && id != null) IdChip(id, label = "ID") else Caption(userKindLabel(active.kind))
                }
            }
            RowGroup(
                listOf(
                    { ListItem("Identity", left = { IconCircle(Icons.Outlined.Fingerprint, p.primary, p.primarySoft, 36.dp) }, chevron = true, onClick = { go(IdentityTab) }) },
                    { ListItem("Settings", left = { IconCircle(Icons.Outlined.Settings, p.primary, p.primarySoft, 36.dp) }, chevron = true, onClick = { go(SettingsTab) }) },
                ),
            )
        } else {
            Caption("No user on this phone yet.")
        }
        if (others.isNotEmpty()) {
            Caption("Switch to", Modifier.padding(horizontal = 4.dp))
            RowGroup(
                others.map { u ->
                    @Composable {
                        ListItem(u.name, subtitle = userLine(u), left = { Avatar(u.name, u.color, 36.dp) }, onClick = {
                            services.store.setActiveUser(u.id)
                            onClose()
                        })
                    }
                },
            )
        }
        RowGroup(listOf { ListItem("Add a user", left = { IconCircle(Icons.Outlined.PersonAdd, p.muted, p.cardAlt, 36.dp) }, onClick = { go(NewUserRoute()) }) })
    }
}

/** Naming an account that was pasted or scanned: it joins the contacts, and the wallet shows the name wherever the account appears. */
@Composable
fun SaveContactSheet(account: String, onClose: () -> Unit) {
    val services = LocalServices.current
    val contacts = services.store.data.collectAsState().value.contacts
    var name by remember { mutableStateOf("") }
    val trimmed = name.trim()
    val taken = trimmed.isNotEmpty() && contacts.any { it.name.trim().lowercase() == trimmed.lowercase() }
    val save = {
        services.store.addContact(Contact(UUID.randomUUID().toString(), trimmed, account.lowercase()))
        name = ""
        onClose()
    }
    Sheet(onClose = onClose, title = "Save as contact") {
        Text(account, style = Type.mono, color = t.muted)
        Field(name, { name = it }, label = "Name", placeholder = "Bob", error = if (taken) "A contact is called that already." else null, onDone = { if (trimmed.isNotEmpty() && !taken) save() })
        Button("Save contact", onClick = save, enabled = trimmed.isNotEmpty() && !taken)
    }
}

enum class WhenChoice { Now, Later }

/**
 * When a payment goes: now, or at a date and time picked with the phone's own
 * pickers. `lock`: the sending account's lock, in seconds; with one, "now" is
 * the earliest it allows, and a time before the lock ends waits for it.
 */
@Composable
fun WhenPicker(choice: WhenChoice, at: Long, lock: Long, onChoice: (WhenChoice) -> Unit, onAt: (Long) -> Unit) {
    val context = LocalContext.current
    val now = System.currentTimeMillis() / 1000
    val zone = ZoneId.systemDefault()
    val local = Instant.ofEpochSecond(at).atZone(zone)
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Segmented(listOf(WhenChoice.Now to (if (lock > 0) "Earliest" else "Now"), WhenChoice.Later to "Later"), choice, onChoice)
        if (choice == WhenChoice.Later) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Choice(Icons.Outlined.CalendarMonth, formatDay(at), "Change the date") {
                    DatePickerDialog(context, { _, y, m, d -> onAt(When.onDay(at, LocalDate.of(y, m + 1, d))) }, local.year, local.monthValue - 1, local.dayOfMonth).apply {
                        datePicker.minDate = now * 1000
                        datePicker.maxDate = (now + When.LATEST) * 1000
                    }.show()
                }
                Choice(Icons.Outlined.AccessTime, formatClock(at), "Change the time") {
                    TimePickerDialog(context, { _, h, min -> onAt(When.atTime(at, LocalTime.of(h, min))) }, local.hour, local.minute, DateFormat.is24HourFormat(context)).show()
                }
            }
        }
        val lockEnds = now + lock
        if (lock > 0 && (choice == WhenChoice.Now || at < lockEnds)) {
            Caption("It runs when the lock ends, ${formatDay(lockEnds)} at ${formatClock(lockEnds)}, and can be cancelled until then.")
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Choice(icon: ImageVector, label: String, description: String, onClick: () -> Unit) {
    val p = t
    val shape = RoundedCornerShape(Radius.md)
    Row(
        Modifier.weight(1f).heightIn(min = 46.dp).clip(shape).background(p.card).border(1.dp, p.border, shape)
            .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = description }.padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, tint = p.primary, modifier = Modifier.size(17.dp))
        Text(label, style = manropeStyle(14.5f, FontWeight.Bold, tabular = true), color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
