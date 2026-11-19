// Throwaway review probe: not to be committed.
package app.tingid.state

import app.tingid.chain.Coin
import app.tingid.chain.Contracts
import app.tingid.chain.FeePayer
import app.tingid.chain.HEADER_BYTES
import app.tingid.chain.Signer
import app.tingid.chain.MemoryKey
import app.tingid.chain.Tcr
import app.tingid.chain.filled
import app.tingid.chain.hex
import app.tingid.chain.publicKey
import app.tingid.chain.taggedHash
import app.tingid.chain.u64le
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigInteger

class ZzReviewProbeTest {
    @Test
    fun `a call's one-hour note frees the nonce of a three-hour payment still waiting`() = runBlocking {
        val node = FakeRestNode(filled(0x28))
        val app = TestApp(node)
        node.emptyState = true
        app.chain.node.status()
        val c = Contracts.canonical(node.chainId)
        val secret = filled(1)
        val pub = publicKey(secret)
        val signer = Signer(MemoryKey(secret), null, c.policySecp256k1, pub)
        val coin = filled(0x44)
        val payer = FeePayer(signer, coin, BigInteger.valueOf(1_000))
        val t0 = app.clock
        // A payment, signed for three hours, at nonce 0.
        val pay = app.chain.submitCoinAction(node.chainId, coin, signer, Coin.Action(Coin.Effect.Transfer(filled(0x7f), BigInteger.ONE), Coin.NO_TIMING), payer = payer)
        node.lookups[pay.txId] = pendingLookup()
        assertEquals(0L, pay.nonce)
        assertEquals(t0 + 3 * 3600, pay.expires)
        // A minute later, a program call paying from the same account: its payment queues at nonce 1, signed for an hour.
        app.clock = t0 + 60
        val program = filled(0x5a)
        val call = app.chain.submitProgramCall(node.chainId, program, Tcr.templateHash("give(item:u64): Give item {item}"), u64le(7), signer, coin to Coin.Effect.Burn(BigInteger.ONE), null, payer)
        node.lookups[call.txId] = pendingLookup()
        val account = pay.account
        assertEquals(1L, app.chain.nextNonce(coin, account) - 1)
        // 2 h 2 min after the payment: it is still valid for another 58 minutes (and a block may take it an hour after that).
        app.clock = t0 + 60 + 3600 + 3600 + 61
        val next = app.chain.nextNonce(coin, account)
        println("PROBE next nonce at +2h02 = $next (the payment at nonce 0 is valid until +3h, cleared at +4h)")
        assertEquals(0L, next)
    }

    @Test
    fun `a header's timestamp sets the expiry with no bound`() = runBlocking {
        val node = FakeRestNode(filled(0x29))
        val app = TestApp(node)
        val year2100 = 4_102_444_800L
        val header = ByteArray(HEADER_BYTES).also { h ->
            h[0] = 1
            node.chainId.copyInto(h, 2)
            u64le(1).copyInto(h, 34)
            u64le(year2100).copyInto(h, 74)
            h.fill(0xff.toByte(), 82, 114)
        }
        val tip = taggedHash("block", header).hex()
        node.other["/v1/status"] = { jsonResponse(buildJsonObject { put("chain_id", node.chainId.hex()); put("height", 1); put("tip", tip); put("work", "1") }) }
        node.other["/v1/blocks/$tip/raw?offset=0&length=294"] = { jsonResponse(buildJsonObject { put("hex", header.hex()) }) }
        app.chain.node.status()
        println("PROBE calls = ${node.calls}")
        println("PROBE signedExpiry = ${app.chain.signedExpiry()} (clock ${app.clock})")
        assertEquals(year2100 + 3 * 3600, app.chain.signedExpiry())
    }
}
