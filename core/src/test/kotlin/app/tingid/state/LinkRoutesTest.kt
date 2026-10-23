// Where a Ting link leads: every link from inside the app, only pay, create
// and sign from outside it.
package app.tingid.state

import app.tingid.chain.Link
import app.tingid.chain.LinkError
import app.tingid.chain.Links
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkRoutesTest {
    private val net = "15d6549c"
    private val id = "cd".repeat(32)
    private val pay = Links.encode(Link.Pay(net, id))
    private val create = Links.encode(Link.Create(net, byteArrayOf(1, 2, 3)))
    private val sign = "ting:sign?url=https%3A%2F%2Fforum.example%2Fr%2F1"
    private val device = "ting:device?id=$id&scheme=secp256k1&key=0479be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798483ada7726a3c4655da4fbfc0e1108a8fd17b448a68554199c47d08ffb10d4b8&net=$net"
    private val recover = Links.encode(Link.Recover(net, id, byteArrayOf(1)))

    @Test
    fun `a link opened in the app opens its own screen, with the link`() {
        val inside = LinkSource.Inside
        assertTrue(LinkRoutes.routeOf(pay, inside).let { it is LinkTarget.Send && it.code == pay })
        assertTrue(LinkRoutes.routeOf(create, inside) is LinkTarget.Sponsor)
        assertTrue(LinkRoutes.routeOf(sign, inside) is LinkTarget.Sign)
        assertTrue(LinkRoutes.routeOf(device, inside) is LinkTarget.AddDevice)
        assertTrue(LinkRoutes.routeOf(recover, inside) is LinkTarget.HelpRecover)
        assertEquals(pay, LinkRoutes.routeOf("  $pay \n", inside).code)
    }

    @Test
    fun `a link opened in the app says what is wrong with anything else`() {
        assertThrows(LinkError::class.java) { LinkRoutes.routeOf("hello", LinkSource.Inside) }
        assertThrows(LinkError::class.java) { LinkRoutes.routeOf("ting:swap?net=15d6549c", LinkSource.Inside) }
    }

    @Test
    fun `a link opened from outside opens pay, create and sign, and nothing else`() {
        assertTrue(LinkRoutes.fromOutside(pay) is LinkTarget.Send)
        assertTrue(LinkRoutes.fromOutside(create) is LinkTarget.Sponsor)
        assertTrue(LinkRoutes.fromOutside(sign) is LinkTarget.Sign)
        assertNull(LinkRoutes.fromOutside(device))
        assertNull(LinkRoutes.fromOutside(recover))
        assertNull(LinkRoutes.fromOutside("https://example.com"))
    }
}
