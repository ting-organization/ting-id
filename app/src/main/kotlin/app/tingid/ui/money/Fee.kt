// The fee of an operation on a screen: what state/Fees works out from the
// workspace, the fee rates and the balances, with the choices the person
// makes (the amount, the paying account) kept by the screen. Also the
// balances every money screen reads, and the run fee of a scheduled action.
package app.tingid.ui.money

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import app.tingid.chain.FeePayer
import app.tingid.chain.Signer
import app.tingid.chain.hex
import app.tingid.state.Acting
import app.tingid.state.AccountViews
import app.tingid.state.CoinInfo
import app.tingid.state.FeeQuote
import app.tingid.state.FeeSource
import app.tingid.state.PayerOption
import app.tingid.state.UserAccount
import app.tingid.state.WorkspaceState
import app.tingid.state.canSignFor
import app.tingid.state.feeCoinOf
import app.tingid.state.feeCoins
import app.tingid.state.instantAccounts
import app.tingid.state.payerOf
import app.tingid.state.quoteFee
import app.tingid.state.runFeeOf
import app.tingid.ui.LocalServices
import java.math.BigInteger

/**
 * The fee of one operation and the person's choices about it, as the screen
 * shows it now; [rememberFee] makes a new one each time anything it depends
 * on changes. Read it where it is used, not once: the paying account, the
 * amount and the balances move.
 */
class FeeChoice internal constructor(
    private val quote: FeeQuote,
    private val typed: MutableState<String?>,
    private val choice: MutableState<String?>,
    private val signer: (slot: Long) -> Signer,
) : FeeSource {
    /** The amount as typed, or the automatic one, in the payer's display units. */
    val amount: String get() = quote.amount

    fun setAmount(text: String) {
        typed.value = text
    }

    /** Whether the amount is the automatic one, not typed. */
    val automatic: Boolean get() = quote.automatic

    /** Goes back to the automatic amount. */
    fun useAutomatic() {
        typed.value = null
    }

    /** The coin fees are paid in. */
    val feeCoin: CoinInfo? get() = quote.feeCoin

    /** What an operation carrying its own fee pays in the fee coin now. */
    val usual: BigInteger get() = quote.usual

    /** What the run of a scheduled action in coin `c` pays the miner that runs it. */
    fun runTip(c: CoinInfo): BigInteger = quote.runTip(c)

    /** Every instant account of the user, in the fee coin and in the operation's coin. */
    val options: List<PayerOption> get() = quote.options

    /** The account that pays, one of [options]; null when none can. */
    val payer: PayerOption? get() = quote.payer

    fun choose(option: PayerOption) {
        // An amount typed for another coin means nothing in this one.
        if (option.coin.hex != quote.payer?.coin?.hex) typed.value = null
        choice.value = option.key
    }

    /** Whether the options span two coins. */
    val multiCoin: Boolean get() = quote.multiCoin

    /** The fee in the payer's coin, base units; 0 for none. */
    val fee: BigInteger get() = quote.fee

    /** The fee goes inside the acting account's own action: it pays in the coin it moves. */
    val own: Boolean get() = quote.own

    /** The fee goes in a batch with the operation: a second signature, about 60% more room in a block. */
    val bundled: Boolean get() = quote.bundled

    /** What is wrong with the fee, as a message to show; null when nothing is. */
    val problem: String? get() = quote.problem

    /** Why the fee is not in the fee coin, when it fell back. */
    val notice: String? get() = quote.notice

    /** E.g. "Fee 0.0001 DLR from Spending", when there is a fee. */
    override val summary: String? get() = quote.summary

    /** Whether this device can sign for a paying account. */
    val canPay: Boolean get() = quote.canPay

    /** The payer to submit with: null for no fee; throws on a problem. */
    override suspend fun resolve(): FeePayer? = payerOf(quote, signer)
}

/** Appends the fee, if any, to a summary of what was submitted. */
fun withFee(summary: String, fee: FeeChoice): String = app.tingid.state.withFee(summary, fee)

/**
 * Balances and scheduled actions of `accounts` in `coins`, from the cache and
 * read again with each block: by coin hex, and whether
 * some are not known yet. While other coins or accounts load, the last ones
 * read stay.
 */
@Composable
fun rememberAccountViews(coins: List<CoinInfo>, accounts: List<UserAccount>): AccountViews {
    val services = LocalServices.current
    // The workspace makes its coins and accounts anew with every block: they are told apart by what
    // the reads depend on. A coin's minter has its scheduled actions read back to the first.
    val key = coins.map { "${it.hex}:${it.state.minter.hex()}" } to accounts.map { "${it.slot}:${it.account.hex()}:${it.delay}:${it.label}" }
    val views = remember(key) { services.coins.accountViews(coins, accounts) }
    return views.collectAsState(initial = AccountViews(emptyMap(), true)).value
}

/** The coin fees are paid in: the one chosen in Settings while followed, else the chain's listed coin, else the first followed. */
@Composable
fun rememberFeeCoin(w: WorkspaceState): CoinInfo? {
    val chosen = LocalServices.current.store.data.collectAsState().value.settings.feeCoin
    return feeCoinOf(chosen, w.coins, w.info?.chainHex)
}

/**
 * The fee of an operation acting as `acting` (its coin and account; null
 * for an identity operation, which has neither), from the workspace, the
 * rates recent transactions paid and the paying accounts' balances. The
 * amount typed and the account chosen stay while the screen does.
 */
@Composable
fun rememberFee(acting: Acting?): FeeChoice {
    val services = LocalServices.current
    val w by services.workspace.state.collectAsState()
    val feeCoin = rememberFeeCoin(w)
    val rates by services.feeRates.rates.collectAsState(initial = null)
    val typed = remember { mutableStateOf<String?>(null) }
    val choice = remember { mutableStateOf<String?>(null) }
    val instant = instantAccounts(w.accounts)
    // Balances of the paying candidates, the same ones the wallet shows.
    val views = rememberAccountViews(feeCoins(feeCoin, acting?.coin), instant).views
    val quote = quoteFee(feeCoin, rates, acting, w.accounts, views, canSignFor(w.user, w.info, w.keys, w.identity), choice.value, typed.value)
    val state = w
    return FeeChoice(quote, typed, choice) { slot ->
        val user = state.user ?: throw IllegalStateException("no account here can pay a fee")
        val info = state.info ?: throw IllegalStateException("no account here can pay a fee")
        services.chain.resolveSigner(user, slot, info.canonical, state.keys, state.identity)
    }
}

/**
 * The run fee of an action from a time-locked account, such as a mint: what
 * whoever runs it when due is paid for the run's room, in the action's coin
 * from the acting account (wiki: programs/coin). An account holding less of
 * that coin pays none; an instant account's action runs at once and pays none.
 */
@Composable
fun rememberRunFee(fee: FeeChoice, coin: CoinInfo?, account: UserAccount?): BigInteger {
    val views = rememberAccountViews(listOfNotNull(coin), listOfNotNull(account)).views
    val view = coin?.let { views[it.hex]?.firstOrNull() }
    val tip = if (coin != null) fee.runTip(coin) else BigInteger.ZERO
    return runFeeOf(coin, account, view, tip, if (fee.own) fee.fee else BigInteger.ZERO)
}
