// Identity operations: signed by a key the identity lists, with the op nonce
// that lets a second proposal follow one still waiting; and where a recovery
// stands, as the registry's records say.
package app.tingid.state

import app.tingid.chain.Budgets
import app.tingid.chain.Contracts
import app.tingid.chain.HttpFailure
import app.tingid.chain.Id
import app.tingid.chain.MaybeSent
import app.tingid.chain.Reader
import app.tingid.chain.Scheme
import app.tingid.chain.Writer
import app.tingid.chain.encodeCall
import app.tingid.chain.filled
import app.tingid.chain.hex
import app.tingid.chain.publicKey
import app.tingid.chain.signingDigest
import app.tingid.chain.unhex
import app.tingid.chain.verifyDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegistryTest {
    private val node = FakeRestNode(filled(0x26))
    private val app = TestApp(node)
    private val chainId = node.chainId
    private val c = Contracts.canonical(chainId)
    private val registry = Registry(app.chain, app.store, now = { app.clock })

    private val secret = filled(1)
    private val pub = publicKey(secret)
    private val key = KeyMeta("k1", "Anna · Android phone", Scheme.Secp256k1, pub.hex(), 0)
    private val id = filled(0x2e)
    private val user = User("u1", "Anna", "#000", UserKind.Identity, identityId = id.hex(), registry = c.registry.hex(), keyId = key.id)

    init {
        app.secrets.saveKey(key.id, secret)
        app.store.addKey(key)
        app.store.addUser(user)
    }

    private fun identity(opNonce: Long = 5, keyId: Long = 0, of: ByteArray = id) = IdentityInfo(
        of,
        c.registry,
        Id.Core(opNonce, 0, 0, 0, keyId + 1, 1, 0, 2, 0, 604_800, 172_800, 0),
        listOf(IdentityKey(keyId, Id.Key(c.policySecp256k1, pub))),
        listOf(IdentitySlot(0, 0), IdentitySlot(1, 604_800)),
        emptyList(),
    )

    private val opKey = "op:${c.registry.hex()}:${id.hex()}"

    /** The input of a posted call to the registry. */
    private fun postedInput(n: Int): Reader {
        val r = Reader(unhex(node.posted[n].first))
        r.take(2 + 32)
        assertArrayEquals(c.registry, r.array32())
        return Reader(r.bytes())
    }

    /** Reads `u64 key_id ‖ Bytes(witness)` and checks the witness signs `body` for `target` as key `keyId`. */
    private fun checkAuth(r: Reader, target: ByteArray, body: ByteArray, keyId: Long = 0) {
        assertEquals(keyId, r.u64())
        val witness = r.bytes()
        assertTrue(verifyDigest(pub, signingDigest(Id.registryMessage(chainId, c.registry, target, keyId, body)), witness))
    }

    private fun propose(identity: IdentityInfo = identity()): String = runBlocking {
        registry.proposeChange(chainId, identity, registry.identitySigner(user, c, listOf(key), identity), listOf(Id.Operation.SetThreshold(1)))
    }

    private fun notedNonce() = app.store.data.value.nonces.getValue(opKey).nonce

    @Test
    fun `a proposal is signed by the identity's key, with its op nonce`() {
        val txId = propose()
        val change = Id.encodeChange(listOf(Id.Operation.SetThreshold(1)))
        val r = postedInput(0)
        assertEquals(Id.OP_PROPOSE, r.u8())
        assertArrayEquals(id, r.array32())
        assertEquals(5L, r.u64())
        checkAuth(r, id, Id.proposeBody(5, change))
        assertArrayEquals(change, r.rest())
        assertEquals(NonceNote(5, txId, app.clock), app.store.data.value.nonces[opKey])
    }

    @Test
    fun `the next proposal follows one still waiting`() {
        val first = propose()
        node.lookups[first] = pendingLookup()
        propose()
        assertEquals(6L, notedNonce())
    }

    @Test
    fun `the op nonce is free again when the proposal failed in a block, or the node does not know it`() {
        val first = propose()
        node.lookups[first] = includedLookup(10, status = 3)
        propose()
        assertEquals(5L, notedNonce())
        // The second is not known to the node.
        propose()
        assertEquals(5L, notedNonce())
    }

    @Test
    fun `the op nonce after one included, until the identity read counts it`() {
        val first = propose()
        node.lookups[first] = includedLookup(10)
        propose()
        assertEquals(6L, notedNonce())
        // Once read with the proposal counted, the identity's own nonce is the one.
        app.store.noteNonce(opKey, NonceNote(5, first, app.clock))
        propose(identity(opNonce = 6))
        assertEquals(6L, notedNonce())
    }

    @Test
    fun `a proposal that cannot be looked up counts as waiting, one a day old as lost`() {
        val first = propose()
        node.other["/v1/transactions/$first"] = { jsonResponse(buildJsonObject { put("error", "busy") }, 500) }
        propose()
        assertEquals(6L, notedNonce())
        app.store.noteNonce(opKey, NonceNote(7, "aa".repeat(32), app.clock - 86_401))
        propose()
        assertEquals(5L, notedNonce())
    }

    @Test
    fun `a proposal whose answer was lost holds its op nonce all the same`() {
        node.accept = { throw HttpFailure("could not be reached") }
        val e = runCatching { propose() }.exceptionOrNull()
        assertTrue(e is MaybeSent)
        assertEquals(NonceNote(5, (e as MaybeSent).txId, app.clock), app.store.data.value.nonces[opKey])
        // Kept for sending again, as every submission is.
        assertTrue(e.txId in app.store.data.value.outbox)
    }

    @Test
    fun `a cancel takes the change numbers in order, without an op nonce`() = runBlocking {
        val identity = identity()
        registry.cancelChanges(chainId, identity, registry.identitySigner(user, c, listOf(key), identity), listOf(7L, 3L))
        val r = postedInput(0)
        assertEquals(Id.OP_CANCEL, r.u8())
        assertArrayEquals(id, r.array32())
        checkAuth(r, id, Id.cancelBody(listOf(3L, 7L)))
        assertArrayEquals(Id.encodeChangeNumbers(listOf(3L, 7L)), r.rest())
    }

    @Test
    fun `a guardian approves and withdraws, signing for the identity it guards`() = runBlocking {
        val guardian = identity(keyId = 2, of = filled(0x47))
        val target = filled(0x3a)
        val keyList = Id.encodeKeyList(listOf(Id.Key(c.policySecp256k1, pub)))
        val proposal = Id.proposalHash(target, 0, keyList)
        val signer = registry.identitySigner(user.copy(identityId = guardian.id.hex()), c, listOf(key), guardian)
        assertEquals(2L, signer.keyId)
        registry.approveRecovery(chainId, c.registry, guardian, signer, target, keyList, proposal)
        val a = postedInput(0)
        assertEquals(Id.OP_APPROVE, a.u8())
        assertArrayEquals(target, a.array32())
        assertArrayEquals(guardian.id, a.array32())
        assertArrayEquals(keyList, a.take(keyList.size))
        checkAuth(a, target, Id.approvalBody(Id.OP_APPROVE, proposal, guardian.id), keyId = 2)
        a.end()
        registry.withdrawApproval(chainId, c.registry, guardian, signer, target, proposal)
        val w = postedInput(1)
        assertEquals(Id.OP_WITHDRAW, w.u8())
        assertArrayEquals(target, w.array32())
        assertArrayEquals(proposal, w.array32())
        assertArrayEquals(guardian.id, w.array32())
        checkAuth(w, target, Id.approvalBody(Id.OP_WITHDRAW, proposal, guardian.id), keyId = 2)
        w.end()
    }

    @Test
    fun `applying, recovering and queueing a recovery need no signature`() = runBlocking {
        val keyList = Id.encodeKeyList(listOf(Id.Key(c.policySecp256k1, pub)))
        registry.applyChange(chainId, identity(), 4)
        registry.recover(chainId, c.registry, id, keyList)
        registry.queueRecover(c.registry, id, keyList, 1_900_000_000)
        val recover = encodeCall(c.registry, Id.recoverInput(id, keyList), Budgets.CALL).hex()
        assertEquals(
            listOf(encodeCall(c.registry, Id.applyInput(id, 4), Budgets.CALL).hex() to null, recover to null, recover to 1_900_000_000L),
            node.posted.toList(),
        )
    }

    @Test
    fun `only a key the identity lists signs for it`() {
        val e = runCatching { registry.identitySigner(user, c, listOf(key), identity().let { IdentityInfo(it.id, it.registry, it.core, emptyList(), it.slots, it.pending) }) }.exceptionOrNull()
        assertEquals("no key on this device can sign for this identity", e?.message)
        assertEquals("the identity is not on chain yet", runCatching { registry.identitySigner(user, c, listOf(key), null) }.exceptionOrNull()?.message)
        val single = User("s", "Savings", "#000", UserKind.Key, keyId = key.id)
        assertEquals("not an identity", runCatching { registry.identitySigner(single, c, listOf(key), null) }.exceptionOrNull()?.message)
    }
}

class ProposalStatusTest {
    private val registry = filled(0x1d)
    private val target = filled(0x3a)
    private val guardian = filled(0x47)
    private val keyList = Id.encodeKeyList(listOf(Id.Key(filled(0x50), filled(4, 65))))
    private val core = Id.Core(0, 0, 2, 0, 1, 1, 3, 1, 0, 604_800, 100, 0)
    private val proposal = Id.proposalHash(target, 2, keyList)
    private fun record(approvals: Long, reachedAt: Long) = Writer().u64(approvals).u64(reachedAt).finish()

    private fun status(records: Map<ByteArray, ByteArray>, guardian: ByteArray? = this.guardian) = runBlocking {
        proposalStatus(MapChain(records.mapKeys { it.key.hex() }), registry, target, core, keyList, guardian)
    }

    @Test
    fun `counts approvals and says when the recovery can run`() {
        val s = status(
            mapOf(
                Id.proposalKey(target, proposal) to record(2, 1000),
                Id.approvalKey(target, proposal, guardian) to byteArrayOf(Id.APPROVED.toByte()),
                Id.currentApprovalKey(target, 2, guardian) to proposal,
                Id.guardianKey(target, guardian) to ByteArray(0),
            ),
        )
        assertArrayEquals(proposal, s.proposal)
        assertEquals(2L, s.approvals)
        assertEquals(2L, s.required)
        assertEquals(1000L, s.reachedAt)
        assertEquals(1100L, s.executableAt)
        assertEquals(Standing.Approved, s.standing)
        assertNull(s.approvesOther)
        assertEquals(true, s.guards)
    }

    @Test
    fun `says what a guardian approves instead, and when it withdrew`() {
        val other = filled(0x99)
        val s = status(
            mapOf(
                Id.proposalKey(target, proposal) to record(1, 0),
                Id.approvalKey(target, proposal, guardian) to byteArrayOf(Id.WITHDRAWN.toByte()),
                Id.currentApprovalKey(target, 2, guardian) to other,
            ),
        )
        assertEquals(Standing.Withdrawn, s.standing)
        assertArrayEquals(other, s.approvesOther)
        assertNull(s.executableAt)
        assertEquals(false, s.guards)
    }

    @Test
    fun `without a guardian, reads the proposal only`() {
        val chain = MapChain()
        val s = runBlocking { proposalStatus(chain, registry, target, core, keyList) }
        assertEquals(0L, s.approvals)
        assertNull(s.standing)
        assertNull(s.guards)
        assertEquals(listOf(Id.proposalKey(target, proposal).hex()), chain.asked)
        // A standing record of another size, or an unknown value, is no standing.
        assertNull(status(mapOf(Id.approvalKey(target, proposal, guardian) to byteArrayOf(1, 1))).standing)
        assertNull(status(mapOf(Id.approvalKey(target, proposal, guardian) to byteArrayOf(9))).standing)
    }

    @Test
    fun `finds which candidates guard an identity`() = runBlocking {
        val g2 = filled(0x48)
        val chain = MapChain(mapOf(Id.guardianKey(target, guardian).hex() to ByteArray(0)))
        assertEquals(listOf(guardian.hex()), linkedGuardians(chain, registry, target, listOf(g2, guardian)).map { it.hex() })
        assertEquals(emptyList<ByteArray>(), linkedGuardians(chain, registry, target, emptyList()))
        assertEquals(2, chain.asked.size)
    }
}
