// Names this device knows for accounts and identities.
package app.tingid.state

import app.tingid.chain.Canonical
import app.tingid.chain.Id
import app.tingid.chain.hex
import app.tingid.chain.policyAccount
import app.tingid.chain.unhex
import app.tingid.text.Names
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Account and ID (hex) → name: contacts by their names, identities by their
 * users' names and their accounts as "Anna · Savings", and key users' accounts.
 */
fun names(users: List<User>, keys: List<KeyMeta>, contacts: List<Contact>, c: Canonical?): Names {
    val names = LinkedHashMap<String, String>()
    for (contact in contacts) names[contact.account.lowercase()] = contact.name
    for (user in users) {
        if (user.kind == UserKind.Identity && user.identityId != null && user.registry != null) {
            names[user.identityId.lowercase()] = user.name
            val slots = user.creation?.let { Id.decodeCreation(unhex(it)) }?.slots
            val count = maxOf(slots?.size ?: 0, 2)
            for (slot in 0L until maxOf(count, 4)) {
                val account = Id.account(unhex(user.registry), unhex(user.identityId), slot)
                names[account.hex()] = "${user.name} · ${slotLabel(slot)}"
            }
        } else if (user.kind == UserKind.Key && c != null) {
            val key = keys.firstOrNull { it.id == user.keyId }
            if (key != null) names[policyAccount(c.policyFor(key.scheme), unhex(key.pub)).hex()] = user.name
        }
    }
    return names
}

/** The names this device knows, with the contacts of the chain the app is on. */
fun names(chain: Chain): Flow<Names> = combine(chain.store.data, chain.info, chain.caches.current) { d, info, cache ->
    names(d.users, d.keys, d.contacts.filter { it.chain == null || it.chain == cache?.chain }, info?.canonical)
}.distinctUntilChanged()
