// The screen a Ting link opens (LINKS.md). Scanned or pasted in the app,
// every link opens its screen. Opened from outside, only those whose screen
// asks before anything happens and moves only the person's own coins: a pay,
// create, sign or call link. A device or recovery link opened from a message could
// lead someone into adding a stranger's key, so those open from inside only.
package app.tingid.state

import app.tingid.chain.Link
import app.tingid.chain.LinkError
import app.tingid.chain.Links
import app.tingid.chain.trimWhiteSpace

/** Where a link leads; `code` is the link as written, which the screen reads again. */
sealed class LinkTarget(val code: String) {
    class Send(code: String) : LinkTarget(code)
    class Sign(code: String) : LinkTarget(code)
    class Sponsor(code: String) : LinkTarget(code)
    class AddDevice(code: String) : LinkTarget(code)
    class HelpRecover(code: String) : LinkTarget(code)
    class Call(code: String) : LinkTarget(code)
}

enum class LinkSource { Inside, Outside }

object LinkRoutes {
    /** Where `text` leads; throws [LinkError] saying why it leads nowhere. */
    fun routeOf(text: String, from: LinkSource): LinkTarget {
        val link = Links.parse(text)
        val code = trimWhiteSpace(text)
        if (from == LinkSource.Outside && (link is Link.Device || link is Link.Recover)) {
            throw LinkError("open this link from the app: scan it or paste it there")
        }
        return when (link) {
            is Link.Pay -> LinkTarget.Send(code)
            is Link.Sign -> LinkTarget.Sign(code)
            is Link.Create -> LinkTarget.Sponsor(code)
            is Link.Device -> LinkTarget.AddDevice(code)
            is Link.Recover -> LinkTarget.HelpRecover(code)
            is Link.Call -> LinkTarget.Call(code)
        }
    }

    /** Where a link opened from outside goes: its screen, or nowhere (the wallet). */
    fun fromOutside(text: String): LinkTarget? = try {
        routeOf(text, LinkSource.Outside)
    } catch (e: LinkError) {
        null
    }
}
