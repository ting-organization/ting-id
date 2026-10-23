// Which instant account pays an operation's fee unless the user picks one
// (state/fees): in the fee coin, the acting account first, then Spending, then
// the others, the first holding the usual fee; when none does, the acting
// account or Spending in the coin the operation moves.
package app.tingid.state

import java.math.BigInteger

data class PayerCandidate(
    val slot: Long,
    /** The coin, hex. */
    val coin: String,
    /** Available balance; null until read, which counts as enough. */
    val available: BigInteger?,
)

data class PayerContext(
    val feeCoin: String?,
    val actingCoin: String?,
    /** The acting account's slot when it is instant; null otherwise. */
    val actingSlot: Long?,
    val spendingSlot: Long?,
    /** The usual fee in the fee coin. */
    val usual: BigInteger,
)

/** The default payer's index in the options, -1 for none, and whether no account holds the usual fee in the fee coin. */
data class DefaultPayer(val index: Int, val short: Boolean)

fun defaultPayer(options: List<PayerCandidate>, ctx: PayerContext): DefaultPayer {
    fun order(slot: Long) = if (slot == ctx.actingSlot) 0 else if (slot == ctx.spendingSlot) 1 else 2
    fun ranked(coin: String?) = options.withIndex()
        .filter { (_, o) -> coin != null && o.coin == coin }
        .sortedWith(compareBy<IndexedValue<PayerCandidate>> { order(it.value.slot) }.thenBy { it.value.slot })
    val inFeeCoin = ranked(ctx.feeCoin)
    val enough = inFeeCoin.firstOrNull { (_, o) -> o.available == null || o.available >= ctx.usual }
    if (enough != null) return DefaultPayer(enough.index, false)
    val short = ctx.feeCoin != null && ctx.usual.signum() > 0
    val fallback = ranked(ctx.actingCoin).firstOrNull() ?: inFeeCoin.firstOrNull()
    return DefaultPayer(fallback?.index ?: -1, short)
}
