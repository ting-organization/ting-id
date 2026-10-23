// The identity registry reference program (wiki: programs/identity):
// identifiers, records, operations, messages and events.
@file:UseSerializers(HexSerializer::class, BigIntegerSerializer::class)

package app.tingid.chain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

object Id {
    const val APP_TAG = "app/id-v1"
    const val VERSION = 1
    const val PARAMS_LEN = 40

    const val OP_CREATE = 1
    const val OP_PROPOSE = 2
    const val OP_CANCEL = 3
    const val OP_APPLY = 4
    const val OP_APPROVE = 5
    const val OP_WITHDRAW = 6
    const val OP_RECOVER = 7
    const val OP_INFO = 8

    private const val KIND_CORE = 0
    private const val KIND_KEY = 1
    private const val KIND_GUARDIAN = 2
    private const val KIND_SLOT = 3
    private const val KIND_CHANGE = 4
    private const val KIND_PROPOSAL = 5
    private const val KIND_APPROVAL = 6
    private const val KIND_CURRENT_APPROVAL = 7

    const val APPROVED = 1
    const val WITHDRAWN = 2

    const val DEFAULT_CHANGE_DELAY = 604_800L
    const val DEFAULT_RECOVERY_DELAY = 172_800L
    val DEFAULT_SLOTS = listOf(0L, 604_800L)

    val CREATED_TOPIC: ByteArray = taggedHash("app/id-v1/event/created")
    val CHANGE_PROPOSED_TOPIC: ByteArray = taggedHash("app/id-v1/event/change-proposed")
    val CHANGE_CANCELLED_TOPIC: ByteArray = taggedHash("app/id-v1/event/change-cancelled")
    val CHANGE_APPLIED_TOPIC: ByteArray = taggedHash("app/id-v1/event/change-applied")
    val RECOVERY_APPROVED_TOPIC: ByteArray = taggedHash("app/id-v1/event/recovery-approved")
    val RECOVERY_WITHDRAWN_TOPIC: ByteArray = taggedHash("app/id-v1/event/recovery-withdrawn")
    val RECOVERED_TOPIC: ByteArray = taggedHash("app/id-v1/event/recovered")

    // ---- Identifiers and record keys -------------------------------------------

    /** The ID of a creation input (everything after the op byte). */
    fun idOf(creation: ByteArray): ByteArray = taggedHash("app/id-v1/id", creation)

    /** Policy parameters of an ID account: `id ‖ u64 slot`. */
    fun params(id: ByteArray, slot: Long): ByteArray = concat(id, u64le(slot))

    fun account(registry: ByteArray, id: ByteArray, slot: Long): ByteArray = policyAccount(registry, params(id, slot))

    private fun record(kind: Int, id: ByteArray, index: ByteArray): ByteArray =
        taggedHash("app/id-v1/record", byteArrayOf(kind.toByte()), id, index)

    fun coreKey(id: ByteArray) = record(KIND_CORE, id, ByteArray(0))
    fun keyKey(id: ByteArray, epoch: Long, keyId: Long) = record(KIND_KEY, id, concat(u64le(epoch), u64le(keyId)))
    fun guardianKey(id: ByteArray, guardian: ByteArray) = record(KIND_GUARDIAN, id, guardian)
    fun slotKey(id: ByteArray, slot: Long) = record(KIND_SLOT, id, u64le(slot))
    fun changeKey(id: ByteArray, changeNo: Long) = record(KIND_CHANGE, id, u64le(changeNo))
    fun proposalKey(id: ByteArray, proposal: ByteArray) = record(KIND_PROPOSAL, id, proposal)
    fun approvalKey(id: ByteArray, proposal: ByteArray, guardian: ByteArray) = record(KIND_APPROVAL, id, concat(proposal, guardian))

    /** The proposal `guardian` approves in a recovery epoch; a guardian approves one at a time. */
    fun currentApprovalKey(id: ByteArray, recoveryEpoch: Long, guardian: ByteArray) =
        record(KIND_CURRENT_APPROVAL, id, concat(u64le(recoveryEpoch), guardian))

    /** A recovery proposal: the new key set, bound to the ID's recovery epoch. */
    fun proposalHash(id: ByteArray, recoveryEpoch: Long, keyList: ByteArray): ByteArray =
        taggedHash("app/id-v1/recovery", id, u64le(recoveryEpoch), keyList)

    // ---- Records ---------------------------------------------------------------

    @Serializable
    data class Core(
        val opNonce: Long,
        val changeEpoch: Long,
        val recoveryEpoch: Long,
        val keyEpoch: Long,
        val nextKeyId: Long,
        val keyCount: Long,
        val guardianCount: Long,
        val slotCount: Long,
        val threshold: Long,
        val changeDelay: Long,
        val recoveryDelay: Long,
        val nextChange: Long,
    ) {
        /** Approvals a recovery needs. */
        val required: Long get() = if (threshold > 0) threshold else guardianCount / 2 + 1
    }

    fun decodeCore(bytes: ByteArray): Core? = tryDecode {
        val r = Reader(bytes)
        if (r.u8() != VERSION) throw DecodeError("version")
        val c = Core(r.u64(), r.u64(), r.u64(), r.u64(), r.u64(), r.u64(), r.u64(), r.u64(), r.u64(), r.u64(), r.u64(), r.u64())
        r.end()
        c
    }

    fun encodeCore(c: Core): ByteArray = Writer()
        .u8(VERSION)
        .u64(c.opNonce)
        .u64(c.changeEpoch)
        .u64(c.recoveryEpoch)
        .u64(c.keyEpoch)
        .u64(c.nextKeyId)
        .u64(c.keyCount)
        .u64(c.guardianCount)
        .u64(c.slotCount)
        .u64(c.threshold)
        .u64(c.changeDelay)
        .u64(c.recoveryDelay)
        .u64(c.nextChange)
        .finish()

    /** One of an identity's keys: a policy and its parameters. */
    @Serializable
    class Key(val policy: ByteArray, val params: ByteArray) : ByContent() {
        override fun toString() = "Key(policy=${policy.hex()}, params=${params.hex()})"
    }

    fun decodeKeyRecord(bytes: ByteArray): Key? =
        if (bytes.size < 32) null else Key(bytes.copyOfRange(0, 32), bytes.copyOfRange(32, bytes.size))

    fun encodeKeyRecord(k: Key): ByteArray = concat(k.policy, k.params)

    /** `u64 count ‖ count × (policy ‖ Bytes(params))`. */
    fun encodeKeyList(keys: List<Key>): ByteArray {
        val w = Writer().u64(keys.size)
        for (k in keys) w.put32(k.policy).bytes(k.params)
        return w.finish()
    }

    fun decodeKeyList(bytes: ByteArray): List<Key>? = tryDecode {
        val r = Reader(bytes)
        val keys = readKeyList(r)
        r.end()
        keys
    }

    private fun readKeyList(r: Reader): List<Key> {
        val count = r.u64()
        // Each key takes at least 36 bytes: a count beyond that is a lie, not a reason to loop.
        if (count > r.remaining / 36) throw DecodeError("truncated")
        return (0 until count).map { Key(r.array32(), r.bytes()) }
    }

    /** What a creation input sets. */
    @Serializable
    class Settings(
        val keys: List<Key>,
        val guardians: List<ByteArray>,
        val threshold: Long,
        val changeDelay: Long,
        val recoveryDelay: Long,
        val slots: List<Long>,
        val salt: ByteArray,
    ) : ByContent() {
        override fun toString() =
            "Settings(keys=$keys, guardians=${guardians.map { it.hex() }}, threshold=$threshold, changeDelay=$changeDelay, " +
                "recoveryDelay=$recoveryDelay, slots=$slots, salt=${salt.hex()})"
    }

    /** Guardians in the order a creation input lists them: strictly increasing bytes. */
    fun sortGuardians(guardians: List<ByteArray>): List<ByteArray> = guardians.sortedWith(BYTE_ORDER)

    /** Creation input, after the op byte. Guardians must already be sorted ([sortGuardians]). */
    fun encodeCreation(s: Settings): ByteArray {
        val w = Writer().u8(VERSION).put(encodeKeyList(s.keys)).u64(s.guardians.size)
        for (g in s.guardians) w.put32(g)
        w.u64(s.threshold).u64(s.changeDelay).u64(s.recoveryDelay).u64(s.slots.size)
        for (d in s.slots) w.u64(d)
        return w.put32(s.salt).finish()
    }

    fun decodeCreation(bytes: ByteArray): Settings? = tryDecode {
        val r = Reader(bytes)
        if (r.u8() != VERSION) throw DecodeError("version")
        val keys = readKeyList(r)
        val g = r.items(32)
        val guardians = (0 until g.size / 32).map { g.copyOfRange(it * 32, it * 32 + 32) }
        val threshold = r.u64()
        val changeDelay = r.u64()
        val recoveryDelay = r.u64()
        val sl = r.items(8)
        val slots = (0 until sl.size / 8).map { Reader(sl.copyOfRange(it * 8, it * 8 + 8)).u64() }
        val salt = r.array32()
        r.end()
        Settings(keys, guardians, threshold, changeDelay, recoveryDelay, slots, salt)
    }

    /** The registry's configuration rules: guardians in strictly increasing byte order (so none twice), never the id itself. */
    fun validSettings(s: Settings, id: ByteArray): String? {
        if (s.keys.isEmpty()) return "at least one key"
        if (s.slots.isEmpty()) return "at least one slot"
        if (s.threshold != 0L && s.threshold > s.guardians.size) return "threshold above the guardian count"
        if (s.recoveryDelay >= s.changeDelay) return "recovery delay must be below the change delay"
        for (i in s.guardians.indices) {
            if (equal(s.guardians[i], id)) return "an identity cannot guard itself"
            if (i > 0) {
                val order = compare(s.guardians[i - 1], s.guardians[i])
                if (order == 0) return "guardians must be distinct"
                if (order > 0) return "guardians must be sorted"
            }
        }
        return null
    }

    // ---- Changes -----------------------------------------------------------------

    @Serializable
    sealed class Operation : ByContent() {
        @Serializable @SerialName("addKey")
        class AddKey(val key: Key) : Operation()

        @Serializable @SerialName("removeKey")
        class RemoveKey(val keyId: Long) : Operation()

        @Serializable @SerialName("addGuardian")
        class AddGuardian(val id: ByteArray) : Operation()

        @Serializable @SerialName("removeGuardian")
        class RemoveGuardian(val id: ByteArray) : Operation()

        @Serializable @SerialName("setThreshold")
        class SetThreshold(val threshold: Long) : Operation()

        @Serializable @SerialName("setDelays")
        class SetDelays(val changeDelay: Long, val recoveryDelay: Long) : Operation()

        @Serializable @SerialName("setSlot")
        class SetSlot(val slot: Long, val delay: Long) : Operation()

        override fun toString(): String = when (this) {
            is AddKey -> "AddKey($key)"
            is RemoveKey -> "RemoveKey($keyId)"
            is AddGuardian -> "AddGuardian(${id.hex()})"
            is RemoveGuardian -> "RemoveGuardian(${id.hex()})"
            is SetThreshold -> "SetThreshold($threshold)"
            is SetDelays -> "SetDelays($changeDelay, $recoveryDelay)"
            is SetSlot -> "SetSlot($slot, $delay)"
        }
    }

    private fun encodeOperation(w: Writer, op: Operation) {
        when (op) {
            is Operation.AddKey -> w.u8(1).put32(op.key.policy).bytes(op.key.params)
            is Operation.RemoveKey -> w.u8(2).u64(op.keyId)
            is Operation.AddGuardian -> w.u8(3).put32(op.id)
            is Operation.RemoveGuardian -> w.u8(4).put32(op.id)
            is Operation.SetThreshold -> w.u8(5).u64(op.threshold)
            is Operation.SetDelays -> w.u8(6).u64(op.changeDelay).u64(op.recoveryDelay)
            is Operation.SetSlot -> w.u8(7).u64(op.slot).u64(op.delay)
        }
    }

    private fun readOperation(r: Reader): Operation = when (r.u8()) {
        1 -> Operation.AddKey(Key(r.array32(), r.bytes()))
        2 -> Operation.RemoveKey(r.u64())
        3 -> Operation.AddGuardian(r.array32())
        4 -> Operation.RemoveGuardian(r.array32())
        5 -> Operation.SetThreshold(r.u64())
        6 -> Operation.SetDelays(r.u64(), r.u64())
        7 -> Operation.SetSlot(r.u64(), r.u64())
        else -> throw DecodeError("unknown operation")
    }

    /** `u64 count ‖ count × operation`. */
    fun encodeChange(ops: List<Operation>): ByteArray {
        val w = Writer().u64(ops.size)
        for (op in ops) encodeOperation(w, op)
        return w.finish()
    }

    private fun readChange(r: Reader): List<Operation> {
        val count = r.u64()
        // Each operation takes at least 9 bytes.
        if (count > r.remaining / 9) throw DecodeError("truncated")
        return (0 until count).map { readOperation(r) }
    }

    fun decodeChange(bytes: ByteArray): List<Operation>? = tryDecode {
        val r = Reader(bytes)
        val ops = readChange(r)
        r.end()
        ops
    }

    /** Change record: `u64 change_epoch ‖ u64 proposer ‖ u64 proposed_at ‖ u64 effective_at ‖ change`. */
    @Serializable
    data class ChangeRecord(
        val changeEpoch: Long,
        /** The key_id of the key that proposed it: the change dies with that key. */
        val proposer: Long,
        /** Block timestamp of the proposal. */
        val proposedAt: Long,
        val effectiveAt: Long,
        val operations: List<Operation>,
    )

    fun decodeChangeRecord(bytes: ByteArray): ChangeRecord? = tryDecode {
        val r = Reader(bytes)
        val rec = ChangeRecord(r.u64(), r.u64(), r.u64(), r.u64(), readChange(r))
        r.end()
        rec
    }

    /**
     * A change is pending while its record exists, it belongs to the current
     * change epoch and the key that proposed it is still one of the ID's keys
     * (`keyIds`: the key ids of the current key epoch).
     */
    fun isPending(rec: ChangeRecord, core: Core, keyIds: List<Long>): Boolean =
        rec.changeEpoch == core.changeEpoch && rec.proposer in keyIds

    @Serializable
    data class Proposal(val approvals: Long, val reachedAt: Long)

    /** A proposal record; a missing one has no approvals. Throws [DecodeError] on a malformed one. */
    fun decodeProposal(bytes: ByteArray?): Proposal {
        if (bytes == null) return Proposal(0, 0)
        val r = Reader(bytes)
        val p = Proposal(r.u64(), r.u64())
        r.end()
        return p
    }

    // ---- Operations and messages -------------------------------------------------

    /** `u64 key_id ‖ Bytes(witness)`: the witness of an ID account, and of registry operations. */
    fun keyAuth(keyId: Long, witness: ByteArray): ByteArray = Writer().u64(keyId).bytes(witness).finish()

    /**
     * The message a key signs for a registry operation on `id`:
     * `Bytes(tag) ‖ chain ‖ registry ‖ id ‖ u64 key_id ‖ body`, where `key_id` is
     * the signing key's id within its own identity (a guardian's key for approvals
     * and withdrawals).
     */
    fun registryMessage(chain: ByteArray, registry: ByteArray, id: ByteArray, keyId: Long, body: ByteArray): ByteArray =
        Writer().bytes(utf8(APP_TAG)).put32(chain).put32(registry).put32(id).u64(keyId).put(body).finish()

    /** Change numbers to cancel: `u64 count ‖ count × u64 change_no`, strictly increasing. */
    fun encodeChangeNumbers(changeNos: List<Long>): ByteArray {
        require(strictlyIncreasing(changeNos)) { "change numbers must be strictly increasing" }
        val w = Writer().u64(changeNos.size)
        for (n in changeNos) w.u64(n)
        return w.finish()
    }

    // Only a proposal carries the op nonce; a cancel, an approval and a withdrawal
    // can each take effect only once by themselves.
    fun proposeBody(opNonce: Long, change: ByteArray) = Writer().u8(OP_PROPOSE).u64(opNonce).put(change).finish()
    fun cancelBody(changeNos: List<Long>) = Writer().u8(OP_CANCEL).put(encodeChangeNumbers(changeNos)).finish()

    /** Body of an approval (`op` = [OP_APPROVE]) or a withdrawal ([OP_WITHDRAW]): `op ‖ proposal ‖ guardian`. */
    fun approvalBody(op: Int, proposal: ByteArray, guardian: ByteArray) = Writer().u8(op).put32(proposal).put32(guardian).finish()

    fun createInput(creation: ByteArray) = Writer().u8(OP_CREATE).put(creation).finish()
    fun proposeInput(id: ByteArray, opNonce: Long, auth: ByteArray, change: ByteArray) =
        Writer().u8(OP_PROPOSE).put32(id).u64(opNonce).put(auth).put(change).finish()

    /** Cancels every listed change that is still pending, skipping the others; at least one must be pending. */
    fun cancelInput(id: ByteArray, auth: ByteArray, changeNos: List<Long>) =
        Writer().u8(OP_CANCEL).put32(id).put(auth).put(encodeChangeNumbers(changeNos)).finish()

    fun applyInput(id: ByteArray, changeNo: Long) = Writer().u8(OP_APPLY).put32(id).u64(changeNo).finish()

    /**
     * A guardian approves recovering `id` to the keys of `keyList` (not empty). The
     * registry derives the proposal from the ID's current recovery epoch; the key
     * signs [approvalBody] with that proposal.
     */
    fun approveInput(id: ByteArray, guardian: ByteArray, keyList: ByteArray, auth: ByteArray) =
        Writer().u8(OP_APPROVE).put32(id).put32(guardian).put(keyList).put(auth).finish()

    fun withdrawInput(id: ByteArray, proposal: ByteArray, guardian: ByteArray, auth: ByteArray) =
        Writer().u8(OP_WITHDRAW).put32(id).put32(proposal).put32(guardian).put(auth).finish()

    fun recoverInput(id: ByteArray, keyList: ByteArray) = Writer().u8(OP_RECOVER).put32(id).put(keyList).finish()
    fun infoInput(id: ByteArray) = Writer().u8(OP_INFO).put32(id).finish()

    // ---- Events ----------------------------------------------------------------------

    @Serializable
    sealed class Event : ByContent() {
        abstract val id: ByteArray

        @Serializable @SerialName("created")
        class Created(override val id: ByteArray) : Event()
        @Serializable @SerialName("changeProposed")
        class ChangeProposed(override val id: ByteArray, val changeNo: Long, val effectiveAt: Long, val operations: List<Operation>) : Event()
        @Serializable @SerialName("changeCancelled")
        class ChangeCancelled(override val id: ByteArray, val changeNo: Long) : Event()
        @Serializable @SerialName("changeApplied")
        class ChangeApplied(override val id: ByteArray, val changeNo: Long) : Event()
        @Serializable @SerialName("recoveryApproved")
        class RecoveryApproved(
            override val id: ByteArray,
            val proposal: ByteArray,
            val guardian: ByteArray,
            val approvals: Long,
            val reachedAt: Long,
            /** The keys the recovery installs. */
            val keys: List<Key>,
        ) : Event()
        @Serializable @SerialName("recoveryWithdrawn")
        class RecoveryWithdrawn(override val id: ByteArray, val proposal: ByteArray, val guardian: ByteArray, val approvals: Long, val reachedAt: Long) : Event()
        @Serializable @SerialName("recovered")
        class Recovered(override val id: ByteArray, val keyEpoch: Long) : Event()

        override fun toString(): String = when (this) {
            is Created -> "Created(${id.hex()})"
            is ChangeProposed -> "ChangeProposed(${id.hex()}, $changeNo, $effectiveAt, $operations)"
            is ChangeCancelled -> "ChangeCancelled(${id.hex()}, $changeNo)"
            is ChangeApplied -> "ChangeApplied(${id.hex()}, $changeNo)"
            is RecoveryApproved -> "RecoveryApproved(${id.hex()}, ${proposal.hex()}, ${guardian.hex()}, $approvals, $reachedAt, $keys)"
            is RecoveryWithdrawn -> "RecoveryWithdrawn(${id.hex()}, ${proposal.hex()}, ${guardian.hex()}, $approvals, $reachedAt)"
            is Recovered -> "Recovered(${id.hex()}, $keyEpoch)"
        }
    }

    fun decodeEvent(payload: ByteArray): Event? = tryDecode {
        val r = Reader(payload)
        val topic = r.array32()
        val id = r.array32()
        val e = when {
            equal(topic, CREATED_TOPIC) -> Event.Created(id)
            equal(topic, CHANGE_PROPOSED_TOPIC) -> Event.ChangeProposed(id, r.u64(), r.u64(), readChange(r))
            equal(topic, CHANGE_CANCELLED_TOPIC) -> Event.ChangeCancelled(id, r.u64())
            equal(topic, CHANGE_APPLIED_TOPIC) -> Event.ChangeApplied(id, r.u64())
            equal(topic, RECOVERY_APPROVED_TOPIC) -> Event.RecoveryApproved(id, r.array32(), r.array32(), r.u64(), r.u64(), readKeyList(r))
            equal(topic, RECOVERY_WITHDRAWN_TOPIC) -> Event.RecoveryWithdrawn(id, r.array32(), r.array32(), r.u64(), r.u64())
            equal(topic, RECOVERED_TOPIC) -> Event.Recovered(id, r.u64())
            else -> throw DecodeError("unknown topic")
        }
        r.end()
        e
    }
}
