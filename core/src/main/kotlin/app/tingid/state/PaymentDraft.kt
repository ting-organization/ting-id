// The payment the Send screen hands its review page: what the page shows,
// and the submit that sends it. Send stays open beneath the page, so the
// submit it made still holds its accounts, fee and signer.
package app.tingid.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.math.BigInteger

class PaymentDraft(
    val coin: CoinInfo,
    val amount: BigInteger,
    val from: From,
    val to: To,
    /** When it runs: null as soon as a block includes it; else the later of the chosen time and the end of the account's lock. */
    val runsAt: Long?,
    /** The chosen time, for a payment set for later. */
    val chosenAt: Long?,
    /** How long the sending account's withdrawals wait, 0 for an instant one. */
    val lock: Long,
    /** E.g. "Fee 0.0001 DLR from Spending"; null for none. */
    val fee: String?,
    /** That little or nothing is left for later fees, when so. */
    val feeWarning: String?,
    /** What a scheduled payment pays its runner, in the coin sent, reserved until it runs. */
    val runFee: BigInteger,
    /** What leaves the sending account in the coin sent: the amount, a fee it pays itself and the run fee. */
    val total: BigInteger,
    /** What the sending account has available before it. */
    val available: BigInteger,
    /** Sends it; gives the transaction's id, `unsure` when the node's answer was lost. */
    val submit: suspend () -> Sent,
) {
    /** The sending account: its label and the account, hex. */
    class From(val label: String, val account: String)

    /** The receiving account, hex. */
    class To(val account: String)
}

/** Holds the draft between Send and Review. */
class PaymentDrafts {
    private val _draft = MutableStateFlow<PaymentDraft?>(null)
    val draft: StateFlow<PaymentDraft?> = _draft.asStateFlow()

    fun setDraft(draft: PaymentDraft?) {
        _draft.value = draft
    }
}

/** The one holder for the app: Send and Review are one of each, and the draft lives only in memory. */
val paymentDrafts = PaymentDrafts()
