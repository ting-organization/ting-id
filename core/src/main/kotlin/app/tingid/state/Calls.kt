// A program's call request (TCR-1.md), checked against the chain before
// anything of it shows: the program is on chain and not one of Ting's own,
// its published list holds the link's template, so no sentence the program
// does not have is ever shown for signing, and the payment's coin runs the
// coin program.
package app.tingid.state

import app.tingid.chain.CallError
import app.tingid.chain.ChainApi
import app.tingid.chain.Coin
import app.tingid.chain.ContractName
import app.tingid.chain.Contracts
import app.tingid.chain.Link
import app.tingid.chain.Links
import app.tingid.chain.StorageRead
import app.tingid.chain.Tcr
import app.tingid.chain.parseAmount
import app.tingid.chain.unhex
import java.math.BigInteger

/**
 * A call link, checked: what it asks of which program. Its coins are read for
 * their decimals only; a coin goes by name only when the user follows it
 * (TCR-1.md, field types), as a coin's own configuration names it however its
 * deployer likes.
 */
class CallRequest(
    val link: Link.Call,
    val program: ByteArray,
    /** The program's CodeHash: what a reviewed list knows it by. */
    val codeHash: String,
    /** What the call pays, when it pays. */
    val payment: CallPayment?,
    /** The coin the program takes fee payments in, when the link names one and the call pays nothing. */
    val feeCoin: CoinInfo?,
)

/** A call's payment, in a coin this app reads. */
class CallPayment(val coin: CoinInfo, val effect: Coin.Effect) {
    /** What leaves the paying account, before any fee. */
    val amount: BigInteger get() = effect.debit

    /** The account paid; null for a burn. */
    val to: ByteArray? get() = (effect as? Coin.Effect.Transfer)?.to
}

/** Reads `link` against the chain `chainId`; throws [CallError] saying why it cannot be signed. */
suspend fun readCallRequest(node: ChainApi, store: Store, link: Link.Call, chainId: ByteArray): CallRequest {
    if (link.net != Links.netOf(chainId)) throw CallError("this link is for another Ting network than the one this app uses")
    val program = unhex(link.program)
    val manifest = node.contract(program) ?: throw CallError("the program is not on chain")
    if (ContractName.entries.any { Contracts.codeHash(it) == manifest.codeHash }) {
        throw CallError("the link calls one of Ting’s own programs, which Ting ID handles on its own screens")
    }
    val list = node.storageFixed(listOf(StorageRead(program, Tcr.TEMPLATES_KEY)))[0]
        ?: throw CallError("the program publishes no list of its operations, so Ting ID cannot check this one")
    val templates = Tcr.decodeTemplateList(list) ?: throw CallError("the program’s list of operations is not written right")
    Tcr.listProblem(templates, link.template)?.let { throw CallError(it) }
    val coinInfo = link.coin?.let { coin ->
        if (!isStandardCoin(node, coin)) throw CallError("the link’s coin is a contract that is not a coin")
        fetchCoin(node, store, coin) ?: throw CallError("the link’s coin is not on chain")
    }
    val payment = coinInfo?.takeIf { link.pays }?.let { info ->
        val decimals = info.config.decimals
        fun units(text: String): BigInteger = try {
            parseAmount(text, decimals)
        } catch (e: Exception) {
            throw CallError("the payment’s amount has more than $decimals decimals")
        }
        val effect = if (link.burn != null) Coin.Effect.Burn(units(link.burn)) else Coin.Effect.Transfer(unhex(link.to!!), units(link.amount!!))
        CallPayment(info, effect)
    }
    return CallRequest(link, program, manifest.codeHash, payment, coinInfo?.takeIf { !link.pays })
}
