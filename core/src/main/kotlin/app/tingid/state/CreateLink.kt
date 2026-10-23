// Someone else's create link (LINKS.md), read before this phone sets up the
// identity it holds: the creation must be for the network the app is on, one
// this app can read, and one the registry would take. The identity's ID comes
// from the creation itself, never from the link's word.
package app.tingid.state

import app.tingid.chain.Id
import app.tingid.chain.Link
import app.tingid.chain.Links

/** A create link read and checked: the link, the settings it creates and their ID; or why it cannot be used. */
sealed class CreateLinkReading {
    class Ok(val link: Link.Create, val settings: Id.Settings, val id: ByteArray) : CreateLinkReading()
    class Problem(val message: String) : CreateLinkReading()
}

/** Reads `code` as a create link for the chain `chainId`; with no chain known yet, the network is not checked. */
fun readCreateLink(code: String, chainId: ByteArray?): CreateLinkReading {
    val link = try {
        Links.parseOf<Link.Create>(code, "create")
    } catch (e: Exception) {
        return CreateLinkReading.Problem(e.message ?: "this is not a create link")
    }
    if (chainId != null && link.net != Links.netOf(chainId)) return CreateLinkReading.Problem("this link is for another Ting network")
    val settings = Id.decodeCreation(link.input) ?: return CreateLinkReading.Problem("the link does not hold an identity this app can read")
    val id = Id.idOf(link.input)
    Id.validSettings(settings, id)?.let { return CreateLinkReading.Problem("the registry would refuse this identity: $it") }
    return CreateLinkReading.Ok(link, settings, id)
}
