// Whether a change can apply, as the registry decides: the change that waits
// forever to be applied, and the changes that would fail behind the ones
// already waiting.
package app.tingid.state

import app.tingid.chain.Id
import app.tingid.chain.hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangeCheckTest {
    private val me = ByteArray(32) { 1 }
    private val anna = ByteArray(32) { 2 }
    private val bo = ByteArray(32) { 3 }

    /** An identity with keys 0 and 1, the guardians in `linked` of those in `known`, and `threshold`. */
    private fun state(linked: List<ByteArray>, known: List<ByteArray> = listOf(anna, bo), threshold: Long = 0) = ChangeState(
        me.hex(),
        Id.Core(3, 0, 0, 0, 2, 2, linked.size.toLong(), 2, threshold, 604_800, 172_800, 0),
        setOf(0L, 1L),
        known.associate { g -> g.hex() to linked.any { it.contentEquals(g) } },
    )

    private fun change(no: Long, vararg ops: Id.Operation) = PendingChange(no, Id.ChangeRecord(0, 0, 1_000, 2_000, ops.toList()))

    private fun words(b: ProposeBlocked?) = b?.let { blockedWords(it.why, it.waiting, advanced = false) { g -> if (g.contentEquals(anna)) "Anna" else "Bo" } }

    @Test
    fun `linking a guardian who is linked already can never apply`() {
        // As on the production chain: a change linking a guardian the identity was made with.
        val s = state(linked = listOf(anna))
        val dead = change(1, Id.Operation.AddGuardian(anna))
        assertTrue(blockedNow(s, dead.record.operations) is Blocked.GuardianLinked)
        val b = blockedApply(s, listOf(dead), dead)!!
        assertTrue(b.forGood)
        assertEquals("Anna is your guardian already", blockedWords(b.why, waiting = false, advanced = false) { "Anna" })
        // Proposing it is refused for the same reason.
        assertEquals("Anna is your guardian already", words(blockedBehind(s, emptyList(), dead.record.operations)))
        assertEquals("an identity cannot be its own guardian", words(blockedBehind(s, emptyList(), listOf(Id.Operation.AddGuardian(me)))))
    }

    @Test
    fun `a second change of the same kind would fail behind the first`() {
        val s = state(linked = listOf(anna))
        assertEquals("a change linking Bo is already waiting", words(blockedBehind(s, listOf(change(1, Id.Operation.AddGuardian(bo))), listOf(Id.Operation.AddGuardian(bo)))))
        assertEquals("a change unlinking Anna is already waiting", words(blockedBehind(s, listOf(change(1, Id.Operation.RemoveGuardian(anna))), listOf(Id.Operation.RemoveGuardian(anna)))))
        assertEquals("a change removing that device is already waiting", words(blockedBehind(s, listOf(change(1, Id.Operation.RemoveKey(1))), listOf(Id.Operation.RemoveKey(1)))))
        // Removing both devices in two changes leaves none.
        assertEquals("with the changes already waiting, the identity would have no device left", words(blockedBehind(s, listOf(change(1, Id.Operation.RemoveKey(1))), listOf(Id.Operation.RemoveKey(0)))))
        // With no change waiting, each one alone is fine.
        assertNull(blockedBehind(s, emptyList(), listOf(Id.Operation.RemoveKey(0))))
        assertNull(blockedBehind(s, emptyList(), listOf(Id.Operation.AddGuardian(bo))))
    }

    @Test
    fun `a threshold the guardians left could not reach is refused`() {
        val two = state(linked = listOf(anna, bo))
        // A waiting threshold of 2, then one of two guardians unlinked.
        val behind = blockedBehind(two, listOf(change(1, Id.Operation.SetThreshold(2))), listOf(Id.Operation.RemoveGuardian(bo)))
        assertTrue(behind!!.waiting)
        assertEquals("with the changes already waiting, a recovery would need 2 approvals, with 1 guardian to give them", words(behind))
        // And the other way round: a guardian unlinked, then a threshold of 2.
        assertTrue(blockedBehind(two, listOf(change(1, Id.Operation.RemoveGuardian(bo))), listOf(Id.Operation.SetThreshold(2)))?.why is Blocked.ThresholdAbove)
        assertNull(blockedBehind(two, emptyList(), listOf(Id.Operation.SetThreshold(2))))
        // Delays: a recovery must wait less than a change.
        assertTrue(blockedNow(two, listOf(Id.Operation.SetDelays(172_800, 604_800))) is Blocked.DelaysOrder)
    }

    @Test
    fun `a change that fails is left out of what the others are checked behind`() {
        val s = state(linked = listOf(anna))
        // The dead change linking Anna again leaves her linked: unlinking her can still apply.
        assertNull(blockedBehind(s, listOf(change(1, Id.Operation.AddGuardian(anna))), listOf(Id.Operation.RemoveGuardian(anna))))
    }

    @Test
    fun `a change that can apply once another has is not dead`() {
        val s = state(linked = listOf(anna))
        val unlink = change(1, Id.Operation.RemoveGuardian(anna))
        val relink = change(2, Id.Operation.AddGuardian(anna))
        val b = blockedApply(s, listOf(unlink, relink), relink)!!
        assertFalse(b.forGood)
        assertNull(blockedApply(s, listOf(unlink, relink), unlink))
        // An operation after another in one change sees what that one did.
        assertNull(blockedNow(s, listOf(Id.Operation.RemoveGuardian(anna), Id.Operation.AddGuardian(anna))))
    }

    @Test
    fun `accounts are added one after another`() {
        val s = state(linked = emptyList())
        assertNull(blockedNow(s, listOf(Id.Operation.SetSlot(2, 0))))
        assertTrue(blockedNow(s, listOf(Id.Operation.SetSlot(3, 0))) is Blocked.SlotMissing)
        assertNull(blockedNow(s, listOf(Id.Operation.SetSlot(2, 0), Id.Operation.SetSlot(3, 0))))
    }

    @Test
    fun `a guardian not read is not checked`() {
        val s = state(linked = emptyList(), known = emptyList())
        assertNull(blockedNow(s, listOf(Id.Operation.AddGuardian(anna))))
        assertNull(blockedNow(s, listOf(Id.Operation.RemoveGuardian(anna))))
        assertEquals(listOf(anna.hex(), bo.hex()), guardiansNamed(listOf(Id.Operation.AddGuardian(anna), Id.Operation.RemoveGuardian(bo), Id.Operation.RemoveGuardian(anna))).map { it.hex() })
    }
}
