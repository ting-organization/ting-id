// What the app keeps about its users, keys, contacts and transactions. Byte
// strings are kept as hex, as they are shown and compared as such. Key
// secrets and recovery phrases are never here: they are in the vault.
package app.tingid.state

import app.tingid.chain.Scheme
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A signing key held on this device; its secret lives in the vault. */
@Serializable
data class KeyMeta(
    val id: String,
    val name: String,
    val scheme: Scheme,
    /** Uncompressed SEC1 public key, hex. */
    val pub: String,
    val createdAt: Long,
)

@Serializable
enum class UserKind {
    @SerialName("identity")
    Identity,

    @SerialName("key")
    Key,
}

/** How the phone keeps a user's recovery phrase (app: vault). */
@Serializable
enum class PhraseLock {
    /** Behind the owner's fingerprint, face or screen lock, which the keystore asks for each use. */
    @SerialName("owner")
    Owner,

    /** The phone has no screen lock: anyone holding it can see the words. */
    @SerialName("sealed")
    Sealed,
}

/**
 * A user of this device: an identity (wiki: programs/identity) with its slots,
 * or a single key acting as its own account (a policy account).
 */
@Serializable
data class User(
    val id: String,
    val name: String,
    val color: String,
    val kind: UserKind,
    /** Identity users: the id, its registry and chain, and the creation input (hex) when it was made here. */
    val identityId: String? = null,
    val registry: String? = null,
    val chainId: String? = null,
    val creation: String? = null,
    /**
     * The key this device uses for the user: the account itself for key users;
     * for identities, the key this device made for it (one of its keys, or the
     * one waiting to be added by another device or a recovery).
     */
    val keyId: String? = null,
    /** How this phone keeps the user's recovery phrase; absent when it keeps none. */
    val phrase: PhraseLock? = null,
    /** The key the recovery phrase gives (a KeyMeta id), which the identity lists or a change will add. */
    val phraseKeyId: String? = null,
    /** The person confirmed the words by typing some of them back. */
    val phraseBackedUp: Boolean = false,
    /** New words were made to be added to the identity by a change that is not proposed yet. */
    val phraseToAdd: Boolean = false,
    /** Guardians this phone proposed for the identity (IDs, hex), so it can name and unlink them later. */
    val knownGuardians: List<String> = emptyList(),
)

@Serializable
data class Contact(
    val id: String,
    val name: String,
    /** 32-byte account, hex. */
    val account: String,
    /** The chain it was saved on (hex): an account is only meaningful on its own chain. */
    val chain: String? = null,
    /** The identity (hex ID) whose Spending account `account` is, when known, as for a guardian: the name is its name too. */
    val identity: String? = null,
)

@Serializable
enum class Theme {
    @SerialName("system")
    System,

    @SerialName("light")
    Light,

    @SerialName("dark")
    Dark,
}

@Serializable
data class Settings(
    /** The nodes the app uses, as one (NodePool), in the user's order. */
    val nodeUrls: List<String>,
    /** Coins the user follows, hex contract ids, in display order. None is special. */
    val coins: List<String> = emptyList(),
    /** Discovered coins the user chose not to follow. */
    val dismissed: List<String> = emptyList(),
    /** Shows keys, signature schemes and other technical detail. Off for most people. */
    val advanced: Boolean = false,
    /** The coin tools: every coin on the chain, creating coins and minting. Off unless switched on. */
    val coinTools: Boolean = false,
    /**
     * The coin fees are paid in (hex contract id), while followed; null for the
     * chain's listed coin. Miners rank transactions by what they pay in coins
     * they accept, so one widely accepted coin pays for operations in any coin.
     */
    val feeCoin: String? = null,
    /** Light or dark, or as the system is set. */
    val theme: Theme = Theme.System,
    /** The phone checks its owner when Ting ID opens or comes back after a while. */
    val unlockApp: Boolean = true,
    /** The phone checks its owner before a payment goes. */
    val confirmPayments: Boolean = true,
)

@Serializable
enum class TxState {
    /** No block has included it. */
    @SerialName("pending")
    Pending,

    /** A block has. */
    @SerialName("included")
    Included,

    /** It ran in a block without succeeding, or the node refused it again. */
    @SerialName("failed")
    Failed,

    /** The user put it away. */
    @SerialName("dismissed")
    Dismissed,
}

/** A transaction this device submitted: what it does, and what became of it. */
@Serializable
data class SubmittedTx(
    val id: String,
    val at: Long,
    val summary: String,
    val userId: String,
    /** The chain it was sent to (hex), so it is only followed there. */
    val chain: String? = null,
    /** Followed until settled. */
    val state: TxState = TxState.Pending,
    /** The block that included it. */
    val height: Long? = null,
    /** Why it failed. */
    val problem: String? = null,
    /** For a payment: the coin, the paying account (hex) and the amount in base units, for the account's "on the way". */
    val coin: String? = null,
    val account: String? = null,
    val amount: String? = null,
    /** For a payment: the account it goes to (hex). */
    val to: String? = null,
)

/** A submitted transaction's bytes, kept until a block includes it, to send again if the node loses it. */
@Serializable
data class OutboxEntry(
    val raw: String,
    val notBefore: Long? = null,
    val at: Long,
    /** For a batch that pays for an operation: the operation sent alone, whichever of the two goes in. */
    val twin: String? = null,
)

@Serializable
enum class SignatureKind {
    @SerialName("signIn")
    SignIn,

    @SerialName("grant")
    Grant,

    @SerialName("statement")
    Statement,
}

@Serializable
enum class SignatureOutcome {
    @SerialName("signed")
    Signed,

    @SerialName("declined")
    Declined,

    @SerialName("failed")
    Failed,
}

/** A site's signing request (TOCS-1) this device answered, kept so the user can see what they signed. */
@Serializable
data class SignatureNote(
    val at: Long,
    val userId: String,
    val origin: String,
    val kind: SignatureKind,
    val title: String,
    val outcome: SignatureOutcome,
    /** What the site answered, or why it failed. */
    val message: String? = null,
)

/**
 * The last action this device signed for a coin account: its nonce and
 * transaction, when it was signed, and for a fee payment bound to a batch the
 * prefix it commits to.
 */
@Serializable
data class NonceNote(
    val nonce: Long,
    val txId: String,
    /** Seconds. */
    val at: Long,
    val prefix: String? = null,
)

/**
 * Recovery phrase words in the vault that belong to no user any more:
 * deleting them failed, or the saved state that listed their user could not
 * be read. They are kept by id so that deleting can be tried again.
 */
@Serializable
data class StrayPhrase(val id: String, val name: String, val phrase: PhraseLock)

/** Everything the app saves, in one piece. */
@Serializable
data class AppData(
    val settings: Settings,
    val keys: List<KeyMeta> = emptyList(),
    val users: List<User> = emptyList(),
    val contacts: List<Contact> = emptyList(),
    val submitted: List<SubmittedTx> = emptyList(),
    val activeUserId: String? = null,
    val activeSlot: Int = 0,
    /** The coin the send screen last used; a convenience, not a preference. */
    val activeCoin: String? = null,
    /**
     * The last action signed per coin account (`coin:account`), so that the
     * next one takes the following nonce while it still waits for a block.
     */
    val nonces: Map<String, NonceNote> = emptyMap(),
    /** Each coin's configuration as first read (coin hex → config bytes, hex): it never changes, so a node giving another is refused. */
    val coinConfigs: Map<String, String> = emptyMap(),
    /** Signing requests of sites this device answered, newest first. */
    val signatures: List<SignatureNote> = emptyList(),
    /** The bytes of submitted transactions no block has included yet, by id. */
    val outbox: Map<String, OutboxEntry> = emptyMap(),
    val strayPhrases: List<StrayPhrase> = emptyList(),
) {
    /** The active user, or the first, or none. */
    val activeUser: User? get() = users.firstOrNull { it.id == activeUserId } ?: users.firstOrNull()
}
