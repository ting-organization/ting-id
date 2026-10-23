// Following submitted transactions: what the app makes of the node's answer.
package app.tingid.state

import app.tingid.chain.Deferred
import app.tingid.chain.Occurrence
import app.tingid.chain.Receipt
import app.tingid.chain.TransactionLookup
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigInteger

class WatchTest {
    /** Stands in for ui/describe's words. */
    private val words = { status: String -> if (status == "Revert") "the program refused it" else "it failed ($status)" }

    private fun tx(at: Long, coin: String? = null, account: String? = null, amount: String? = null) =
        SubmittedTx(id = "aa", at = at, summary = "Send 1 DLR", userId = "u", coin = coin, account = account, amount = amount)

    private fun receipt(statusName: String) = Receipt(if (statusName == "Success") 0 else 3, statusName, listOf(0L, 0, 0, 0), ByteArray(0), emptyList())
    private fun lookup(pending: Boolean = false, deferred: Deferred? = null, occurrences: List<Occurrence> = emptyList()) = TransactionLookup(pending, deferred, occurrences)
    private fun inBlock(height: Long, status: String) = lookup(occurrences = listOf(Occurrence(height, receipt(status))))
    private fun kept(at: Long, notBefore: Long? = null) = OutboxEntry(raw = "00", notBefore = notBefore, at = at)

    private fun decide(tx: SubmittedTx, lookup: TransactionLookup?, kept: OutboxEntry?, now: Long, tip: Long, twin: Twin? = null) =
        decide(tx, lookup, kept, now, tip, twin, words)

    @Test
    fun `settles once a block includes it, as done or failed`() {
        assertEquals(Decision.Included(102), decide(tx(1000), inBlock(102, "Success"), kept(1000), 1100, 200))
        assertEquals(
            Decision.Failed(103, "it ran in block 103 without succeeding: the program refused it"),
            decide(tx(1000), inBlock(103, "Revert"), kept(1000), 1100, 200),
        )
        // Until buried as deep as an inclusion, a failure is shown but not settled: a reorganization may undo it.
        val shallow = decide(tx(1000), inBlock(103, "Revert"), kept(1000), 1100, 104)
        assertEquals(Decision.Follow(Watch.Failed("it ran in block 103 without succeeding: the program refused it", 103)), shallow)
    }

    @Test
    fun `the last block that included it decides`() {
        val twice = lookup(occurrences = listOf(Occurrence(90, receipt("Revert")), Occurrence(95, receipt("Success"))))
        assertEquals(Decision.Included(95), decide(tx(1000), twice, kept(1000), 1100, 200))
    }

    @Test
    fun `waits while the node holds it, in the pool or for later`() {
        assertEquals(Decision.Follow(Watch.Waiting), decide(tx(1000), lookup(pending = true), kept(1000), 1100, 200))
        val later = lookup(deferred = Deferred(notBefore = 5000, expires = 9000, admitted = false, lastResult = "Revert"))
        assertEquals(Decision.Follow(Watch.Scheduled(5000, "Revert")), decide(tx(1000), later, kept(1000, 5000), 1100, 200))
        val due = lookup(pending = true, deferred = Deferred(notBefore = 1000, expires = 9000, admitted = true, lastResult = null))
        assertEquals(Decision.Follow(Watch.Waiting), decide(tx(1000), due, kept(1000, 1000), 1100, 200))
    }

    @Test
    fun `sends it again when the node lost it, while that makes sense`() {
        // Just sent: the node may not have it yet.
        assertEquals(Decision.Follow(Watch.Checking), decide(tx(1000), null, kept(1000), 1060, 200))
        assertEquals(Decision.Resend(listOf(kept(1000))), decide(tx(1000), null, kept(1000), 1200, 200))
        // A deferred one is sent again until a day past its time.
        assertEquals(Decision.Resend(listOf(kept(1000, 50_000))), decide(tx(1000), null, kept(1000, 50_000), 120_000, 200))
        // After a day, or without a copy, it is lost.
        assertEquals(Decision.Follow(Watch.Lost), decide(tx(1000), null, kept(1000), 1000 + 86_401, 0))
        assertEquals(Decision.Follow(Watch.Lost), decide(tx(1000), null, null, 1200, 200))
        // A node that knows it, but neither holds nor included it, lost it all the same.
        assertEquals(Decision.Resend(listOf(kept(1000))), decide(tx(1000), lookup(), kept(1000), 1200, 200))
    }

    @Test
    fun `waits for a few blocks on top before it counts as done`() {
        val included = inBlock(198, "Success")
        assertEquals(Decision.Follow(Watch.Confirming(198)), decide(tx(1000), included, kept(1000), 1100, 200))
        assertEquals(Decision.Included(198), decide(tx(1000), included, kept(1000), 1100, 198 + SETTLE_DEPTH))
    }

    @Test
    fun `counts a paid operation done when either the batch or the operation alone goes in`() {
        fun twin(l: TransactionLookup?, k: OutboxEntry? = kept(1000)) = Twin(l, k)
        val done = inBlock(150, "Success")
        val failed = inBlock(150, "Revert")
        // The operation went in alone; the batch then failed, as it should.
        assertEquals(Decision.Included(150), decide(tx(1000), failed, kept(1000), 1100, 200, twin(done)))
        // The batch failed, the operation alone still waits: so does the submission.
        assertEquals(Decision.Follow(Watch.Waiting), decide(tx(1000), failed, kept(1000), 1100, 200, twin(lookup(pending = true))))
        // Both failed: the batch's reason stands.
        val both = decide(tx(1000), failed, kept(1000), 1100, 200, twin(failed))
        assertEquals(150L, (both as Decision.Failed).height)
        // Both lost: both are sent again.
        assertEquals(Decision.Resend(listOf(kept(1000), kept(1001))), decide(tx(1000), null, kept(1000), 1200, 200, twin(null, kept(1001))))
    }

    @Test
    fun `counts what is on the way from one account`() {
        val items = listOf(
            PendingItem(tx(1, "c", "a", "10"), Watch.Waiting, true),
            PendingItem(tx(2, "c", "a", "5"), Watch.Resent, true),
            PendingItem(tx(3, "c", "a", "7"), Watch.Failed("x", null), false),
            PendingItem(tx(4, "c", "b", "9"), Watch.Waiting, true),
            PendingItem(tx(5), Watch.Waiting, true),
            // In a block already: the balance shows it.
            PendingItem(tx(6, "c", "a", "3"), Watch.Confirming(9), true),
        )
        assertEquals(OnTheWay(2, BigInteger.valueOf(15)), onTheWay(items, "c", "a"))
    }

    @Test
    fun `counts amounts beyond a Long`() {
        val big = "1" + "0".repeat(30)
        val items = listOf(PendingItem(tx(1, "c", "a", big), Watch.Waiting, true), PendingItem(tx(2, "c", "a", big), Watch.Checking, true))
        assertEquals(OnTheWay(2, BigInteger(big) * BigInteger.TWO), onTheWay(items, "c", "a"))
    }
}
