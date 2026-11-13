// Whether a change to an identity can apply, as the registry decides when it
// is applied: each operation against the identity as the ones before it left
// it, and the identity whole after them all (a key left, a threshold its
// guardians can reach, a recovery shorter than a change). A change that
// cannot apply never will by itself, so Apply would wait forever: it only
// waits to be cancelled. A new change is also checked behind the changes
// already waiting, as if they apply first, so that it is not proposed to fail
// once they do.
package app.tingid.state

import app.tingid.chain.ChainApi
import app.tingid.chain.Id
import app.tingid.chain.equal
import app.tingid.chain.hex

/**
 * What decides whether a change applies to the identity `id`: its core, the
 * ids of its keys, and which guardians are linked, by hex ID. A guardian
 * missing from `guardians` was not read, and is not checked.
 */
data class ChangeState(val id: String, val core: Id.Core, val keyIds: Set<Long>, val guardians: Map<String, Boolean>)

/** Why a change cannot apply. */
sealed class Blocked {
    /** It names the identity as its own guardian. */
    data object OwnIdentity : Blocked()
    class GuardianLinked(val id: ByteArray) : Blocked()
    class GuardianNotLinked(val id: ByteArray) : Blocked()
    class KeyGone(val keyId: Long) : Blocked()
    class SlotMissing(val slot: Long) : Blocked()
    data object NoKeys : Blocked()
    class ThresholdAbove(val threshold: Long, val guardians: Long) : Blocked()
    data object DelaysOrder : Blocked()
}

/** `state` as a change leaves it, or why the change cannot apply to it. */
sealed class Applied {
    class Yes(val state: ChangeState) : Applied()
    class No(val why: Blocked) : Applied()
}

/** `state` after `ops`, applied as the registry would. */
fun afterChange(state: ChangeState, ops: List<Id.Operation>): Applied {
    var core = state.core
    val keyIds = state.keyIds.toMutableSet()
    val guardians = state.guardians.toMutableMap()
    for (op in ops) {
        when (op) {
            is Id.Operation.AddKey -> {
                keyIds.add(core.nextKeyId)
                core = core.copy(nextKeyId = core.nextKeyId + 1, keyCount = core.keyCount + 1)
            }
            is Id.Operation.RemoveKey -> {
                if (!keyIds.remove(op.keyId)) return Applied.No(Blocked.KeyGone(op.keyId))
                core = core.copy(keyCount = core.keyCount - 1)
            }
            is Id.Operation.AddGuardian -> {
                val g = op.id.hex()
                if (g == state.id) return Applied.No(Blocked.OwnIdentity)
                if (guardians[g] == true) return Applied.No(Blocked.GuardianLinked(op.id))
                guardians[g] = true
                core = core.copy(guardianCount = core.guardianCount + 1)
            }
            is Id.Operation.RemoveGuardian -> {
                val g = op.id.hex()
                if (guardians[g] == false) return Applied.No(Blocked.GuardianNotLinked(op.id))
                guardians[g] = false
                core = core.copy(guardianCount = maxOf(0, core.guardianCount - 1))
            }
            is Id.Operation.SetThreshold -> core = core.copy(threshold = op.threshold)
            is Id.Operation.SetDelays -> core = core.copy(changeDelay = op.changeDelay, recoveryDelay = op.recoveryDelay)
            is Id.Operation.SetSlot -> {
                if (op.slot > core.slotCount) return Applied.No(Blocked.SlotMissing(op.slot))
                if (op.slot == core.slotCount) core = core.copy(slotCount = core.slotCount + 1)
            }
        }
    }
    return when {
        core.keyCount < 1 -> Applied.No(Blocked.NoKeys)
        core.threshold != 0L && core.threshold > core.guardianCount -> Applied.No(Blocked.ThresholdAbove(core.threshold, core.guardianCount))
        core.recoveryDelay >= core.changeDelay -> Applied.No(Blocked.DelaysOrder)
        else -> Applied.Yes(ChangeState(state.id, core, keyIds, guardians))
    }
}

/** Why `ops` cannot apply to the identity as `state` has it now; null when they can. */
fun blockedNow(state: ChangeState, ops: List<Id.Operation>): Blocked? = (afterChange(state, ops) as? Applied.No)?.why

/** Why a pending change cannot apply now, and whether it never will (`forGood`). */
class ApplyBlocked(val why: Blocked, val forGood: Boolean)

/**
 * Why pending `change` cannot apply to the identity as `state` has it, or
 * null when it can. It can apply later (not `forGood`) when it could after
 * the other `pending` changes, each one that can, apply in their order: such
 * as a guardian linked again after a change unlinking them.
 */
fun blockedApply(state: ChangeState, pending: List<PendingChange>, change: PendingChange): ApplyBlocked? {
    val why = blockedNow(state, change.record.operations) ?: return null
    var after = state
    for (other in pending.sortedBy { it.changeNo }) {
        if (other.changeNo == change.changeNo) continue
        (afterChange(after, other.record.operations) as? Applied.Yes)?.let { after = it.state }
    }
    return ApplyBlocked(why, forGood = blockedNow(after, change.record.operations) != null)
}

/** Why a new change cannot be proposed: it cannot apply now, or not once the changes already waiting do (`waiting`). */
class ProposeBlocked(val why: Blocked, val waiting: Boolean)

/**
 * Why `ops` should not be proposed to the identity `state` has: they cannot
 * apply to it as it is, or not after the `pending` changes, applied in their
 * order, each one that can. Null when they can.
 */
fun blockedBehind(state: ChangeState, pending: List<PendingChange>, ops: List<Id.Operation>): ProposeBlocked? {
    blockedNow(state, ops)?.let { return ProposeBlocked(it, waiting = false) }
    var after = state
    for (change in pending.sortedBy { it.changeNo }) {
        (afterChange(after, change.record.operations) as? Applied.Yes)?.let { after = it.state }
    }
    return blockedNow(after, ops)?.let { ProposeBlocked(it, waiting = true) }
}

/** The guardians `ops` name, once each. */
fun guardiansNamed(ops: List<Id.Operation>): List<ByteArray> = ops.mapNotNull {
    when (it) {
        is Id.Operation.AddGuardian -> it.id
        is Id.Operation.RemoveGuardian -> it.id
        else -> null
    }
}.distinctBy { it.hex() }

/** The guardians whose links decide `identity`'s pending changes and `ops`. */
fun guardiansToRead(identity: IdentityInfo, ops: List<Id.Operation> = emptyList()): List<ByteArray> =
    guardiansNamed(identity.pending.flatMap { it.record.operations } + ops)

/** The state `identity`'s changes are checked against: of the guardians in `named`, those in `linked` guard it. */
fun changeStateOf(identity: IdentityInfo, named: List<ByteArray>, linked: List<ByteArray>): ChangeState = ChangeState(
    identity.id.hex(),
    identity.core,
    identity.keys.map { it.keyId }.toSet(),
    named.associate { g -> g.hex() to linked.any { equal(it, g) } },
)

/** The state `identity`'s changes and `ops` are checked against, the guardians they name read from `node`. */
suspend fun readChangeState(node: ChainApi, identity: IdentityInfo, ops: List<Id.Operation> = emptyList()): ChangeState {
    val named = guardiansToRead(identity, ops)
    return changeStateOf(identity, named, linkedGuardians(node, identity.registry, identity.id, named))
}

/**
 * Why a change cannot apply, in words: `name` names a guardian, `advanced`
 * says key where a person would say device. `waiting`: the reason is the
 * changes already waiting, for a change being proposed.
 */
fun blockedWords(why: Blocked, waiting: Boolean, advanced: Boolean, name: (ByteArray) -> String): String {
    val device = if (advanced) "key" else "device"
    val behind = if (waiting) "with the changes already waiting, " else ""
    return when (why) {
        Blocked.OwnIdentity -> "an identity cannot be its own guardian"
        is Blocked.GuardianLinked -> if (waiting) "a change linking ${name(why.id)} is already waiting" else "${name(why.id)} is your guardian already"
        is Blocked.GuardianNotLinked -> if (waiting) "a change unlinking ${name(why.id)} is already waiting" else "${name(why.id)} is not your guardian"
        is Blocked.KeyGone -> if (waiting) "a change removing that $device is already waiting" else "that $device is no longer on the identity"
        is Blocked.SlotMissing -> "${behind}that account does not exist"
        Blocked.NoKeys -> "${behind}the identity would have no $device left"
        is Blocked.ThresholdAbove -> "${behind}a recovery would need ${why.threshold} approval${if (why.threshold == 1L) "" else "s"}, with ${why.guardians} guardian${if (why.guardians == 1L) "" else "s"} to give them"
        Blocked.DelaysOrder -> "${behind}a recovery would no longer wait less than a change"
    }
}
