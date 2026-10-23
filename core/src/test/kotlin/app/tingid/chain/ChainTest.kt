// Byte-exact interoperability with the Rust reference crates: every value in
// vectors.json holds byte vectors from a Rust implementation of the same
// formats: the app must produce exactly those bytes.
package app.tingid.chain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

private val v: JsonObject = Json.parseToJsonElement(ChainTest::class.java.getResource("/vectors.json")!!.readText()).jsonObject

private fun JsonObject.s(vararg path: String): String {
    var o: JsonObject = this
    for (p in path.dropLast(1)) o = o.getValue(p).jsonObject
    return o.getValue(path.last()).jsonPrimitive.content
}

private fun vec(vararg path: String) = v.s(*path)

private val chain = unhex(vec("chain_id"))
private fun fill(n: Int, size: Int = 32) = filled(n, size)
private fun big(n: Long) = BigInteger.valueOf(n)

class ChainTest {
    private val secret = fill(1)
    private val c = Contracts.canonical(chain)
    private val pub = publicKey(secret)
    private val account = policyAccount(c.policySecp256k1, pub)

    @Test
    fun `canonical contracts derive the same ids as the wallet CLI`() {
        assertEquals(vec("canonical", "policy_secp256k1"), c.policySecp256k1.hex())
        assertEquals(vec("canonical", "policy_p256"), c.policyP256.hex())
        assertEquals(vec("canonical", "policy_multisig_secp256k1"), c.multisig.hex())
        assertEquals(vec("canonical", "id_registry"), c.registry.hex())
        assertEquals(vec("canonical", "batch"), c.batch.hex())
    }

    @Test
    fun `code hashes are the manifest's`() {
        assertEquals("edea54d81ee9c28319bb5f0aea7aacbe891654e358f6a5cbe7225078ba7396d3", Contracts.codeHash(ContractName.IdRegistry))
        assertEquals("492e5470e3384a0bfa7cdfcf496c2e374a314325ce24327c75670f17e9201dcf", Contracts.codeHash(ContractName.PolicySecp256k1))
    }

    @Test
    fun `secp256k1 matches k256 (RFC 6979, low S)`() {
        assertEquals(vec("signing", "secp256k1", "pubkey"), pub.hex())
        assertEquals(vec("signing", "secp256k1", "account"), account.hex())
        val message = unhex(vec("signing", "message"))
        assertEquals(vec("signing", "digest"), signingDigest(message).hex())
        val sig = signMessage(secret, message)
        assertEquals(vec("signing", "secp256k1", "signature"), sig.hex())
        assertTrue(verifyDigest(pub, signingDigest(message), sig))
        // P-256 keys are only read: the vector's key is on its curve, and its account is the policy's.
        val p256 = unhex(vec("signing", "p256", "pubkey"))
        assertTrue(isPublicKey(Scheme.P256, p256))
        assertEquals(vec("signing", "p256", "account"), policyAccount(c.policyP256, p256).hex())
    }

    private val transfer = Coin.Action(
        Coin.Effect.Transfer(fill(2), big(12_345)),
        Coin.Timing(fee = big(1), tip = big(2), notBefore = 3, validUntil = 4),
    )

    private fun roundTrip(a: Coin.Action): Pair<ByteArray, Coin.Action> {
        val w = Writer()
        Coin.encodeAction(w, a)
        val bytes = w.finish()
        val r = Reader(bytes)
        val back = Coin.readAction(r)
        r.end()
        return bytes to back
    }

    @Test
    fun `coin signs and encodes an execute call`() {
        val message = Coin.message(chain, unhex(vec("coin", "coin")), account, 7, transfer)
        assertEquals(vec("coin", "message"), message.hex())
        val witness = signMessage(secret, message)
        val input = Coin.executeInput(Coin.PolicyAuth(c.policySecp256k1, pub, 7, witness), transfer)
        assertEquals(vec("coin", "execute"), input.hex())
    }

    @Test
    fun `coin timing ends with its condition`() {
        val (plain, back) = roundTrip(transfer)
        assertEquals(transfer, back)
        assertEquals(0, plain.last().toInt())
        val sponsored = transfer.copy(timing = transfer.timing.copy(within = Coin.Within(fill(10), fill(11))))
        val (conditioned, back2) = roundTrip(sponsored)
        assertEquals(sponsored, back2)
        assertEquals("01${fill(10).hex()}${fill(11).hex()}", conditioned.copyOfRange(plain.size - 1, conditioned.size).hex())
        assertEquals(plain.copyOfRange(0, plain.size - 1).hex(), conditioned.copyOfRange(0, plain.size - 1).hex())
    }

    @Test
    fun `coin signs and encodes a cancel of several actions, without a nonce`() {
        val cancel = Coin.Cancel(listOf(1L, 7L), big(5), 9)
        val message = Coin.cancelMessage(chain, unhex(vec("coin", "coin")), account, cancel)
        assertEquals(vec("coin", "cancel_message"), message.hex())
        val witness = signMessage(secret, message)
        assertEquals(vec("coin", "cancel"), Coin.cancelInput(Coin.CancelAuth(c.policySecp256k1, pub, witness), cancel).hex())
        assertEquals(vec("coin", "contract_cancel"), Coin.cancelInput(null, cancel).hex())
        val w = Writer()
        Coin.encodeCancel(w, cancel)
        assertEquals(cancel, Coin.readCancel(Reader(w.finish())))
        // Sequences must be strictly increasing: never repeated, never descending.
        assertThrows(IllegalArgumentException::class.java) { Coin.cancelInput(null, Coin.Cancel(listOf(4L, 4L), BigInteger.ZERO, 0)) }
        assertThrows(IllegalArgumentException::class.java) { Coin.cancelInput(null, Coin.Cancel(listOf(4L, 1L), BigInteger.ZERO, 0)) }
    }

    @Test
    fun `coin decodes records and events`() {
        assertEquals(Coin.AccountRecord(big(1000), big(250), 3), Coin.decodeAccount(unhex(vec("coin", "account_record"))))
        val config = Coin.decodeConfig(unhex(vec("coin", "config_record")))!!
        assertEquals("Test Coin", config.name)
        assertEquals("TST", config.symbol)
        assertEquals(2, config.decimals)
        assertEquals(1000L, config.halvingInterval)
        assertEquals(42L, config.deployHeight)
        assertEquals(vec("coin", "scheduled_key"), Coin.scheduledKey(account, 7).hex())
        val scheduled = Coin.decodeScheduled(unhex(vec("coin", "scheduled_record")))!!
        assertEquals(99L, scheduled.executeAt)
        assertEquals(big(12_347), scheduled.reserved)
        assertEquals(big(2), scheduled.tip)
        assertEquals(Coin.Effect.Transfer(fill(2), big(12_345)), scheduled.effect)
        assertEquals(Coin.Event.Transfer(account, fill(2), big(12_345)), Coin.decodeEvent(unhex(vec("coin", "transfer_event"))))
        assertEquals(
            Coin.Event.Scheduled(account, 7, 99, big(2), Coin.Effect.Transfer(fill(2), big(12_345))),
            Coin.decodeEvent(unhex(vec("coin", "scheduled_event"))),
        )
        assertEquals(Coin.Event.Executed(account, 7), Coin.decodeEvent(unhex(vec("coin", "executed_event"))))
        assertEquals(Coin.Event.Cancelled(account, 7), Coin.decodeEvent(unhex(vec("coin", "cancelled_event"))))
        assertEquals(Coin.Event.ProducerPayment(fill(8), big(1)), Coin.decodeEvent(unhex(vec("coin", "producer_payment"))))
        assertNull(Coin.decodeEvent(utf8("junk")))
    }

    @Test
    fun `coin labels show hidden characters`() {
        assertEquals("Dollar�", Coin.visibleLabel("Dollar‮"))
        assertEquals("the name and symbol cannot hold invisible or direction characters", Coin.deployProblem(
            Coin.Options("A​B", "AB", 2, Coin.UNCAPPED, big(1), 0, big(0), Coin.ZERO_ACCOUNT, emptyList()),
        ))
    }

    private val keys = listOf(
        Id.Key(c.policyP256, unhex(vec("signing", "p256", "pubkey"))),
        Id.Key(c.policySecp256k1, pub),
    )
    private val settings = Id.Settings(
        keys = keys,
        guardians = listOf(fill(5), fill(6)),
        threshold = 0,
        changeDelay = 604_800,
        recoveryDelay = 172_800,
        slots = listOf(0L, 604_800L),
        salt = fill(9),
    )
    private val creation = Id.encodeCreation(settings)
    private val identity = Id.idOf(creation)
    private val keyList = Id.encodeKeyList(keys.drop(1))

    @Test
    fun `identity creates the same id and accounts`() {
        assertEquals(vec("identity", "creation"), creation.hex())
        assertEquals(vec("identity", "id"), identity.hex())
        assertEquals(vec("identity", "create_input"), Id.createInput(creation).hex())
        assertEquals(vec("identity", "account_slot_1"), Id.account(c.registry, identity, 1).hex())
        assertEquals(settings, Id.decodeCreation(creation))
        assertNull(Id.validSettings(settings, identity))
    }

    @Test
    fun `identity lists guardians in strictly increasing byte order, never the id`() {
        fun with(guardians: List<ByteArray>) = Id.Settings(settings.keys, guardians, 0, 604_800, 172_800, settings.slots, settings.salt)
        assertEquals("guardians must be sorted", Id.validSettings(with(listOf(fill(6), fill(5))), identity))
        assertEquals("guardians must be distinct", Id.validSettings(with(listOf(fill(5), fill(5))), identity))
        assertEquals("an identity cannot guard itself", Id.validSettings(with(listOf(identity)), identity))
        assertEquals(vec("identity", "creation"), Id.encodeCreation(with(Id.sortGuardians(listOf(fill(6), fill(5))))).hex())
    }

    @Test
    fun `identity derives record keys`() {
        assertEquals(vec("identity", "core_key"), Id.coreKey(identity).hex())
        assertEquals(vec("identity", "key_key_0_1"), Id.keyKey(identity, 0, 1).hex())
        assertEquals(vec("identity", "guardian_key_5"), Id.guardianKey(identity, fill(5)).hex())
        assertEquals(vec("identity", "slot_key_1"), Id.slotKey(identity, 1).hex())
        assertEquals(vec("identity", "change_key_2"), Id.changeKey(identity, 2).hex())
        assertEquals(vec("identity", "proposal_key"), Id.proposalKey(identity, fill(4)).hex())
        assertEquals(vec("identity", "approval_key"), Id.approvalKey(identity, fill(4), fill(5)).hex())
        assertEquals(vec("identity", "current_approval_key_3"), Id.currentApprovalKey(identity, 3, fill(5)).hex())
    }

    @Test
    fun `identity decodes core and key records`() {
        val core = Id.decodeCore(unhex(vec("identity", "core_record")))!!
        assertEquals(5L, core.nextKeyId)
        assertEquals(3L, core.guardianCount)
        assertEquals(2L, core.required)
        assertEquals(vec("identity", "core_record"), Id.encodeCore(core).hex())
        assertEquals(keys[1], Id.decodeKeyRecord(unhex(vec("identity", "key_record"))))
    }

    @Test
    fun `identity encodes changes and proposals exactly`() {
        val ops = listOf(
            Id.Operation.AddKey(keys[1]),
            Id.Operation.RemoveKey(3),
            Id.Operation.AddGuardian(fill(7)),
            Id.Operation.SetSlot(1, 99),
        )
        val change = Id.encodeChange(ops)
        assertEquals(vec("identity", "change"), change.hex())
        assertEquals(ops, Id.decodeChange(change))
        val rec = Id.decodeChangeRecord(unhex(vec("identity", "change_record")))!!
        assertEquals(2L, rec.changeEpoch)
        assertEquals(1L, rec.proposer)
        assertEquals(1_699_000_000L, rec.proposedAt)
        assertEquals(1_700_000_000L, rec.effectiveAt)
        assertEquals(ops, rec.operations)
        // Pending in the current change epoch while the proposing key exists.
        val core = Id.decodeCore(unhex(vec("identity", "core_record")))!!
        assertTrue(Id.isPending(rec, core, listOf(0L, 1L)))
        assertFalse(Id.isPending(rec, core, listOf(0L, 2L)))
        assertFalse(Id.isPending(rec, core.copy(changeEpoch = 3), listOf(0L, 1L)))
        val body = Id.proposeBody(4, change)
        val message = Id.registryMessage(chain, c.registry, identity, 1, body)
        assertEquals(vec("identity", "propose_message"), message.hex())
        val auth = Id.keyAuth(1, signMessage(secret, message))
        assertEquals(vec("identity", "propose_input"), Id.proposeInput(identity, 4, auth, change).hex())
        assertEquals(vec("identity", "key_list"), keyList.hex())
        assertEquals(vec("identity", "proposal_hash"), Id.proposalHash(identity, 2, keyList).hex())
        assertEquals(vec("identity", "key_auth"), Id.keyAuth(0, utf8("sig")).hex())
    }

    @Test
    fun `identity cancels several changes with one signature, without a nonce`() {
        val message = Id.registryMessage(chain, c.registry, identity, 1, Id.cancelBody(listOf(2L, 5L)))
        assertEquals(vec("identity", "cancel_message"), message.hex())
        val auth = Id.keyAuth(1, signMessage(secret, message))
        assertEquals(vec("identity", "cancel_input"), Id.cancelInput(identity, auth, listOf(2L, 5L)).hex())
        assertThrows(IllegalArgumentException::class.java) { Id.cancelBody(listOf(5L, 2L)) }
    }

    @Test
    fun `identity approves a recovery with the key list`() {
        val proposal = Id.proposalHash(identity, 3, keyList)
        // Signed by the guardian's key 0, named in the message.
        val message = Id.registryMessage(chain, c.registry, identity, 0, Id.approvalBody(Id.OP_APPROVE, proposal, fill(5)))
        assertEquals(vec("identity", "approve_message"), message.hex())
        val auth = Id.keyAuth(0, signMessage(secret, message))
        assertEquals(vec("identity", "approve_input"), Id.approveInput(identity, fill(5), keyList, auth).hex())
    }

    @Test
    fun `identity decodes events`() {
        assertEquals(Id.Event.Created(identity), Id.decodeEvent(unhex(vec("identity", "events", "created"))))
        assertTrue(Id.decodeEvent(unhex(vec("identity", "events", "change_proposed"))) is Id.Event.ChangeProposed)
        assertEquals(
            Id.Event.RecoveryApproved(identity, fill(4), fill(5), 2, 1_700_000_000, keys.drop(1)),
            Id.decodeEvent(unhex(vec("identity", "events", "recovery_approved"))),
        )
        assertEquals(
            Id.Event.RecoveryWithdrawn(identity, fill(4), fill(5), 1, 0),
            Id.decodeEvent(unhex(vec("identity", "events", "recovery_withdrawn"))),
        )
        assertEquals(Id.Event.Recovered(identity, 1), Id.decodeEvent(unhex(vec("identity", "events", "recovered"))))
    }

    @Test
    fun `identity refuses counts that the bytes cannot hold`() {
        // A key list claiming 2^40 keys in a few bytes is refused, not looped over.
        assertNull(Id.decodeKeyList(u64le(1L shl 40) + ByteArray(8)))
        assertNull(Id.decodeChange(u64le(1L shl 40)))
        // A u64 of 2^63 or more is refused.
        assertNull(Id.decodeCore(byteArrayOf(1) + ByteArray(96) { 0xff.toByte() }))
    }

    @Test
    fun `batch encodes calls back to back and decodes results`() {
        val input = batchInput(listOf(BatchCall(fill(7), utf8("abc")), BatchCall(fill(8), ByteArray(0))))
        assertEquals("${fill(7).hex()}03000000616263${fill(8).hex()}00000000", input.hex())
        assertEquals("", batchInput(emptyList()).hex())
        assertEquals(listOf("61", ""), decodeBatchResult(unhex("0100000061" + "00000000"))!!.map { it.hex() })
        assertEquals("00", BATCH_QUERY.hex())
        assertNull(decodeBatchResult(unhex("05000000")))
    }

    @Test
    fun `batch binds a sponsoring payment to the calls before it`() {
        val coinId = unhex(vec("coin", "coin"))
        val first = BatchCall(coinId, unhex(vec("coin", "execute")))
        assertEquals(vec("batch", "prefix_start"), batchPrefixStart().hex())
        val prefix = batchPrefix(listOf(first))
        assertEquals(vec("batch", "prefix_1"), prefix.hex())
        // A zero transfer that carries the fee, valid only as the batch's second call.
        val bound = Coin.Action(Coin.Effect.Transfer(account, BigInteger.ZERO), Coin.Timing(fee = big(9), within = Coin.Within(c.batch, prefix)))
        val message = Coin.message(chain, coinId, account, 8, bound)
        assertEquals(vec("coin", "bound_message"), message.hex())
        val witness = signMessage(secret, message)
        val execute = Coin.executeInput(Coin.PolicyAuth(c.policySecp256k1, pub, 8, witness), bound)
        assertEquals(vec("coin", "bound_execute"), execute.hex())
        // The sponsoring payment goes last, after the calls it commits to.
        assertEquals(vec("batch", "input"), batchInput(listOf(first, BatchCall(coinId, execute))).hex())
    }

    @Test
    fun `batch builds a sponsored call, the operation then the bound payment`() {
        val coinId = unhex(vec("coin", "coin"))
        val operation = BatchCall(coinId, unhex(vec("coin", "execute")))
        val payer = FeePayer(Signer(MemoryKey(secret), null, c.policySecp256k1, pub), coinId, big(9))
        val tx = sponsoredCall(chain, c, listOf(operation), payer, 8, 0)
        assertEquals(encodeCall(c.batch, unhex(vec("batch", "input")), Budgets.BATCH).hex(), tx.hex())
        val payment = feePayment(chain, c, listOf(operation), payer, 8, 0)
        assertEquals(vec("coin", "bound_message"), payment.message.hex())
        assertEquals(vec("coin", "bound_execute"), payment.call.input.hex())
        assertEquals(Coin.Within(c.batch, batchPrefix(listOf(operation))), payment.action.timing.within)
        assertEquals(big(9), payment.action.timing.fee)
        // A payment expires, so one whose operation went in alone does not stay usable.
        assertEquals(1_790_000_000L, feePayment(chain, c, listOf(operation), payer, 8, 1_790_000_000).action.timing.validUntil)
    }

    @Test
    fun `transactions encode calls and derive ids`() {
        val tx = encodeCall(fill(7), utf8("abc"), Resources(1, 2, 3, 4))
        assertEquals(vec("transaction", "call"), tx.hex())
        assertEquals(vec("transaction", "call_id"), transactionId(chain, tx).hex())
        assertEquals(vec("transaction", "contract_ordinal_0"), contractId(chain, tx).hex())
    }

    @Test
    fun `the production chain's canonical ids are the ones on chain`() {
        val prod = Contracts.canonical(unhex(Networks.PRODUCTION))
        assertEquals("1185ab4a07f12fd03e130acfd51211cd9360ec94b89e5f742bbd8f52767714bd", prod.registry.hex())
        assertEquals("9558a75642eb797de5ec1be056f01c62d99c492c8f7ec6efed460767588987c9", prod.policySecp256k1.hex())
        assertNotNull(prod.kindOf(prod.policyP256))
    }
}

class BytesTest {
    @Test
    fun `hex, base64url and integers round-trip`() {
        assertEquals("00ff10", byteArrayOf(0, -1, 16).hex())
        assertArrayEquals(byteArrayOf(0, -1, 16), unhex(" 00FF10 "))
        assertThrows(IllegalArgumentException::class.java) { unhex("0g") }
        for (n in 0..40) {
            val b = ByteArray(n) { (it * 37).toByte() }
            assertArrayEquals(b, fromBase64url(base64url(b)))
        }
        assertNull(fromBase64url("AB"))
        assertNull(fromBase64url("A"))
        assertNull(fromBase64url("AB=="))
        assertEquals(BigInteger("340282366920938463463374607431768211455"), Reader(u128le(U128_MAX)).u128())
        assertThrows(IllegalArgumentException::class.java) { u128le(U128_MAX + BigInteger.ONE) }
        assertThrows(DecodeError::class.java) { Reader(ByteArray(3)).u32() }
        assertThrows(DecodeError::class.java) { Reader(ByteArray(2)).apply { u8() }.end() }
    }

    @Test
    fun `white space is Unicode's on every platform`() {
        assertEquals("a b", trimWhiteSpace(" 　a b \t"))
        assertEquals("﻿a", trimWhiteSpace("﻿a"))
    }
}
