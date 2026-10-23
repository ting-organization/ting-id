// The fee an operation pays and which of the user's instant accounts pays it.
// The amount is automatic: the median that recent transactions paid per share
// of a block, times the share this operation takes (chain/FeeMarket), unless
// the user types one. Fees go in the fee coin (Settings), which miners accept,
// whatever coin the operation moves: the account acting pays when it is
// instant and holds enough, else Spending. An action of the paying account in
// the fee coin carries the fee itself; anything else goes in a batch with a
// payment bound to it, which takes more room and so pays more. When no
// instant account has enough of the fee coin, the fee falls back to the coin
// the operation moves, with no amount until the user enters one. A delayed
// account never pays.
//
// What is here is pure, for tests: the app keeps the choices the user makes
// and the balances it reads, and asks [quoteFee] for the rest (ui/money/Fee).
package app.tingid.state

import app.tingid.chain.FeeMarket
import app.tingid.chain.FeePayer
import app.tingid.chain.Signer
import app.tingid.chain.WHITE_SPACE
import app.tingid.chain.formatAmount
import app.tingid.chain.parseAmount
import java.math.BigInteger

/** The (i) text of every fee row. */
const val FEE_INFO =
    "Miners take transactions by what they pay for their room in a block, in the coins they accept. The fee is set from what recent transactions paid for the same room; change it here for this operation. It reaches the miner only together with the operation. Choose the coin in Settings."

/** The (i) text a fee row adds when the operation is scheduled. */
const val RUN_INFO =
    "A scheduled action pays again when it runs, for the room its run takes, in the coin it moves, to the miner that runs it; that part is reserved until then."

/**
 * The coin fees are paid in: the one chosen in Settings while it is followed,
 * else the chain's listed coin when followed, else the first followed coin.
 */
fun feeCoinOf(chosen: String?, coins: List<CoinInfo>, chainHex: String?): CoinInfo? {
    fun followed(h: String) = coins.firstOrNull { it.hex == h }
    if (chosen != null) followed(chosen)?.let { return it }
    for (k in if (chainHex != null) knownCoinsFor(chainHex) else emptyList()) followed(k.contract)?.let { return it }
    return coins.firstOrNull()
}

/** An amount typed for coin `c` in base units; null when it is not one. */
fun feeAmount(text: String, c: CoinInfo): BigInteger? = try {
    parseAmount(text.trim().ifEmpty { "0" }, c.config.decimals)
} catch (e: Exception) {
    null
}

/** The operation's own coin and account, when it has them (an identity operation has neither). */
class Acting(val coin: CoinInfo, val account: UserAccount)

class PayerOption(
    val account: UserAccount,
    val coin: CoinInfo,
    /** Available balance; null until read. */
    val available: BigInteger?,
) {
    /** Which option this is, across readings of the balances. */
    val key: String get() = "${account.slot}:${coin.hex}"
}

/** What a submission needs of its fee: the line it adds to the summary, and the payer to submit with. */
interface FeeSource {
    /** E.g. "Fee 0.0001 DLR from Spending", when there is a fee. */
    val summary: String?

    /** The payer to submit with: null for no fee; throws on a problem. */
    suspend fun resolve(): FeePayer?
}

/** Appends the fee, if any, to a summary of what was submitted. */
fun withFee(summary: String, fee: FeeSource): String = fee.summary?.let { "$summary · $it" } ?: summary

/** The accounts that may pay a fee: the instant ones. A delayed account never pays. */
fun instantAccounts(accounts: List<UserAccount>): List<UserAccount> = accounts.filter { it.delay == 0L }

/** The coins a fee may be paid in: the fee coin, and the coin the operation moves when that is another. */
fun feeCoins(feeCoin: CoinInfo?, actingCoin: CoinInfo?): List<CoinInfo> {
    val out = ArrayList<CoinInfo>()
    if (feeCoin != null) out.add(feeCoin)
    if (actingCoin != null && actingCoin.hex != feeCoin?.hex) out.add(actingCoin)
    return out
}

/**
 * Whether this device can sign for the user's accounts: a key user's key is
 * here, or a key here is one of the identity's.
 */
fun canSignFor(user: User?, info: ChainInfo?, keys: List<KeyMeta>, identity: IdentityInfo?): Boolean {
    if (user == null || info == null) return false
    if (user.kind == UserKind.Key) return keys.any { it.id == user.keyId }
    return identity != null && linkedDeviceKeys(info.canonical, identity, keys).isNotEmpty()
}

private fun rateOf(rates: List<FeeMarket.Rate>?, c: CoinInfo): Double? = FeeMarket.medianRate(rates ?: emptyList(), c.hex)

/** What the run of a scheduled action in coin `c` pays the miner that runs it, at `rates`. */
fun runTipOf(rates: List<FeeMarket.Rate>?, c: CoinInfo): BigInteger = FeeMarket.feeFor(rateOf(rates, c), FeeMarket.Share.Run.value)

/** The fee of an operation as it stands: what it pays, from which account, and what is wrong with it. */
class FeeQuote(
    val amount: String,
    /** Whether the amount is the automatic one, not typed. */
    val automatic: Boolean,
    /** The coin fees are paid in, and what an operation carrying its own fee pays in it now. */
    val feeCoin: CoinInfo?,
    val usual: BigInteger,
    /** Every instant account of the user, in the fee coin and in the operation's coin. */
    val options: List<PayerOption>,
    val payer: PayerOption?,
    /** Whether the options span two coins. */
    val multiCoin: Boolean,
    /** The fee in the payer's coin, base units; 0 for none. */
    val fee: BigInteger,
    /** The fee goes inside the acting account's own action: it pays in the coin it moves. */
    val own: Boolean,
    /** The fee goes in a batch with the operation: a second signature, about 60% more room in a block. */
    val bundled: Boolean,
    val problem: String?,
    /** Why the fee is not in the fee coin, when it fell back. */
    val notice: String?,
    /** E.g. "Fee 0.0001 DLR from Spending", when there is a fee. */
    val summary: String?,
    /** Whether this device can sign for a paying account. */
    val canPay: Boolean,
    private val rates: List<FeeMarket.Rate>?,
) {
    /** What the run of a scheduled action in coin `c` pays the miner that runs it. */
    fun runTip(c: CoinInfo): BigInteger = runTipOf(rates, c)
}

/**
 * The fee of an operation from what it depends on: the fee coin, the rates
 * recent transactions paid (null until read), the operation's coin and
 * account, the user's accounts, the balances read of the paying candidates
 * (by coin hex, as [CoinData.accountViews] gives them for [feeCoins] and
 * [instantAccounts]), whether this device can sign for them ([canSignFor]),
 * and what the user chose: a paying option ([PayerOption.key]) and an amount.
 */
fun quoteFee(
    feeCoin: CoinInfo?,
    rates: List<FeeMarket.Rate>?,
    acting: Acting?,
    accounts: List<UserAccount>,
    views: Map<String, List<AccountView>>,
    canSign: Boolean,
    choice: String?,
    typed: String?,
): FeeQuote {
    val instant = instantAccounts(accounts)
    val actingCoin = acting?.coin
    val coins = feeCoins(feeCoin, actingCoin)

    // Balances of the paying candidates, the same ones the wallet shows.
    fun availableOf(a: UserAccount, c: CoinInfo): BigInteger? = views[c.hex]?.firstOrNull { it.account.slot == a.slot }?.record?.available

    val options = instant.flatMap { a -> coins.map { c -> PayerOption(a, c, availableOf(a, c)) } }
    // What the payer needs at most: an operation with its fee in a batch beside it.
    val usual = if (feeCoin != null) FeeMarket.feeFor(rateOf(rates, feeCoin), FeeMarket.Share.Own.value) else BigInteger.ZERO
    val most = if (feeCoin != null) FeeMarket.feeFor(rateOf(rates, feeCoin), FeeMarket.Share.Bundled.value) else BigInteger.ZERO
    val spending = instant.firstOrNull { it.slot == 0L } ?: instant.firstOrNull()
    val picked = defaultPayer(
        options.map { PayerCandidate(it.account.slot, it.coin.hex, it.available) },
        PayerContext(
            feeCoin = feeCoin?.hex,
            actingCoin = actingCoin?.hex,
            actingSlot = if (acting != null && acting.account.delay == 0L) acting.account.slot else null,
            spendingSlot = spending?.slot,
            usual = most,
        ),
    )
    val payer = choice?.let { k -> options.firstOrNull { it.key == k } } ?: options.getOrNull(picked.index)
    val inUsualCoin = payer != null && feeCoin != null && payer.coin.hex == feeCoin.hex
    // No instant account holds the usual fee in the fee coin: no fee unless the user enters one.
    val short = picked.short
    val notice = if (short && choice == null && payer != null) {
        if (inUsualCoin) "${payer.account.label} has too little ${feeCoin.config.symbol} for the usual fee."
        else "${spending?.label ?: "No account"} has too little ${feeCoin!!.config.symbol} for the fee; it can be paid in ${payer.coin.config.symbol} instead."
    } else {
        null
    }

    val canPay = canSign && payer != null
    val own = acting != null && payer != null && acting.account.delay == 0L && payer.coin.hex == acting.coin.hex && payer.account.slot == acting.account.slot
    val decimals = payer?.coin?.config?.decimals ?: 0
    val symbol = payer?.coin?.config?.symbol ?: ""
    // Automatic in the fee coin; a fallback payer in another coin pays what the person enters. A
    // phone that signs for no account pays none: an operation anyone may submit, such as finishing
    // a recovery, then goes unpaid.
    val automatic = if (canPay && inUsualCoin && (!short || choice != null)) {
        FeeMarket.feeFor(rateOf(rates, payer.coin), if (own) FeeMarket.Share.Own.value else FeeMarket.Share.Bundled.value)
    } else {
        BigInteger.ZERO
    }
    val amount = typed ?: if (automatic.signum() > 0) formatAmount(automatic, decimals).replace(Regex("[$WHITE_SPACE]"), "") else ""
    val parsed = if (payer != null) feeAmount(amount, payer.coin) else BigInteger.ZERO
    val fee = parsed ?: BigInteger.ZERO
    val available = payer?.available
    val problem = when {
        parsed == null -> "enter a fee with at most $decimals decimals"
        fee.signum() > 0 && !canPay -> "no account here can pay a fee"
        fee.signum() > 0 && payer != null && available != null && fee > available ->
            "the fee needs ${formatAmount(fee, decimals)} $symbol; ${payer.account.label} has ${formatAmount(available, decimals)}"
        else -> null
    }
    val summary = if (fee.signum() > 0 && payer != null) "Fee ${formatAmount(fee, decimals)} $symbol from ${payer.account.label}" else null

    return FeeQuote(
        amount = amount,
        automatic = typed == null,
        feeCoin = feeCoin,
        usual = usual,
        options = options,
        payer = payer,
        multiCoin = coins.size > 1,
        fee = fee,
        own = own,
        bundled = fee.signum() > 0 && !own,
        problem = problem,
        notice = notice,
        summary = summary,
        canPay = canPay,
        rates = rates,
    )
}

/**
 * The payer to submit `quote` with: null for no fee; throws on a problem.
 * `signer` finds what signs for a slot of the user (Chain.resolveSigner).
 */
fun payerOf(quote: FeeQuote, signer: (slot: Long) -> Signer): FeePayer? {
    if (quote.fee.signum() == 0) return null
    quote.problem?.let { throw IllegalStateException(it) }
    val payer = quote.payer ?: throw IllegalStateException("no account here can pay a fee")
    return FeePayer(signer(payer.account.slot), payer.coin.id, quote.fee)
}

/**
 * The run fee of an action from a time-locked account, such as a mint: what
 * whoever runs it when due is paid for the run's room, in the action's coin
 * from the acting account (wiki: programs/coin). An account holding less of
 * that coin than the tip and any fee its action carries pays none. `view` is
 * the account's in coin `c`; `tip` is [FeeQuote.runTip] of `c`.
 */
fun runFeeOf(c: CoinInfo?, account: UserAccount?, view: AccountView?, tip: BigInteger, ownFee: BigInteger): BigInteger {
    if (c == null || account == null || account.delay == 0L) return BigInteger.ZERO
    return if (view != null && view.record.available >= tip + ownFee) tip else BigInteger.ZERO
}
