// Ting links (LINKS.md): written and read back, and refused when wrong.
package app.tingid.chain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LinksTest {
    private val net = "15d6549c"
    private val account = "ab".repeat(32)
    private val daler = "305378d17f6af84a563d0f8a8455084948aff7330b62b4f2f98a44b82ba113c4"
    private val pub = publicKey(filled(7))

    private fun show(l: Link): String = when (l) {
        is Link.Pay -> "pay ${l.net} ${l.to} ${l.coin} ${l.amount} ${l.note}"
        is Link.Create -> "create ${l.net} ${l.input.hex()} ${l.name} ${l.coin} ${l.amount}"
        is Link.Device -> "device ${l.net} ${l.id} ${l.scheme} ${l.key.hex()}"
        is Link.Recover -> "recover ${l.net} ${l.id} ${l.keys.hex()}"
        is Link.Sign -> "sign ${l.url}"
    }

    private fun refuses(text: String, why: String) {
        val e = assertThrows(LinkError::class.java) { Links.parse(text) }
        assertTrue("${e.message} should say $why", e.message!!.contains(why))
    }

    @Test
    fun `base64url refuses padding, other letters, impossible lengths and stray bits`() {
        assertEquals("-_8", base64url(byteArrayOf(0xfb.toByte(), 0xff.toByte())))
        assertNull(fromBase64url("-_8="))
        assertNull(fromBase64url("ab+c"))
        assertNull(fromBase64url("abcde"))
        assertNull(fromBase64url("-_9"))
    }

    @Test
    fun `write and read each request`() {
        val links = listOf(
            Link.Pay(net, account, daler, "12.5", "Rent, March"),
            Link.Pay(net, account),
            Link.Create(net, byteArrayOf(1, 2, 3, 250.toByte()), "Anna Ö", daler, "50"),
            Link.Device(net, account, Scheme.Secp256k1, pub),
            Link.Recover(net, account, byteArrayOf(9, 8, 7)),
            Link.Sign("https://forum.example/request/1?x=1&y=2"),
        )
        for (link in links) {
            val text = Links.encode(link)
            assertTrue(text.startsWith("ting:${link.request}?"))
            assertEquals(show(link), show(Links.parse(text)))
        }
        assertEquals("ting:pay?to=$account&amount=5&net=$net", Links.encode(Link.Pay(net, account, amount = "5")))
    }

    @Test
    fun `read the scheme, request and fields without regard to case, after an optional slashes`() {
        val link = Links.parse("TING://Pay?TO=${account.uppercase()}&Net=15D6549C&extra=1")
        assertEquals(show(Link.Pay(net, account)), show(link))
    }

    @Test
    fun `refuse what is not a link, or not one this version knows`() {
        assertThrows(LinkError::class.java) { Links.parse(account) }
        refuses("ting:swap?net=15d6549c", "does not know")
        refuses("ting:pay?to=$account", "no net")
        refuses("ting:pay?to=$account&net=xyz", "8 hex digits")
        refuses("ting:pay?to=12&net=$net", "64 hex digits")
        refuses("ting:pay?to=$account&amount=1,5&net=$net", "amount")
        refuses("ting:pay?to=$account&amount=١&net=$net", "amount")
        refuses("ting:pay?to=$account&to=$account&net=$net", "twice")
        refuses("ting:pay?to=$account&note=${"x".repeat(81)}&net=$net", "longer")
        refuses("ting:pay?to=$account&note=%E0%A4%A&net=$net", "not written right")
        refuses("ting:create?input=a=b&net=$net", "input")
        refuses("ting:device?id=$account&scheme=rsa&key=${pub.hex()}&net=$net", "scheme")
        // 65 bytes, but not a point on the curve.
        refuses("ting:device?id=$account&scheme=secp256k1&key=04${"00".repeat(64)}&net=$net", "public key")
    }

    @Test
    fun `are taken only as the request asked for`() {
        val e = assertThrows(LinkError::class.java) { Links.parseOf<Link.Device>(Links.encode(Link.Recover(net, account, byteArrayOf(1))), "device") }
        assertTrue(e.message!!.contains("recover link, not a device link"))
    }

    @Test
    fun `give the account a pay link pays, or a bare account`() {
        assertEquals(account, Links.accountIn(" ${account.uppercase()} "))
        assertEquals(account, Links.accountIn(Links.encode(Link.Pay(net, account))))
        assertNull(Links.accountIn(Links.encode(Link.Recover(net, account, byteArrayOf(1)))))
        assertNull(Links.accountIn("hello"))
    }

    @Test
    fun `name a network by the first 8 hex digits of its chain ID`() {
        assertEquals(net, Links.netOf(byteArrayOf(0x15, 0xd6.toByte(), 0x54, 0x9c.toByte(), 0xfa.toByte(), 0xcd.toByte())))
    }

    @Test
    fun `refuse text that could hide or reorder what it says`() {
        fun named(name: String) = "ting:create?input=AQID&name=${Links.encodeComponent(name)}&net=$net"
        assertEquals("Anna", (Links.parse(named("  Anna  ")) as Link.Create).name)
        for (bad in listOf("Mom\nDad", "Mo​m", "‮Mom", "Mom⁦", "a\u0000b")) refuses(named(bad), "hide or reorder")
    }

    @Test
    fun `percent-encode as encodeURIComponent does`() {
        assertEquals("a%20b%2Fc%3F%26%3D%C3%B6~*'()!", Links.encodeComponent("a b/c?&=ö~*'()!"))
        assertEquals("a b/ö", Links.decodeComponent("a%20b%2F%C3%B6"))
        assertNull(Links.decodeComponent("%"))
        assertNull(Links.decodeComponent("%zz"))
        assertNull(Links.decodeComponent("%C3"))
    }
}
