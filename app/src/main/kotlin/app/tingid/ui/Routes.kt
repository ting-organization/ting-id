// Where the app can go: its tabs, and the screens that open over them. Every
// route is a type, so a screen gets its parameters typed and checked.
package app.tingid.ui

import kotlinx.serialization.Serializable

/** The tabs along the bottom. */
@Serializable
data object WalletTab

/** `code`: a pay link to fill the payment from; `to`, `coin`, `slot`: a payment to start. `at` tells a link opened again from the one already there. */
@Serializable
data class SendTab(val code: String? = null, val to: String? = null, val coin: String? = null, val slot: Long? = null, val at: Long = 0)

/** Only with the coin tools switched on in Settings. */
@Serializable
data object CoinsTab

@Serializable
data object IdentityTab

@Serializable
data object PeopleTab

@Serializable
data object SettingsTab

/** A followed coin and the user's account in it. */
@Serializable
data class CoinRoute(val id: String, val slot: Long? = null)

/** A coin as the coin tools show it. */
@Serializable
data class CoinToolsRoute(val id: String)

@Serializable
data object NewCoinRoute

@Serializable
data class MintRoute(val id: String)

@Serializable
data class MinterRoute(val id: String)

/** `mode`: "new" or "restore". */
@Serializable
data class NewUserRoute(val mode: String? = null)

@Serializable
data object ReviewRoute

/** Setting up someone else's identity from a create link. */
@Serializable
data class SponsorRoute(val code: String? = null, val at: Long = 0)

/** `sent`: just submitted; `unsure`: its answer was lost; `back`: a page the call link offers to go back to (TCR-1.md). */
@Serializable
data class TxRoute(val id: String, val sent: Boolean = false, val unsure: Boolean = false, val back: String? = null)

/** `kind`: what change to start with; `keyId`, `id`, `slot`, `key`: what it is about; `code`: a device link. */
@Serializable
data class ProposeRoute(
    val kind: String? = null,
    val keyId: String? = null,
    val id: String? = null,
    val slot: Long? = null,
    val key: String? = null,
    val code: String? = null,
    val at: Long = 0,
)

/** `mode`: "help" (a guardian helps another) or "mine"; `code`: a recovery link. */
@Serializable
data class RecoveryRoute(val mode: String? = null, val code: String? = null, val at: Long = 0)

/** A site's signing request, from its sign code. */
@Serializable
data class SignRoute(val code: String? = null, val at: Long = 0)

/** A program's call (TCR-1.md), from its call link. */
@Serializable
data class CallRoute(val code: String? = null, val at: Long = 0)

/** A user's recovery phrase. `next`: "add" once the words are written down. */
@Serializable
data class PhraseRoute(val id: String, val next: String? = null)
