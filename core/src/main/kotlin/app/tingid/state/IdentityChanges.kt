// What the screens that change an identity read from what the person pasted,
// scanned or typed: the key a device link asks to add, the identity and keys
// a recovery link names, a recovery threshold, and a key's kind in words.
// Each says why it refuses, in words the screen shows as they are.
package app.tingid.state

import app.tingid.chain.Canonical
import app.tingid.chain.Id
import app.tingid.chain.Link
import app.tingid.chain.Links
import app.tingid.chain.PolicyKind
import app.tingid.chain.Scheme
import app.tingid.chain.trimWhiteSpace
import app.tingid.text.count

/** A value read from what the person gave, or why there is none. */
sealed class Checked<out T> {
    class Ok<T>(val value: T) : Checked<T>()
    class Refused(val message: String) : Checked<Nothing>()
}

/** A key another device asks to add to an identity. */
class DeviceKey(val scheme: Scheme, val pub: ByteArray)

/** The identity a recovery link names, and the key list (encoded) the recovery would install. */
class RecoverRequest(val id: String, val keys: ByteArray)

/**
 * The key a device link asks to add; null while nothing is typed. A link for
 * another network than `net` (Links.netOf) or another identity than
 * `identity` (hex) is refused, as its key would join the wrong identity;
 * either one null is not known yet, and not checked. `name` is the user's,
 * for that message.
 */
fun deviceLinkKey(text: String, net: String?, identity: String?, name: String?): Checked<DeviceKey>? {
    if (trimWhiteSpace(text).isEmpty()) return null
    return try {
        val link = Links.parseOf<Link.Device>(text, "device")
        when {
            net != null && link.net != net -> Checked.Refused("this link is for another Ting network")
            identity != null && link.id != identity.lowercase() -> Checked.Refused("this device link is for another identity, not ${name ?: "this one"}")
            else -> Checked.Ok(DeviceKey(link.scheme, link.key))
        }
    } catch (e: Exception) {
        Checked.Refused(e.message ?: "this is not a Ting link")
    }
}

/**
 * The recovery a recovery link asks a guardian to approve; null while nothing
 * is typed. A link for another network than `net`, or whose key list cannot
 * be read, is refused.
 */
fun recoverLink(text: String, net: String?): Checked<RecoverRequest>? {
    if (trimWhiteSpace(text).isEmpty()) return null
    return try {
        val link = Links.parseOf<Link.Recover>(text, "recover")
        when {
            net != null && link.net != net -> Checked.Refused("this link is for another Ting network")
            Id.decodeKeyList(link.keys) == null -> Checked.Refused("the link lists no keys this app can read")
            else -> Checked.Ok(RecoverRequest(link.id, link.keys))
        }
    } catch (e: Exception) {
        Checked.Refused(e.message ?: "this is not a Ting link")
    }
}

/**
 * The approvals a recovery needs, as typed; 0 means more than half of the
 * guardians. More than the identity's `guardians` could never be reached,
 * and no recovery would be possible.
 */
fun thresholdOf(text: String, guardians: Long): Checked<Long> {
    if (!Regex("^[0-9]+$").matches(text)) return Checked.Refused("enter a number")
    val n = text.toLongOrNull()
    if (n == null || n > guardians) return Checked.Refused("at most $guardians: the identity has ${count(guardians, "guardian")}")
    return Checked.Ok(n)
}

/** What kind of key a policy makes, in words: its scheme, "multisig", "another identity", or "custom policy" for one the app does not know (or while `c` is not known). */
fun policyLabel(c: Canonical?, policy: ByteArray): String = when (c?.kindOf(policy)) {
    PolicyKind.Secp256k1 -> Scheme.Secp256k1.label
    PolicyKind.P256 -> Scheme.P256.label
    PolicyKind.Multisig -> "multisig"
    PolicyKind.Identity -> "another identity"
    null -> "custom policy"
}
