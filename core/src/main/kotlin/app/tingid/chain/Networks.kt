// What the app knows of each network beyond what its nodes say, by chain ID
// (hex): the most a block's proof-of-work target may be (wiki:
// consensus/genesis), so that a header without that much work is refused.
package app.tingid.chain

class Network(val name: String, /** The proof-of-work limit, 32 bytes big-endian in hex. */ val powLimit: String)

object Networks {
    /** The production network: 2^228 - 1. */
    const val PRODUCTION = "15d6549cfacd816f835c632b4c67308d1c63e642f4cbf4049368430670705107"

    val known: Map<String, Network> = mapOf(
        PRODUCTION to Network("Ting", "0000000" + "f".repeat(57)),
    )

    /** The proof-of-work limit of chain `chainHex`; none for a chain this app does not know, such as a development chain. */
    fun powLimitOf(chainHex: String?): String? = chainHex?.let { known[it]?.powLimit }
}
