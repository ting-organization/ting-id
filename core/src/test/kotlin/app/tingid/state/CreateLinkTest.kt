// Reading someone else's create link before setting up their identity.
package app.tingid.state

import app.tingid.chain.Id
import app.tingid.chain.Link
import app.tingid.chain.Links
import app.tingid.chain.unhex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CreateLinkTest {
    private val chain = unhex("15d6549cfacd816f835c632b4c67308d1c63e642f4cbf4049368430670705107")
    private val net = Links.netOf(chain)
    private val key = Id.Key(ByteArray(32) { 1 }, ByteArray(65) { 4 })
    private fun settings(changeDelay: Long = Id.DEFAULT_CHANGE_DELAY, recoveryDelay: Long = Id.DEFAULT_RECOVERY_DELAY) =
        Id.Settings(listOf(key), emptyList(), 0, changeDelay, recoveryDelay, Id.DEFAULT_SLOTS, ByteArray(32) { 7 })
    private fun link(input: ByteArray, net: String = this.net) = Links.encode(Link.Create(net, input, "Anna", amount = "5"))

    private fun problem(r: CreateLinkReading): String = (r as CreateLinkReading.Problem).message

    @Test
    fun `a link for this network gives its settings, and the ID they make`() {
        val creation = Id.encodeCreation(settings())
        val r = readCreateLink(link(creation), chain)
        assertTrue(r is CreateLinkReading.Ok)
        r as CreateLinkReading.Ok
        assertArrayEquals(Id.idOf(creation), r.id)
        assertEquals(settings(), r.settings)
        assertEquals("Anna", r.link.name)
        assertEquals("5", r.link.amount)
    }

    @Test
    fun `with no chain known yet, the network is not checked`() {
        assertTrue(readCreateLink(link(Id.encodeCreation(settings()), net = "00000000"), null) is CreateLinkReading.Ok)
    }

    @Test
    fun `a link for another network is refused`() {
        assertEquals("this link is for another Ting network", problem(readCreateLink(link(Id.encodeCreation(settings()), net = "00000000"), chain)))
    }

    @Test
    fun `a creation this app cannot read is refused`() {
        assertEquals("the link does not hold an identity this app can read", problem(readCreateLink(link(byteArrayOf(1, 2, 3)), chain)))
    }

    @Test
    fun `settings the registry would refuse are refused`() {
        // Recovery must wait less than changes do, so guardians can undo what a thief starts.
        val creation = Id.encodeCreation(settings(changeDelay = 100, recoveryDelay = 100))
        assertEquals("the registry would refuse this identity: recovery delay must be below the change delay", problem(readCreateLink(link(creation), chain)))
    }

    @Test
    fun `another kind of link, or none, says what it is`() {
        val pay = Links.encode(Link.Pay(net, "cd".repeat(32)))
        assertEquals("this link is a pay link, not a create link", problem(readCreateLink(pay, chain)))
        assertTrue(problem(readCreateLink("", chain)).isNotEmpty())
    }
}
