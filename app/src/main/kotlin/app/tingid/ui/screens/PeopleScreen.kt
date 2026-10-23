// People: the users on this phone, to switch between, add, rename or remove,
// and the contacts, to pay, copy, rename or remove. Each row has a menu of its
// own.
package app.tingid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.tingid.chain.isHex
import app.tingid.chain.shortId
import app.tingid.chain.trimWhiteSpace
import app.tingid.state.Contact
import app.tingid.state.USER_COLORS
import app.tingid.state.User
import app.tingid.state.UserKind
import app.tingid.ui.Avatar
import app.tingid.ui.Banner
import app.tingid.ui.BannerTone
import app.tingid.ui.Button
import app.tingid.ui.Caption
import app.tingid.ui.Card
import app.tingid.ui.ConfirmHost
import app.tingid.ui.ConfirmOptions
import app.tingid.ui.Field
import app.tingid.ui.IconCircle
import app.tingid.ui.ListItem
import app.tingid.ui.LocalNav
import app.tingid.ui.LocalServices
import app.tingid.ui.NewUserRoute
import app.tingid.ui.PhraseRoute
import app.tingid.ui.RowGroup
import app.tingid.ui.Screen
import app.tingid.ui.SectionHeader
import app.tingid.ui.SendTab
import app.tingid.ui.Sheet
import app.tingid.ui.Spacing
import app.tingid.ui.TAKEN
import app.tingid.ui.copyText
import app.tingid.ui.money.Hairline
import app.tingid.ui.nameTaken
import app.tingid.ui.openTab
import app.tingid.ui.rememberConfirm
import app.tingid.ui.sentence
import app.tingid.ui.t
import app.tingid.ui.userLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID

/** The person a menu is open for. */
private sealed class Menu {
    class OfUser(val user: User) : Menu()
    class OfContact(val contact: Contact) : Menu()
}

@Composable
fun PeopleScreen() {
    val p = t
    val services = LocalServices.current
    val nav = LocalNav.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val confirm = rememberConfirm()
    val data by services.store.data.collectAsState()
    val chain = services.caches.current.collectAsState().value?.chain
    val users = data.users
    val contacts = data.contacts
    val active = data.activeUser
    var removeError by remember { mutableStateOf<String?>(null) }
    var contactSheet by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<Menu?>(null) }
    var renaming by remember { mutableStateOf<Menu?>(null) }

    fun removeUser(u: User) {
        scope.launch {
            // Words not written down are only on this phone: removing the user deletes them.
            val unwritten = u.phrase != null && !u.phraseBackedUp
            if (unwritten) {
                val write = confirm.ask(
                    ConfirmOptions(
                        title = "${u.name}’s recovery phrase is not written down",
                        body = "This phone holds the only copy of its words, and removing the user deletes them. Write them down first.",
                        confirm = "Write it down",
                        back = "Remove anyway…",
                    ),
                )
                if (write) {
                    nav.navigate(PhraseRoute(u.id))
                    return@launch
                }
            }
            val yes = confirm.ask(
                ConfirmOptions(
                    title = "Remove ${u.name} from this phone?",
                    body = when {
                        unwritten -> "Its recovery phrase is deleted with it, and no written copy exists. Without the words or guardians who can recover it, the identity and its funds are gone for good."
                        u.phrase != null -> "Its funds stay on chain, and its recovery phrase is deleted from this phone: with your written copy, Restore adds it again."
                        u.kind == UserKind.Key -> "Its funds stay on chain, and only its key can spend them. The key stays on this phone: with the advanced settings on, Settings → Keys can add it as a user again."
                        else -> "Its funds stay on chain. This phone’s key for it stays: restoring the identity by its ID uses it again."
                    },
                    confirm = "Remove",
                    back = "Keep",
                    danger = true,
                    typeToConfirm = if (unwritten) "remove" else null,
                ),
            )
            if (!yes) return@launch
            removeError = null
            // Forgetting goes to the end even if the screen goes meanwhile.
            services.scope.launch {
                try {
                    services.keys.forgetUser(u)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    removeError = "${u.name} was not removed: ${e.message}"
                }
            }
        }
    }

    // The row with its menu button beside it, not inside it: one control does not hold another.
    @Composable
    fun WithMenu(label: String, onMenu: () -> Unit, selected: Boolean = false, row: @Composable () -> Unit) {
        Row(Modifier.fillMaxWidth().background(if (selected) p.primarySoft else Color.Transparent), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { row() }
            Icon(
                Icons.Outlined.MoreHoriz,
                contentDescription = null,
                tint = p.muted,
                modifier = Modifier.clickable(role = Role.Button, onClick = onMenu).semantics { contentDescription = label }
                    .padding(top = 8.dp, bottom = 8.dp, start = 4.dp, end = Spacing.lg).size(20.dp),
            )
        }
    }

    val close = { menu = null }

    Screen(title = "People") {
        SectionHeader(
            "Users on this phone",
            info = "Each user is an identity, or an account of a single key. Tap one to make it active. Removing one only forgets it on this phone; its funds stay on chain.",
            action = "Add",
            onAction = { nav.navigate(NewUserRoute()) },
        )
        removeError?.let { Banner(sentence(it), BannerTone.Danger) }
        Card(padded = false) {
            Column {
                if (users.isEmpty()) Caption("No users yet.", Modifier.padding(Spacing.lg))
                users.forEachIndexed { i, u ->
                    if (i > 0) Hairline()
                    val on = u.id == active?.id
                    WithMenu("More for ${u.name}", { menu = Menu.OfUser(u) }, on) {
                        ListItem(
                            u.name,
                            left = { Avatar(u.name, u.color) },
                            subtitleContent = { Caption(if (on) "Active · ${userLine(u)}" else userLine(u), maxLines = 1) },
                            selected = on,
                            onClick = { services.store.setActiveUser(u.id) },
                            right = if (on) ({ Icon(Icons.Outlined.CheckCircle, null, tint = p.primary, modifier = Modifier.size(20.dp)) }) else null,
                        )
                    }
                }
            }
        }

        SectionHeader(
            "Contacts",
            info = "Accounts you send to, by name. Save one from Send or Review when you pay a new account.",
            action = "Add",
            onAction = { contactSheet = true },
        )
        Card(padded = false) {
            Column {
                if (contacts.isEmpty()) Caption("No contacts yet. Accounts you pay can be saved here by name.", Modifier.padding(Spacing.lg))
                contacts.forEachIndexed { i, c ->
                    if (i > 0) Hairline()
                    WithMenu("More for ${c.name}", { menu = Menu.OfContact(c) }) {
                        ListItem(
                            c.name,
                            left = { Avatar(c.name, USER_COLORS[0], soft = true) },
                            subtitleContent = {
                                Caption("${shortId(c.account, 10, 8)}${if (c.chain != null && c.chain != chain) " · another network" else ""}", maxLines = 1)
                            },
                            onClick = { menu = Menu.OfContact(c) },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(Spacing.lg))
    }

    when (val m = menu) {
        is Menu.OfUser -> Sheet(onClose = close, title = m.user.name) {
            val u = m.user
            val identityId = u.identityId
            RowGroup(
                listOfNotNull<@Composable () -> Unit>(
                    if (u.id != active?.id) {
                        {
                            MenuRow(Icons.Outlined.SwapHoriz, "Make active") {
                                services.store.setActiveUser(u.id)
                                close()
                            }
                        }
                    } else {
                        null
                    },
                    if (u.kind == UserKind.Identity && identityId != null) {
                        {
                            MenuRow(Icons.Outlined.ContentCopy, "Copy identity ID") {
                                scope.launch { copyText(clipboard, identityId) }
                                close()
                            }
                        }
                    } else {
                        null
                    },
                    {
                        MenuRow(Icons.Outlined.Edit, "Rename") {
                            close()
                            renaming = Menu.OfUser(u)
                        }
                    },
                    {
                        MenuRow(Icons.Outlined.Delete, "Remove from this phone…", danger = true) {
                            close()
                            removeUser(u)
                        }
                    },
                ),
            )
        }
        is Menu.OfContact -> Sheet(onClose = close, title = m.contact.name) {
            val c = m.contact
            Caption(c.account, maxLines = 2)
            RowGroup(
                listOf(
                    {
                        MenuRow(Icons.Outlined.ArrowUpward, "Send to ${c.name}") {
                            close()
                            // The Send tab with this account filled in, as a tab: a new payment, not the one left there.
                            nav.openTab(SendTab(to = c.account, at = System.currentTimeMillis()))
                        }
                    },
                    {
                        MenuRow(Icons.Outlined.ContentCopy, "Copy account") {
                            scope.launch { copyText(clipboard, c.account) }
                            close()
                        }
                    },
                    {
                        MenuRow(Icons.Outlined.Edit, "Rename") {
                            close()
                            renaming = Menu.OfContact(c)
                        }
                    },
                    {
                        MenuRow(Icons.Outlined.Delete, "Remove contact…", danger = true) {
                            close()
                            scope.launch {
                                if (confirm.ask(ConfirmOptions(title = "Remove contact ${c.name}?", confirm = "Remove", back = "Keep", danger = true))) {
                                    services.store.removeContact(c.id)
                                }
                            }
                        }
                    },
                ),
            )
        }
        null -> {}
    }

    val stopRenaming = { renaming = null }
    when (val r = renaming) {
        is Menu.OfUser -> RenameSheet(r.user.name, stopRenaming) { name -> services.store.updateUser(r.user.id) { it.copy(name = name) } }
        is Menu.OfContact -> RenameSheet(r.contact.name, stopRenaming, taken = { nameTaken(contacts, it, except = r.contact.id) }) { name ->
            services.store.renameContact(r.contact.id, name)
        }
        null -> {}
    }
    if (contactSheet) NewContactSheet(onClose = { contactSheet = false })
    ConfirmHost(confirm)
}

/** Saving an account under a name. */
@Composable
private fun NewContactSheet(onClose: () -> Unit) {
    val services = LocalServices.current
    var name by remember { mutableStateOf("") }
    var account by remember { mutableStateOf("") }
    Sheet(onClose = onClose, title = "New contact") {
        Field(name, { name = it }, label = "Name", placeholder = "Bob")
        Field(account, { account = it }, label = "Account", placeholder = "64 hex digits", keyboardType = KeyboardType.Ascii)
        Button(
            "Save",
            enabled = trimWhiteSpace(name).isNotEmpty() && isHex(account, 32),
            onClick = {
                services.store.addContact(Contact(UUID.randomUUID().toString(), trimWhiteSpace(name), trimWhiteSpace(account).lowercase()))
                onClose()
            },
        )
    }
}

/** A new name for a user or a contact; `taken` tells a name another contact has. */
@Composable
private fun RenameSheet(current: String, onClose: () -> Unit, taken: (String) -> Boolean = { false }, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    val trimmed = trimWhiteSpace(name)
    val clash = taken(trimmed)
    val ready = trimmed.isNotEmpty() && trimmed != current && !clash
    val save = {
        onSave(trimmed)
        onClose()
    }
    Sheet(onClose = onClose, title = "Rename $current") {
        Field(name, { name = it }, label = "Name", error = if (clash) TAKEN else null, onDone = { if (ready) save() })
        Button("Save", onClick = save, enabled = ready)
    }
}

@Composable
private fun MenuRow(icon: ImageVector, title: String, danger: Boolean = false, onClick: () -> Unit) {
    val p = t
    ListItem(
        title,
        left = { IconCircle(icon, if (danger) p.danger else p.primary, if (danger) p.dangerSoft else p.primarySoft, 34.dp) },
        onClick = onClick,
    )
}
