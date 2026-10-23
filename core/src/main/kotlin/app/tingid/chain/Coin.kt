// The coin reference program (wiki: programs/coin): records, actions, cancels,
// messages and events.
@file:UseSerializers(HexSerializer::class, BigIntegerSerializer::class)

package app.tingid.chain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

import java.math.BigInteger

object Coin {
    const val APP_TAG = "app/coin-v1"
    const val VERSION = 1
    val CONFIG_KEY: ByteArray get() = filled(0)
    val STATE_KEY: ByteArray get() = filled(0).also { it[31] = 1 }
    val ZERO_ACCOUNT: ByteArray get() = filled(0)
    val UNCAPPED: BigInteger = U128_MAX

    const val OP_BALANCE = 0x01
    const val OP_INFO = 0x02
    const val OP_CLAIM = 0x03
    const val OP_RUN = 0x04
    const val OP_SCHEDULED = 0x05
    const val OP_EXECUTE = 0x10
    const val OP_CANCEL = 0x11

    private const val AUTH_POLICY = 0
    private const val AUTH_CONTRACT = 1
    private const val ACT_TRANSFER = 1
    private const val ACT_BURN = 2
    private const val ACT_MINT = 3
    private const val ACT_SET_MINTER = 4
    private const val WITHIN_NONE = 0
    private const val WITHIN_BATCH = 1

    val TRANSFER_TOPIC: ByteArray = taggedHash("app/coin-v1/event/transfer")
    val MINTER_TOPIC: ByteArray = taggedHash("app/coin-v1/event/minter")
    val SCHEDULED_TOPIC: ByteArray = taggedHash("app/coin-v1/event/scheduled")
    val EXECUTED_TOPIC: ByteArray = taggedHash("app/coin-v1/event/executed")
    val CANCELLED_TOPIC: ByteArray = taggedHash("app/coin-v1/event/cancelled")
    val PRODUCER_PAYMENT_TOPIC: ByteArray = taggedHash("app/event/producer-payment-v1")

    fun isReserved(key: ByteArray): Boolean = equal(key, CONFIG_KEY) || equal(key, STATE_KEY)

    fun scheduledKey(account: ByteArray, sequence: Long): ByteArray =
        taggedHash("app/coin-v1/scheduled", account, u64le(sequence))

    // ---- Deployment -------------------------------------------------------------

    /** A new coin's options, fixed at deployment except the minter. */
    class Options(
        val name: String,
        val symbol: String,
        val decimals: Int,
        /** [UNCAPPED] for no cap. */
        val maxSupply: BigInteger,
        val initialReward: BigInteger,
        /** Blocks per era; 0 for a constant reward. */
        val halvingInterval: Long,
        val tailReward: BigInteger,
        /** [ZERO_ACCOUNT] for none. */
        val minter: ByteArray,
        val allocations: List<Allocation>,
    )

    class Allocation(val account: ByteArray, val amount: BigInteger)

    /**
     * The deployment input: `u8 version ‖ Bytes(name) ‖ Bytes(symbol) ‖ u8 decimals ‖
     * u128 max_supply ‖ u128 initial_reward ‖ u64 halving_interval ‖ u128 tail_reward ‖
     * minter(32) ‖ u64 count ‖ count × (account(32) ‖ u128 amount)`.
     */
    fun encodeDeployInput(o: Options): ByteArray {
        val w = Writer()
            .u8(VERSION)
            .bytes(utf8(o.name))
            .bytes(utf8(o.symbol))
            .u8(o.decimals)
            .u128(o.maxSupply)
            .u128(o.initialReward)
            .u64(o.halvingInterval)
            .u128(o.tailReward)
            .put(o.minter)
            .u64(o.allocations.size)
        for (a in o.allocations) w.put(a.account).u128(a.amount)
        return w.finish()
    }

    /** Why the coin would revert `bad deploy input` with these options, or null. */
    fun deployProblem(o: Options): String? {
        if (o.name.isEmpty()) return "the name is empty"
        if (o.symbol.isEmpty()) return "the symbol is empty"
        if (HIDDEN.containsMatchIn(o.name + o.symbol)) return "the name and symbol cannot hold invisible or direction characters"
        if (o.decimals !in 0..255) return "decimals must be 0 to 255"
        if (o.minter.size != 32 || equal(o.minter, STATE_KEY)) return "the minter is not an account"
        if (o.tailReward > o.initialReward) return "the tail reward exceeds the block reward"
        var total = BigInteger.ZERO
        for (a in o.allocations) {
            if (a.account.size != 32 || isReserved(a.account)) return "an allocation names a reserved key"
            total += a.amount
        }
        if (total > o.maxSupply) return "the allocations exceed the supply cap"
        return null
    }

    /** The reward a claim at `height` mints: halved each era, at least the tail, within the cap. */
    fun blockReward(config: Config, issued: BigInteger, height: Long): BigInteger {
        if (height <= config.deployHeight) return BigInteger.ZERO
        val era = if (config.halvingInterval == 0L) 0L else (height - config.deployHeight - 1) / config.halvingInterval
        val halved = if (era >= 128) BigInteger.ZERO else config.initialReward.shiftRight(era.toInt())
        val reward = if (halved > config.tailReward) halved else config.tailReward
        val room = if (config.maxSupply > issued) config.maxSupply - issued else BigInteger.ZERO
        return if (reward < room) reward else room
    }

    // ---- Actions -----------------------------------------------------------------

    @Serializable
    sealed class Effect : ByContent() {
        @Serializable @SerialName("transfer")
        class Transfer(val to: ByteArray, val amount: BigInteger) : Effect()
        @Serializable @SerialName("burn")
        class Burn(val amount: BigInteger) : Effect()
        @Serializable @SerialName("mint")
        class Mint(val to: ByteArray, val amount: BigInteger) : Effect()
        @Serializable @SerialName("setMinter")
        class SetMinter(val minter: ByteArray) : Effect()

        /** What executing the effect takes from the acting account. */
        val debit: BigInteger get() = when (this) {
            is Transfer -> amount
            is Burn -> amount
            else -> BigInteger.ZERO
        }

        override fun toString(): String = when (this) {
            is Transfer -> "Transfer(to=${to.hex()}, amount=$amount)"
            is Burn -> "Burn(amount=$amount)"
            is Mint -> "Mint(to=${to.hex()}, amount=$amount)"
            is SetMinter -> "SetMinter(minter=${minter.hex()})"
        }
    }

    /**
     * A condition on where an action may run: inside the given batch deployment,
     * after exactly the calls whose progress prefix is `prefix`. A payment that
     * sponsors other calls of a batch carries it, so it cannot be taken out of the
     * batch and included alone.
     */
    @Serializable
    class Within(val batch: ByteArray, val prefix: ByteArray) : ByContent() {
        override fun toString() = "Within(batch=${batch.hex()}, prefix=${prefix.hex()})"
    }

    /**
     * Fee, tip, time window and condition of an action. A delayed account (a
     * time-locked slot, or a key whose policy answers with a delay) must use fee
     * 0: nothing leaves it at once. Its tip is locked and paid when the action runs.
     */
    @Serializable
    data class Timing(
        val fee: BigInteger = BigInteger.ZERO,
        val tip: BigInteger = BigInteger.ZERO,
        /** Earliest execution (block timestamp); 0 for none. */
        val notBefore: Long = 0,
        /** Inclusion deadline (block timestamp); 0 for never. */
        val validUntil: Long = 0,
        /** `null` for no condition, the case of every ordinary action. */
        val within: Within? = null,
    )

    /** An authorized action (op `10`): what it does and when. */
    @Serializable
    data class Action(val effect: Effect, val timing: Timing)

    val NO_TIMING = Timing()

    fun encodeEffect(w: Writer, e: Effect) {
        when (e) {
            is Effect.Transfer -> w.u8(ACT_TRANSFER).put32(e.to).u128(e.amount)
            is Effect.Burn -> w.u8(ACT_BURN).u128(e.amount)
            is Effect.Mint -> w.u8(ACT_MINT).put32(e.to).u128(e.amount)
            is Effect.SetMinter -> w.u8(ACT_SET_MINTER).put32(e.minter)
        }
    }

    fun readEffect(r: Reader): Effect = when (r.u8()) {
        ACT_TRANSFER -> Effect.Transfer(r.array32(), r.u128())
        ACT_BURN -> Effect.Burn(r.u128())
        ACT_MINT -> Effect.Mint(r.array32(), r.u128())
        ACT_SET_MINTER -> Effect.SetMinter(r.array32())
        else -> throw DecodeError("unknown effect")
    }

    /** `00` (no condition) or `01 ‖ batch(32) ‖ prefix(32)`. */
    private fun encodeWithin(w: Writer, within: Within?) {
        if (within == null) {
            w.u8(WITHIN_NONE)
            return
        }
        w.u8(WITHIN_BATCH).put32(within.batch).put32(within.prefix)
    }

    private fun readWithin(r: Reader): Within? = when (r.u8()) {
        WITHIN_NONE -> null
        WITHIN_BATCH -> Within(r.array32(), r.array32())
        else -> throw DecodeError("unknown condition")
    }

    /** `effect ‖ u128 fee ‖ u128 tip ‖ u64 not_before ‖ u64 valid_until ‖ within`. */
    fun encodeAction(w: Writer, a: Action) {
        encodeEffect(w, a.effect)
        w.u128(a.timing.fee).u128(a.timing.tip).u64(a.timing.notBefore).u64(a.timing.validUntil)
        encodeWithin(w, a.timing.within)
    }

    fun readAction(r: Reader): Action {
        val effect = readEffect(r)
        return Action(effect, Timing(fee = r.u128(), tip = r.u128(), notBefore = r.u64(), validUntil = r.u64(), within = readWithin(r)))
    }

    private fun messageHead(chain: ByteArray, coin: ByteArray, account: ByteArray): Writer =
        Writer().bytes(utf8(APP_TAG)).put32(chain).put32(coin).put32(account)

    /** The message a policy account authorizes for an action: `Bytes(tag) ‖ chain ‖ coin ‖ account ‖ 10 ‖ u64 nonce ‖ action`. */
    fun message(chain: ByteArray, coin: ByteArray, account: ByteArray, nonce: Long, action: Action): ByteArray {
        val w = messageHead(chain, coin, account).u8(OP_EXECUTE).u64(nonce)
        encodeAction(w, action)
        return w.finish()
    }

    /** Authorization by a policy account; the coin's own nonce rule applies to actions, not cancels. */
    class PolicyAuth(val policy: ByteArray, val params: ByteArray, val nonce: Long, val witness: ByteArray)

    /** Full `execute` input (op 10): a policy account with its nonce, or (`null`) the calling contract's account. */
    fun executeInput(auth: PolicyAuth?, action: Action): ByteArray {
        val w = Writer().u8(OP_EXECUTE)
        if (auth == null) {
            w.u8(AUTH_CONTRACT)
        } else {
            w.u8(AUTH_POLICY).put32(auth.policy).bytes(auth.params).u64(auth.nonce).bytes(auth.witness)
        }
        encodeAction(w, action)
        return w.finish()
    }

    // ---- Cancels -------------------------------------------------------------------

    /**
     * Scheduled actions to cancel (op `11`) by sequence, strictly increasing. One
     * authorization covers them all; it carries no nonce and leaves the account's
     * nonce alone. Actions that already ran or were cancelled are skipped, but at
     * least one must still be scheduled. A delayed account must use fee 0.
     */
    @Serializable
    data class Cancel(
        val sequences: List<Long>,
        val fee: BigInteger,
        /** Inclusion deadline (block timestamp); 0 for never. */
        val validUntil: Long,
    )

    /** `u64 count ‖ count × u64 sequence ‖ u128 fee ‖ u64 valid_until`. */
    fun encodeCancel(w: Writer, c: Cancel) {
        require(strictlyIncreasing(c.sequences)) { "sequences must be strictly increasing" }
        w.u64(c.sequences.size)
        for (s in c.sequences) w.u64(s)
        w.u128(c.fee).u64(c.validUntil)
    }

    fun readCancel(r: Reader): Cancel {
        val count = r.u64()
        if (count > r.remaining / 8) throw DecodeError("truncated")
        val sequences = (0 until count).map { r.u64() }
        if (!strictlyIncreasing(sequences)) throw DecodeError("sequences must be strictly increasing")
        return Cancel(sequences, r.u128(), r.u64())
    }

    /** The message a policy account authorizes for a cancel: `Bytes(tag) ‖ chain ‖ coin ‖ account ‖ 11 ‖ cancel`. */
    fun cancelMessage(chain: ByteArray, coin: ByteArray, account: ByteArray, cancel: Cancel): ByteArray {
        val w = messageHead(chain, coin, account).u8(OP_CANCEL)
        encodeCancel(w, cancel)
        return w.finish()
    }

    /** Authorization of a cancel: a policy account's, without a nonce. */
    class CancelAuth(val policy: ByteArray, val params: ByteArray, val witness: ByteArray)

    /** Full `cancel` input (op 11): a policy account, or (`null`) the calling contract's account. */
    fun cancelInput(auth: CancelAuth?, cancel: Cancel): ByteArray {
        val w = Writer().u8(OP_CANCEL)
        if (auth == null) {
            w.u8(AUTH_CONTRACT)
        } else {
            w.u8(AUTH_POLICY).put32(auth.policy).bytes(auth.params).bytes(auth.witness)
        }
        encodeCancel(w, cancel)
        return w.finish()
    }

    /** `run` and `scheduled` inputs: `op ‖ account ‖ u64 sequence`. */
    fun targetInput(op: Int, account: ByteArray, sequence: Long): ByteArray =
        Writer().u8(op).put32(account).u64(sequence).finish()

    // ---- Records -------------------------------------------------------------------

    @Serializable
    data class AccountRecord(val balance: BigInteger, val locked: BigInteger, val nonce: Long) {
        val available: BigInteger get() = if (balance > locked) balance - locked else BigInteger.ZERO
    }

    val EMPTY_ACCOUNT = AccountRecord(BigInteger.ZERO, BigInteger.ZERO, 0)

    /** An account record; a missing one is empty. Throws [DecodeError] on a malformed one. */
    fun decodeAccount(bytes: ByteArray?): AccountRecord {
        if (bytes == null) return EMPTY_ACCOUNT
        val r = Reader(bytes)
        val out = AccountRecord(r.u128(), r.u128(), r.u64())
        r.end()
        return out
    }

    @Serializable
    data class Config(
        val name: String,
        val symbol: String,
        val decimals: Int,
        val maxSupply: BigInteger,
        val initialReward: BigInteger,
        val halvingInterval: Long,
        val tailReward: BigInteger,
        val deployHeight: Long,
    )

    /**
     * Characters that change how the text around them reads, or do not show:
     * controls, direction overrides and isolates, zero-width and other format
     * characters. With them, one coin's name could pass for another's.
     */
    private val HIDDEN = Regex("[\\u0000-\\u001f\\u007f-\\u009f\\u00ad\\u061c\\u200b-\\u200f\\u202a-\\u202e\\u2060-\\u206f\\ufeff\\ufff9-\\ufffb]")

    /** A coin's name or symbol as shown: a hidden character shows as �. */
    fun visibleLabel(text: String): String = HIDDEN.replace(text, "�")

    fun decodeConfig(bytes: ByteArray): Config? = tryDecode {
        val r = Reader(bytes)
        if (r.u8() != VERSION) throw DecodeError("version")
        val c = Config(
            name = visibleLabel(fromUtf8(r.bytes())),
            symbol = visibleLabel(fromUtf8(r.bytes())),
            decimals = r.u8(),
            maxSupply = r.u128(),
            initialReward = r.u128(),
            halvingInterval = r.u64(),
            tailReward = r.u128(),
            deployHeight = r.u64(),
        )
        r.end()
        c
    }

    @Serializable
    class State(val issued: BigInteger, val burned: BigInteger, val lastClaimed: Long, val minter: ByteArray) : ByContent() {
        override fun toString() = "State(issued=$issued, burned=$burned, lastClaimed=$lastClaimed, minter=${minter.hex()})"
    }

    fun decodeState(bytes: ByteArray): State? = tryDecode {
        val r = Reader(bytes)
        val s = State(r.u128(), r.u128(), r.u64(), r.array32())
        r.end()
        s
    }

    @Serializable
    data class Scheduled(val executeAt: Long, val reserved: BigInteger, val tip: BigInteger, val effect: Effect)

    fun decodeScheduled(bytes: ByteArray): Scheduled? = tryDecode {
        val r = Reader(bytes)
        val s = Scheduled(r.u64(), r.u128(), r.u128(), readEffect(r))
        r.end()
        s
    }

    // ---- Events ----------------------------------------------------------------------

    @Serializable
    sealed class Event : ByContent() {
        @Serializable @SerialName("transfer")
        class Transfer(val from: ByteArray, val to: ByteArray, val amount: BigInteger) : Event()
        @Serializable @SerialName("minter")
        class Minter(val minter: ByteArray) : Event()
        @Serializable @SerialName("scheduled")
        class Scheduled(val account: ByteArray, val sequence: Long, val executeAt: Long, val tip: BigInteger, val effect: Effect) : Event()

        /** A scheduled action ran; its lock is released. */
        @Serializable @SerialName("executed")
        class Executed(val account: ByteArray, val sequence: Long) : Event()
        @Serializable @SerialName("cancelled")
        class Cancelled(val account: ByteArray, val sequence: Long) : Event()
        @Serializable @SerialName("producerPayment")
        class ProducerPayment(val payee: ByteArray, val amount: BigInteger) : Event()

        override fun toString(): String = when (this) {
            is Transfer -> "Transfer(from=${from.hex()}, to=${to.hex()}, amount=$amount)"
            is Minter -> "Minter(minter=${minter.hex()})"
            is Scheduled -> "Scheduled(account=${account.hex()}, sequence=$sequence, executeAt=$executeAt, tip=$tip, effect=$effect)"
            is Executed -> "Executed(account=${account.hex()}, sequence=$sequence)"
            is Cancelled -> "Cancelled(account=${account.hex()}, sequence=$sequence)"
            is ProducerPayment -> "ProducerPayment(payee=${payee.hex()}, amount=$amount)"
        }
    }

    fun decodeEvent(payload: ByteArray): Event? = tryDecode {
        val r = Reader(payload)
        val topic = r.array32()
        val e = when {
            equal(topic, TRANSFER_TOPIC) -> Event.Transfer(r.array32(), r.array32(), r.u128())
            equal(topic, MINTER_TOPIC) -> Event.Minter(r.array32())
            equal(topic, SCHEDULED_TOPIC) -> Event.Scheduled(r.array32(), r.u64(), r.u64(), r.u128(), readEffect(r))
            equal(topic, EXECUTED_TOPIC) -> Event.Executed(r.array32(), r.u64())
            equal(topic, CANCELLED_TOPIC) -> Event.Cancelled(r.array32(), r.u64())
            equal(topic, PRODUCER_PAYMENT_TOPIC) -> Event.ProducerPayment(r.array32(), r.u128())
            else -> throw DecodeError("unknown topic")
        }
        r.end()
        e
    }
}
