// Identity operations: proposing and cancelling changes, applying due ones,
// guardian approvals and recovery (wiki: programs/identity). The registry's
// operations pay nothing themselves; a `payer` adds a fee from one of the
// user's instant accounts.
package app.tingid.state

import app.tingid.chain.BatchCall
import app.tingid.chain.Budgets
import app.tingid.chain.Canonical
import app.tingid.chain.ChainApi
import app.tingid.chain.Contracts
import app.tingid.chain.FeePayer
import app.tingid.chain.HexSerializer
import app.tingid.chain.Id
import app.tingid.chain.MaybeSent
import app.tingid.chain.StorageRead
import app.tingid.chain.encodeCall
import app.tingid.chain.equal
import app.tingid.chain.hex
import app.tingid.chain.sha256
import app.tingid.chain.unhex
import app.tingid.chain.utf8
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer

/** An identity as the registry knows it: its id and the registry it is in. */
class IdentityRef(val id: ByteArray, val registry: ByteArray)

/** Where a guardian stands on a proposal: approving, or withdrawn (it cannot approve again). */
enum class Standing { Approved, Withdrawn }

class ProposalStatus(
    val proposal: ByteArray,
    val approvals: Long,
    val required: Long,
    val reachedAt: Long,
    val executableAt: Long?,
    /** The asking guardian's standing, when one was given and it has one. */
    val standing: Standing?,
    /**
     * Another proposal of this recovery epoch that the asking guardian approves.
     * A guardian approves one proposal at a time: it withdraws that approval
     * before it can approve this one.
     */
    val approvesOther: ByteArray?,
    /** Whether the asking guardian guards the identity at all; null when none was given. */
    val guards: Boolean?,
)

/** Who a device key serves, by user name; `unknown`: identities whose keys are not read, which may list it too. */
data class KeyUsers(val used: List<String>, val unknown: List<String>)

/** A device key that is one of an identity's keys, and which. */
class LinkedKey(val key: KeyMeta, val keyId: Long)

/** Pending changes one cancel takes at most, to stay within a transaction's budget. */
const val CANCEL_CHUNK = 16

/** How long a noted proposal may hold the op nonce: one that left no trace by then is taken as lost. */
private const val OP_NOTE_LIFE = 86_400L

class Registry(
    private val chain: Chain,
    private val store: Store,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    /** A device key that is one of the identity's current keys. */
    fun identitySigner(user: User, c: Canonical, keys: List<KeyMeta>, identity: IdentityInfo?, preferredKeyId: String? = null): RegistrySigner {
        val s = chain.resolveSigner(user, 0, c, keys, identity, preferredKeyId)
        val keyId = s.keyId ?: throw IllegalStateException("not an identity")
        return RegistrySigner(s.key, keyId)
    }

    suspend fun proposeChange(
        chainId: ByteArray,
        identity: IdentityInfo,
        signer: RegistrySigner,
        ops: List<Id.Operation>,
        payer: FeePayer? = null,
    ): String {
        val change = Id.encodeChange(ops)
        val nonce = nextOpNonce(identity)
        val noted = { txId: String -> store.noteNonce(opNonceKey(identity), NonceNote(nonce, txId, now())) }
        try {
            val txId = chain.submitRegistryOp(
                chainId,
                identity.registry,
                identity.id,
                signer,
                Id.proposeBody(nonce, change),
                { auth -> Id.proposeInput(identity.id, nonce, auth, change) },
                payer,
            )
            noted(txId)
            return txId
        } catch (e: MaybeSent) {
            noted(e.txId)
            throw e
        }
    }

    private fun opNonceKey(identity: IdentityInfo) = "op:${identity.registry.hex()}:${identity.id.hex()}"

    /**
     * The op nonce for the next proposal. The identity counts a proposal only once
     * a block takes it in, so while this phone's last one still waits, the next
     * takes the nonce after it: "add a new key", then at once "remove the stolen
     * one", both go.
     */
    private suspend fun nextOpNonce(identity: IdentityInfo): Long {
        val current = identity.core.opNonce
        val note = store.data.value.nonces[opNonceKey(identity)]
        if (note == null || note.nonce < current || now() - note.at > OP_NOTE_LIFE) return current
        // One that cannot be looked up counts as waiting, so no op nonce is used twice.
        val lookup = try {
            chain.node.transaction(note.txId) ?: return current
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        }
        val included = lookup?.occurrences?.lastOrNull()
        if (included != null) return if (included.receipt.status == 0) note.nonce + 1 else current
        return note.nonce + 1
    }

    /** Cancels pending changes with one signature; a cancel carries no op nonce. */
    suspend fun cancelChanges(
        chainId: ByteArray,
        identity: IdentityInfo,
        signer: RegistrySigner,
        changeNos: List<Long>,
        payer: FeePayer? = null,
    ): String {
        val numbers = changeNos.sorted()
        return chain.submitRegistryOp(
            chainId,
            identity.registry,
            identity.id,
            signer,
            Id.cancelBody(numbers),
            { auth -> Id.cancelInput(identity.id, auth, numbers) },
            payer,
        )
    }

    suspend fun applyChange(chainId: ByteArray, identity: IdentityInfo, changeNo: Long, payer: FeePayer? = null): String =
        chain.submitWithFee(Contracts.canonical(chainId), chainId, BatchCall(identity.registry, Id.applyInput(identity.id, changeNo)), payer)

    /**
     * A guardian approves replacing `target`'s keys with `keyList`. The input carries
     * the key list itself; the registry derives the proposal in `target`'s current
     * recovery epoch, which `proposal` must be, since that is what the key signs.
     */
    suspend fun approveRecovery(
        chainId: ByteArray,
        registry: ByteArray,
        guardian: IdentityInfo,
        guardianSigner: RegistrySigner,
        target: ByteArray,
        keyList: ByteArray,
        proposal: ByteArray,
        payer: FeePayer? = null,
    ): String = chain.submitRegistryOp(
        chainId,
        registry,
        target,
        guardianSigner,
        Id.approvalBody(Id.OP_APPROVE, proposal, guardian.id),
        { auth -> Id.approveInput(target, guardian.id, keyList, auth) },
        payer,
    )

    /** A guardian withdraws its approval of `proposal`. */
    suspend fun withdrawApproval(
        chainId: ByteArray,
        registry: ByteArray,
        guardian: IdentityInfo,
        guardianSigner: RegistrySigner,
        target: ByteArray,
        proposal: ByteArray,
        payer: FeePayer? = null,
    ): String = chain.submitRegistryOp(
        chainId,
        registry,
        target,
        guardianSigner,
        Id.approvalBody(Id.OP_WITHDRAW, proposal, guardian.id),
        { auth -> Id.withdrawInput(target, proposal, guardian.id, auth) },
        payer,
    )

    suspend fun recover(chainId: ByteArray, registry: ByteArray, target: ByteArray, keyList: ByteArray, payer: FeePayer? = null): String =
        chain.submitWithFee(Contracts.canonical(chainId), chainId, BatchCall(registry, Id.recoverInput(target, keyList)), payer)

    /**
     * Hands the node the recovery to finish once due (`at`, seconds): it holds it
     * until then and includes it as soon as it succeeds, so the identity's current
     * keys get no time after the delay to stop it. It goes alone, without a fee: a
     * paid one signed now would expire before a long delay ends. The bytes are
     * those [recover] sends alone, so whichever reaches a block first counts.
     */
    suspend fun queueRecover(registry: ByteArray, target: ByteArray, keyList: ByteArray, at: Long): String =
        chain.node.submit(encodeCall(registry, Id.recoverInput(target, keyList), Budgets.CALL), at)

    /**
     * The recovery of `target` to `keyList` in its current recovery epoch. Any
     * guardian, threshold or delay change that applies starts a new epoch and
     * voids the proposal; reaching the threshold voids every pending change.
     */
    /** Where the recovery of `target` to `keyList` stands, read from the user's nodes (see the function of the same name). */
    suspend fun proposalStatus(registry: ByteArray, target: ByteArray, core: Id.Core, keyList: ByteArray, guardian: ByteArray? = null): ProposalStatus =
        proposalStatus(chain.node, registry, target, core, keyList, guardian)

    /** The guardians of `me` among `candidates`, from the cache, read again with each block. */
    fun linkedGuardians(me: IdentityRef?, candidates: List<ByteArray>): Flow<List<ByteArray>> {
        if (me == null) return flowOf(emptyList())
        val names = candidates.map { it.hex() }.sorted()
        // The candidates are part of the key: other candidates are read again.
        val key = "guardians:${me.id.hex()}:${sha256(utf8(names.joinToString(","))).hex().take(16)}"
        return chain.watch(listOf(key), ListSerializer(HexSerializer)) {
            listOf(linkedGuardians(chain.node, me.registry, me.id, candidates))
        }.map { v -> (v.values[key] ?: emptyList()).filter { it.hex() in names } }
    }
}

suspend fun proposalStatus(node: ChainApi, registry: ByteArray, target: ByteArray, core: Id.Core, keyList: ByteArray, guardian: ByteArray? = null): ProposalStatus {
    val proposal = Id.proposalHash(target, core.recoveryEpoch, keyList)
    val reads = listOf(StorageRead(registry, Id.proposalKey(target, proposal))) + (
        if (guardian == null) {
            emptyList()
        } else {
            listOf(
                StorageRead(registry, Id.approvalKey(target, proposal, guardian)),
                StorageRead(registry, Id.currentApprovalKey(target, core.recoveryEpoch, guardian)),
                StorageRead(registry, Id.guardianKey(target, guardian)),
            )
        }
        )
    val values = node.storageMany(reads)
    val approval = values.getOrNull(1)
    val current = values.getOrNull(2)
    val guarding = values.getOrNull(3)
    val p = Id.decodeProposal(values[0])
    val required = core.required
    val standing = if (approval?.size == 1) {
        when (approval[0].toInt() and 0xff) {
            Id.APPROVED -> Standing.Approved
            Id.WITHDRAWN -> Standing.Withdrawn
            else -> null
        }
    } else {
        null
    }
    return ProposalStatus(
        proposal = proposal,
        approvals = p.approvals,
        required = required,
        reachedAt = p.reachedAt,
        executableAt = if (p.reachedAt > 0 && p.approvals >= required) p.reachedAt + core.recoveryDelay else null,
        standing = standing,
        approvesOther = if (current != null && current.size == 32 && !equal(current, proposal)) current else null,
        guards = if (guardian != null) guarding != null else null,
    )
}

/** Which of `candidates` guard `id` right now. */
suspend fun linkedGuardians(node: ChainApi, registry: ByteArray, id: ByteArray, candidates: List<ByteArray>): List<ByteArray> {
    if (candidates.isEmpty()) return emptyList()
    val records = node.storageMany(candidates.map { StorageRead(registry, Id.guardianKey(id, it)) })
    return candidates.filterIndexed { i, _ -> records[i] != null }
}

fun userIdentity(user: User): IdentityRef? {
    if (user.kind != UserKind.Identity || user.identityId == null || user.registry == null) return null
    return IdentityRef(unhex(user.identityId), unhex(user.registry))
}

/** Which key of the identity a device key is, if any. */
fun linkedKeyId(c: Canonical, identity: IdentityInfo, key: KeyMeta): Long? {
    val policy = c.policyFor(key.scheme)
    val pub = unhex(key.pub)
    return identity.keys.firstOrNull { equal(it.key.policy, policy) && equal(it.key.params, pub) }?.keyId
}

/**
 * Who a device key serves: the users it was made for, and identities that list
 * it on chain, will once a pending change adds it, or, before they are on
 * chain, list it in their creation. `unknown` names identities whose keys are
 * not read (not yet, or of another network): they may list it too.
 * `identities` maps user ids to their identity, null when not on chain; a user
 * missing from it is not read yet.
 */
fun keyUsers(key: KeyMeta, users: List<User>, identities: Map<String, IdentityInfo?>, c: Canonical?): KeyUsers {
    val used = ArrayList<String>()
    val unknown = ArrayList<String>()
    for (u in users) {
        if (u.keyId == key.id || u.phraseKeyId == key.id) {
            used.add(u.name)
            continue
        }
        if (u.kind != UserKind.Identity) continue
        val identity = identities[u.id]
        val lists = when {
            c == null || !identities.containsKey(u.id) -> {
                unknown.add(u.name)
                continue
            }
            identity != null -> linkedKeyId(c, identity, key) != null || pendingAdds(identity, key)
            else -> creationLists(u, key)
        }
        if (lists) used.add(u.name)
    }
    return KeyUsers(used, unknown)
}

/** Whether a pending change of `identity` adds `key`. */
private fun pendingAdds(identity: IdentityInfo, key: KeyMeta): Boolean {
    val pub = unhex(key.pub)
    return identity.pending.any { p -> p.record.operations.any { it is Id.Operation.AddKey && equal(it.key.params, pub) } }
}

private fun creationLists(user: User, key: KeyMeta): Boolean {
    val creation = user.creation?.let { Id.decodeCreation(unhex(it)) } ?: return false
    val pub = unhex(key.pub)
    return creation.keys.any { equal(it.params, pub) }
}

/** The device keys that can sign for the identity right now. */
fun linkedDeviceKeys(c: Canonical, identity: IdentityInfo, keys: List<KeyMeta>): List<LinkedKey> =
    keys.mapNotNull { key -> linkedKeyId(c, identity, key)?.let { LinkedKey(key, it) } }
