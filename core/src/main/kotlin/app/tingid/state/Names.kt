// Names this device knows for accounts and identities.
package app.tingid.state

import app.tingid.chain.Canonical
import app.tingid.chain.Id
import app.tingid.chain.hex
import app.tingid.chain.isHex
import app.tingid.chain.policyAccount
import app.tingid.chain.unhex
import app.tingid.text.Names
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Account and ID (hex) → name: contacts by their names, the identity a contact
 * is for by its name too, identities by their users' names and their accounts
 * as "Anna · Savings", key users' accounts, and a user's guardian by the
 * contact that has its Spending account.
 */
fun names(users: List<User>, keys: List<KeyMeta>, contacts: List<Contact>, c: Canonical?): Names {
    val names = LinkedHashMap<String, String>()
    for (contact in contacts) {
        names[contact.account.lowercase()] = contact.name
        contact.identity?.let { names[it.lowercase()] = contact.name }
    }
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
    // A guardian saved as a contact by its Spending account alone, as one paid before, goes by that contact's name.
    if (c != null) {
        for (g in users.flatMap(::guardiansOf).distinct()) {
            if (g in names) continue
            names[Id.account(c.registry, unhex(g), 0).hex()]?.let { names[g] = it }
        }
    }
    return names
}

/** The name of identity `id` (hex) here: its own, or that of the contact with its Spending account on `registry`. */
fun identityName(names: Names, registry: ByteArray?, id: String): String? =
    names[id] ?: registry?.let { names[Id.account(it, unhex(id), 0).hex()] }

/** The guardians this device knows a user to have (hex IDs): named when it was created here, or linked from here since. */
fun guardiansOf(user: User): List<String> {
    val created = user.creation?.let { Id.decodeCreation(unhex(it)) }?.guardians?.map { it.hex() } ?: emptyList()
    return (created + user.knownGuardians.filter { isHex(it, 32) }.map { it.lowercase() }).distinct()
}

/** The names this device knows, with the contacts of the chain the app is on. */
fun names(chain: Chain): Flow<Names> = combine(chain.store.data, chain.info, chain.caches.current) { d, info, cache ->
    names(d.users, d.keys, d.contacts.filter { it.chain == null || it.chain == cache?.chain }, info?.canonical)
}.distinctUntilChanged()
