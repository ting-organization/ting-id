// Coins the app follows by default, per chain. There is no on-chain index of
// standard-coin deployments, so the ones a wallet should open on are listed
// here; users add others from Settings, and the app also notices standard
// coins their accounts already hold.
package app.tingid.state

/** The marks the app draws for listed coins. */
enum class CoinIcon { Daler }

class KnownCoin(
    val contract: String,
    val name: String,
    /** What its configuration must say: a node that gives other decimals or another symbol is not believed. */
    val decimals: Int? = null,
    val symbol: String? = null,
    /** Its mark; without one, the wallet shows the symbol in a ring. */
    val icon: CoinIcon? = null,
)

val KNOWN_COINS: Map<String, List<KnownCoin>> = mapOf(
    // The production network.
    "15d6549cfacd816f835c632b4c67308d1c63e642f4cbf4049368430670705107" to listOf(
        KnownCoin("305378d17f6af84a563d0f8a8455084948aff7330b62b4f2f98a44b82ba113c4", "Daler", icon = CoinIcon.Daler, decimals = 8, symbol = "DLR"),
    ),
    // The development chain (`ting init --dev`): production's Daler deployment
    // (height 443) submitted again there, which gives the same coin every time.
    "8578787146be1ef0aacc797754001290272915e89cd9e5784fe1368e4c2131bc" to listOf(
        KnownCoin("851dc8161155b1cfdab1554b587be613741b89e22b48232338fc36d0ad856140", "Daler", icon = CoinIcon.Daler, decimals = 8, symbol = "DLR"),
    ),
)

/** The listed coin with contract `coinHex`, on any chain. */
fun knownCoin(coinHex: String): KnownCoin? {
    for (coins in KNOWN_COINS.values) for (k in coins) if (k.contract == coinHex) return k
    return null
}

fun knownCoinsFor(chainHex: String): List<KnownCoin> = KNOWN_COINS[chainHex] ?: emptyList()
