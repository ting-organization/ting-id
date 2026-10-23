// What the identity screens read from a pasted link or a typed threshold:
// the key or keys it gives, and why one for another network or identity is
// refused.
package app.tingid.state

import app.tingid.chain.Contracts
import app.tingid.chain.Id
import app.tingid.chain.Link
import app.tingid.chain.Links
import app.tingid.chain.Scheme
import app.tingid.chain.filled
import app.tingid.chain.publicKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityChangesTest {
    private val net = "15d6549c"
    private val id = "cd".repeat(32)
    private val pub = publicKey(filled(7))
    private val c = Contracts.canonical(filled(0x26))

    private fun refused(check: Checked<*>?): String = (check as Checked.Refused).message

    @Test
    fun `a device link for this identity gives its key`() {
        val link = Links.encode(Link.Device(net, id, Scheme.Secp256k1, pub))
        val found = deviceLinkKey("  $link\n", net, id, "Anna") as Checked.Ok
        assertEquals(Scheme.Secp256k1, found.value.scheme)
        assertArrayEquals(pub, found.value.pub)
        // The identity as the store keeps it, in any case.
        assertTrue(deviceLinkKey(link, net, id.uppercase(), "Anna") is Checked.Ok)
    }

    @Test
    fun `a device link for another network or identity is refused, and nothing typed is nothing`() {
        assertNull(deviceLinkKey(" \n", net, id, "Anna"))
        val other = Links.encode(Link.Device(net, "ab".repeat(32), Scheme.Secp256k1, pub))
        assertEquals("this device link is for another identity, not Anna", refused(deviceLinkKey(other, net, id, "Anna")))
        assertEquals("this device link is for another identity, not this one", refused(deviceLinkKey(other, net, id, null)))
        val elsewhere = Links.encode(Link.Device("00000000", id, Scheme.Secp256k1, pub))
        assertEquals("this link is for another Ting network", refused(deviceLinkKey(elsewhere, net, id, "Anna")))
        // Another kind of link, or none, says so.
        assertEquals("this link is a recover link, not a device link", refused(deviceLinkKey(Links.encode(Link.Recover(net, id, byteArrayOf(1))), net, id, "Anna")))
        assertEquals("this is not a Ting link", refused(deviceLinkKey("hello", net, id, "Anna")))
    }

    @Test
    fun `a recovery link gives the identity and the key list, when the list can be read`() {
        val keys = Id.encodeKeyList(listOf(Id.Key(c.policySecp256k1, pub)))
        val found = recoverLink(Links.encode(Link.Recover(net, id, keys)), net) as Checked.Ok
        assertEquals(id, found.value.id)
        assertArrayEquals(keys, found.value.keys)
        assertNull(recoverLink("", net))
        assertEquals("the link lists no keys this app can read", refused(recoverLink(Links.encode(Link.Recover(net, id, byteArrayOf(9, 9))), net)))
        assertEquals("this link is for another Ting network", refused(recoverLink(Links.encode(Link.Recover("00000000", id, keys)), net)))
        // Not known yet: the network is not checked.
        assertTrue(recoverLink(Links.encode(Link.Recover("00000000", id, keys)), null) is Checked.Ok)
    }

    @Test
    fun `a threshold is a number no larger than the guardians`() {
        assertEquals(2L, (thresholdOf("2", 3) as Checked.Ok).value)
        assertEquals(0L, (thresholdOf("0", 0) as Checked.Ok).value)
        assertEquals("enter a number", refused(thresholdOf("", 3)))
        assertEquals("enter a number", refused(thresholdOf("-1", 3)))
        assertEquals("enter a number", refused(thresholdOf("٣", 3)))
        assertEquals("at most 3: the identity has 3 guardians", refused(thresholdOf("4", 3)))
        assertEquals("at most 1: the identity has 1 guardian", refused(thresholdOf("99999999999999999999", 1)))
    }

    @Test
    fun `a key's kind in words, from its policy`() {
        assertEquals("secp256k1", policyLabel(c, c.policySecp256k1))
        assertEquals("P-256", policyLabel(c, c.policyP256))
        assertEquals("multisig", policyLabel(c, c.multisig))
        assertEquals("another identity", policyLabel(c, c.registry))
        assertEquals("custom policy", policyLabel(c, filled(3)))
        assertEquals("custom policy", policyLabel(null, c.policySecp256k1))
    }
}
