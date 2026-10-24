// Names for people: a contact names the identity it is for, a guardian goes by
// the contact that has its Spending account, and naming an identity or
// renaming a contact keeps one contact per account.
package app.tingid.state

import app.tingid.chain.Contracts
import app.tingid.chain.Id
import app.tingid.chain.hex
import app.tingid.chain.unhex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NamesTest {
    private val chain = "15d6549cfacd816f835c632b4c67308d1c63e642f4cbf4049368430670705107"
    private val c = Contracts.canonical(unhex(chain))
    private val guardian = "de".repeat(32)
    private val spending = Id.account(c.registry, unhex(guardian), 0).hex()

    private fun user(name: String, guardians: List<String> = emptyList()) = User(
        id = name,
        name = name,
        color = USER_COLORS[0],
        kind = UserKind.Identity,
        identityId = "aa".repeat(32),
        registry = c.registry.hex(),
        knownGuardians = guardians,
    )

    private fun store() = Store(MemoryStorage(), listOf("https://default"), currentChain = { chain }).also { it.load() }

    @Test
    fun `a contact for an identity names the identity too`() {
        val names = names(emptyList(), emptyList(), listOf(Contact("c1", "Erik", spending, identity = guardian)), c)
        assertEquals("Erik", names[guardian])
        assertEquals("Erik", names[spending])
    }

    @Test
    fun `a guardian goes by the contact that has its Spending account`() {
        val names = names(listOf(user("Maja", listOf(guardian))), emptyList(), listOf(Contact("c1", "Erik", spending)), c)
        assertEquals("Erik", names[guardian])
    }

    @Test
    fun `a guardian no contact has stays unnamed`() {
        val names = names(listOf(user("Maja", listOf(guardian))), emptyList(), listOf(Contact("c1", "Erik", "ab".repeat(32))), c)
        assertNull(names[guardian])
    }

    @Test
    fun `naming an identity saves its Spending account under the name`() {
        val store = store()
        store.nameIdentity("Erik", guardian.uppercase(), c.registry)
        val saved = store.data.value.contacts.single()
        assertEquals(listOf("Erik", spending, guardian, chain), listOf(saved.name, saved.account, saved.identity, saved.chain))
    }

    @Test
    fun `naming an identity paid before renames that contact instead of adding one`() {
        val store = store()
        store.addContact(Contact("c1", "E.", spending))
        store.nameIdentity("Erik Holm", guardian, c.registry)
        val saved = store.data.value.contacts.single()
        assertEquals(listOf("c1", "Erik Holm", guardian), listOf(saved.id, saved.name, saved.identity))
    }

    @Test
    fun `renaming a contact keeps its account`() {
        val store = store()
        store.addContact(Contact("c1", "Kari", "ab".repeat(32)))
        store.addContact(Contact("c2", "Ola", "cd".repeat(32)))
        store.renameContact("c1", "Kari Nordmann")
        assertEquals(listOf("Kari Nordmann" to "ab".repeat(32), "Ola" to "cd".repeat(32)), store.data.value.contacts.map { it.name to it.account })
    }
}
