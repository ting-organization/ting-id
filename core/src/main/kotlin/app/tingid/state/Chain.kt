// Everything the app learns from the nodes and sends to them. The app is a
// thin client: it reads records through the REST API, signs on the device and
// submits canonical transactions. It never interprets blocks itself.
@file:UseSerializers(HexSerializer::class, BigIntegerSerializer::class)

package app.tingid.state

import app.tingid.chain.BatchCall
import app.tingid.chain.BigIntegerSerializer
import app.tingid.chain.Budgets
import app.tingid.chain.Canonical
import app.tingid.chain.ChainApi
import app.tingid.chain.Coin
import app.tingid.chain.ContractName
import app.tingid.chain.Contracts
import app.tingid.chain.FeePayer
import app.tingid.chain.HexSerializer
import app.tingid.chain.Http
import app.tingid.chain.Id
import app.tingid.chain.MaybeSent
import app.tingid.chain.NodeClient
import app.tingid.chain.NodePool
import app.tingid.chain.NodeStatus
import app.tingid.chain.Resources
import app.tingid.chain.Signer
import app.tingid.chain.SigningKey
import app.tingid.chain.StorageRead
import app.tingid.chain.TransactionLookup
import app.tingid.chain.UrlConnectionHttp
import app.tingid.chain.batchPrefix
import app.tingid.chain.contractId
import app.tingid.chain.encodeCall
import app.tingid.chain.encodeDeploy
import app.tingid.chain.equal
import app.tingid.chain.hex
import app.tingid.chain.policyAccount
import app.tingid.chain.signMessage
import app.tingid.chain.sponsoredCall
import app.tingid.chain.transactionId
import app.tingid.chain.unhex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import java.math.BigInteger

/** Where signing keys come from: the vault on the phone, memory in tests. */
fun interface KeyRing {
    /** A key that signs with the secret of `key`; it opens the secret only to sign. */
    fun signingKey(key: KeyMeta): SigningKey
}

/** The chain as the nodes last reported it, and the ids of its standard contracts. */
class ChainInfo(val status: NodeStatus, val canonical: Canonical) {
    val chainHex: String get() = status.chainId.hex()
}

/** Values a watch read from the cache: by key, and whether some key has no value yet. */
class CachedValues<T>(val values: Map<String, T>, val missing: Boolean)

class Chain(
    val store: Store,
    val caches: Caches,
    private val keyRing: KeyRing,
    private val scope: CoroutineScope,
    private val http: Http = UrlConnectionHttp,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    // ---- Nodes and chain -------------------------------------------------------

    private val pools = HashMap<String, NodePool>()

    /** The user's nodes used as one: one pool for every screen, so what it learns of them is shared. */
    @Synchronized
    fun poolFor(urls: List<String>): NodePool = pools.getOrPut(urls.joinToString(" ")) {
        NodePool(urls, make = { url -> NodeClient(url, http) { app.tingid.chain.SubmitListener { id, tx, notBefore -> store.keepSubmission(id, tx, notBefore) } } }).also {
            // What came from a node found to give wrong answers is read again from the others.
            it.onDistrust = { caches.invalidateCurrent() }
        }
    }

    val pool: StateFlow<NodePool> = store.data.map { poolFor(it.settings.nodeUrls) }.distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, poolFor(store.data.value.settings.nodeUrls))

    /** What the app asks of the chain: the user's nodes, as one. */
    val node: ChainApi get() = pool.value

    private val _error = MutableStateFlow<String?>(null)

    /** Why no node can be used now, while none can. */
    val error: StateFlow<String?> = _error

    /** The chain as the nodes last reported it, from the cache at first. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val info: StateFlow<ChainInfo?> = caches.current.flatMapLatest { cache ->
        if (cache == null) flowOf(null)
        else cache.revision.map { cache.get(STATUS_KEY, NodeStatus.serializer())?.value }.distinctUntilChanged()
            .map { status -> status?.let { ChainInfo(it, Contracts.canonical(it.chainId)) } }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    private var watchers = 0
    private var polling: Job? = null
    private val polls = Mutex()

    /**
     * The status of the user's nodes, asked every 8 seconds while anyone
     * watches, once for the whole app. The best node's goes into the cache of
     * its chain. Close the handle to stop watching.
     */
    @Synchronized
    fun watchStatus(): AutoCloseable {
        if (watchers++ == 0) polling = scope.launch {
            while (isActive) {
                poll()
                delay(STATUS_MS)
            }
        }
        return AutoCloseable {
            synchronized(this) {
                if (--watchers == 0) {
                    polling?.cancel()
                    polling = null
                }
            }
        }
    }

    /** Asks the nodes for their status now. */
    suspend fun poll() {
        if (!polls.tryLock()) return
        try {
            val status = pool.value.status()
            val cache = caches.select(status.chainId.hex())
            cache.put(STATUS_KEY, status, NodeStatus.serializer(), "")
            // Back after a pause: what failed meanwhile is read again.
            if (_error.value != null) {
                _error.value = null
                cache.retry()
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            _error.value = e.message ?: "no node answers"
        } finally {
            polls.unlock()
        }
    }

    /** Forgets what was found of the nodes and reads everything again. */
    fun refresh() {
        scope.launch {
            pool.value.reset()
            caches.current.value?.invalidate()
            poll()
        }
    }

    /**
     * The cached values of `keys`, and one call of `read` for those not read at
     * the chain's current tip yet: `read` gets those keys in order and gives their
     * values in the same order. Without a chain yet nothing is read. A read
     * finishes into the cache even when the watcher is gone: other screens use it.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun <T> watch(keys: List<String>, serializer: KSerializer<T>, read: suspend (stale: List<String>) -> List<T>): Flow<CachedValues<T>> =
        caches.current.flatMapLatest { cache ->
            if (cache == null) return@flatMapLatest flowOf(CachedValues(emptyMap(), keys.isNotEmpty()))
            combine(info, cache.epoch, cache.revision) { i, _, _ -> i?.status?.tip }.map { version ->
                if (version != null) {
                    val stale = keys.filter { cache.stale(it, version) }
                    if (stale.isNotEmpty()) {
                        cache.begin(stale, version)
                        scope.launch {
                            try {
                                val values = read(stale)
                                stale.forEachIndexed { k, key -> cache.put(key, values[k], serializer, version) }
                            } catch (e: Exception) {
                                for (key in stale) cache.fail(key, version)
                            }
                        }
                    }
                }
                val values = LinkedHashMap<String, T>()
                var missing = false
                for (key in keys) {
                    val c = cache.get(key, serializer)
                    if (c != null) values[key] = c.value else missing = true
                }
                CachedValues(values, missing)
            }
        }.distinctUntilChanged { a, b -> a.missing == b.missing && a.values.size == b.values.size && a.values.all { (k, v) -> b.values[k] === v } }

    // ---- Identities ----------------------------------------------------------------

    /** The identities of `users` on the chain the app is on, by user id (null: not on chain). Users not read yet are missing. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun identities(users: List<User>): Flow<Map<String, IdentityInfo?>> = caches.current.flatMapLatest { cache ->
        val wanted = users.mapNotNull { u ->
            if (u.kind == UserKind.Identity && u.registry != null && u.identityId != null && (u.chainId == null || u.chainId == cache?.chain)) {
                u.id to "identity:${u.registry}:${u.identityId}"
            } else {
                null
            }
        }
        val keys = wanted.map { it.second }.distinct()
        watch(keys, IdentityInfo.serializer().nullable) { stale ->
            stale.map { k ->
                val (_, registry, id) = k.split(':')
                fetchIdentity(node, unhex(registry), unhex(id))
            }
        }.map { v -> wanted.filter { v.values.containsKey(it.second) }.associate { it.first to v.values[it.second] } }
    }

    // ---- Signing -------------------------------------------------------------------

    /** Finds a device key that may sign for `user` (as a key of its identity, for an identity). */
    fun resolveSigner(user: User, slot: Long, c: Canonical, keys: List<KeyMeta>, identity: IdentityInfo?, preferredKeyId: String? = null): Signer {
        if (user.kind == UserKind.Key) {
            val key = keys.firstOrNull { it.id == user.keyId } ?: throw IllegalStateException("this user’s key is no longer on the device")
            return Signer(keyRing.signingKey(key), null, c.policyFor(key.scheme), unhex(key.pub))
        }
        if (identity == null) throw IllegalStateException("the identity is not on chain yet")
        // The key this device made for the user comes first; any linked key will do.
        val candidates = if (preferredKeyId != null) keys.filter { it.id == preferredKeyId } else keys.sortedByDescending { it.id == user.keyId }
        for ((keyId, key) in identity.keys) {
            val match = candidates.firstOrNull { equal(c.policyFor(it.scheme), key.policy) && equal(unhex(it.pub), key.params) }
            if (match != null) return Signer(keyRing.signingKey(match), keyId, unhex(user.registry!!), Id.params(unhex(user.identityId!!), slot))
        }
        throw IllegalStateException("no key on this device can sign for this identity")
    }

    /** The device key `key` signing as key `keyId` of an identity, for registry operations. */
    fun keySigner(key: KeyMeta, keyId: Long): RegistrySigner = RegistrySigner(keyRing.signingKey(key), keyId)

    // ---- Nonces --------------------------------------------------------------------

    private fun nonceKey(coinId: ByteArray, account: ByteArray) = "${coinId.hex()}:${account.hex()}"

    /**
     * The nonce for the next action of a coin account. While this device's last
     * action of the account still waits in the node, the next one takes the nonce
     * after it and so queues behind it instead of competing for the same nonce.
     * `prefix`: a fee payment for the same calls as the last one reuses its nonce,
     * so the transaction is the same.
     */
    suspend fun nextNonce(coinId: ByteArray, account: ByteArray, prefix: String? = null): Long {
        val node = this.node
        val note = store.data.value.nonces[nonceKey(coinId, account)]
        // The earlier transaction is looked up before the nonce is read: if a block
        // takes it in between, the nonce read already counts it. One that cannot be
        // looked up counts as waiting, so no nonce is ever signed twice.
        val waiting = if (note == null) false else try {
            node.transaction(note.txId)?.pending == true
        } catch (e: Exception) {
            true
        }
        val current = fetchAccount(node, coinId, account).nonce
        // Past its expiry the noted transaction can never go in, so its nonce is free again.
        if (note == null || !waiting || note.nonce < current || now() - note.at > SIGNED_LIFE) return current
        return if (prefix != null && note.prefix == prefix) note.nonce else note.nonce + 1
    }

    private fun noteNonce(coinId: ByteArray, account: ByteArray, nonce: Long, txId: String, prefix: String? = null) =
        store.noteNonce(nonceKey(coinId, account), NonceNote(nonce, txId, now(), prefix))

    /**
     * When what this app signs expires unless included: the end of the next day
     * (UTC), so the same action signed again that day is the same transaction. A
     * signed action that never went in cannot be used later (wiki: programs/coin,
     * expiry); one already scheduled still runs when due.
     */
    fun signedExpiry(): Long = (now() / 86_400 + 2) * 86_400

    // ---- Submitting ----------------------------------------------------------------

    /** Whether `payer` is the acting account itself in the same coin, which then pays its fee directly. */
    private fun paysOwnFee(payer: FeePayer?, coinId: ByteArray, account: ByteArray): Boolean =
        payer != null && payer.fee.signum() > 0 && equal(payer.coinId, coinId) && equal(payer.signer.account, account)

    /**
     * Submits `call` alone with its usual budget or, with a fee, as a batch that
     * ends with the payer's bound payment, and then the call alone as well,
     * unpaid, so it does not depend on the payment (wiki: programs/conventions):
     * someone else holding a key of the paying account cannot make it fail by
     * using that account's nonce first. The batch's id is returned; the call's
     * own id is kept with it, and whichever goes in settles the submission.
     */
    suspend fun submitWithFee(c: Canonical, chainId: ByteArray, call: BatchCall, payer: FeePayer?, budget: Resources = Budgets.CALL): String {
        val node = this.node
        val alone = encodeCall(call.target, call.input, budget)
        if (payer == null || payer.fee.signum() == 0) return node.submit(alone)
        val account = payer.signer.account
        val prefix = batchPrefix(listOf(call)).hex()
        val nonce = nextNonce(payer.coinId, account, prefix)
        // A transaction the node may have, its answer lost, counts as sent but not for sure.
        var paid: String? = null
        var sure = false
        var refused: Exception? = null
        try {
            paid = node.submit(sponsoredCall(chainId, c, listOf(call), payer, nonce, signedExpiry()))
            sure = true
        } catch (e: MaybeSent) {
            paid = e.txId
        } catch (e: Exception) {
            refused = e
        }
        if (paid != null) noteNonce(payer.coinId, account, nonce, paid, prefix)
        var unpaid: String? = null
        try {
            unpaid = node.submit(alone)
            sure = true
        } catch (e: MaybeSent) {
            unpaid = e.txId
        } catch (e: Exception) {
            // The batch decides.
        }
        if (paid != null && unpaid != null) store.pairOutbox(paid, unpaid)
        val id = paid ?: unpaid ?: throw refused!!
        if (!sure) throw MaybeSent(id)
        return id
    }

    class Submitted(val txId: String, val account: ByteArray, val nonce: Long)

    /**
     * Signs a coin action for the signer's account and submits it. A delayed
     * account must use fee 0; `payer` then pays for it from another account. A
     * payer that is the acting account itself adds its fee to the action. An
     * action without its own expiry gets the usual one.
     */
    suspend fun submitCoinAction(chainId: ByteArray, coinId: ByteArray, signer: Signer, action: Coin.Action, nonceOverride: Long? = null, payer: FeePayer? = null): Submitted {
        val account = policyAccount(signer.policy, signer.params)
        val nonce = nonceOverride ?: nextNonce(coinId, account)
        val own = paysOwnFee(payer, coinId, account)
        val timing = action.timing.copy(validUntil = action.timing.validUntil.takeIf { it != 0L } ?: signedExpiry())
        val signed = action.copy(timing = if (own) timing.copy(fee = timing.fee + payer!!.fee) else timing)
        val message = Coin.message(chainId, coinId, account, nonce, signed)
        val input = Coin.executeInput(Coin.PolicyAuth(signer.policy, signer.params, nonce, signer.witness(message)), signed)
        val txId = try {
            submitWithFee(Contracts.canonical(chainId), chainId, BatchCall(coinId, input), if (own) null else payer, Budgets.EXECUTE)
        } catch (e: MaybeSent) {
            // Its nonce is taken all the same, so nothing is ever signed again with it.
            noteNonce(coinId, account, nonce, e.txId)
            throw MaybeSent(e.txId, account to nonce)
        }
        noteNonce(coinId, account, nonce, txId)
        return Submitted(txId, account, nonce)
    }

    /**
     * Cancels scheduled actions of the signer's account with one signature (op 11).
     * The cancel carries no nonce. Its fee is 0 unless `payer` is the account
     * itself (an instant one), which then pays it directly; any other payer pays
     * through a batch, as it must for a delayed account.
     */
    suspend fun submitCoinCancel(chainId: ByteArray, coinId: ByteArray, signer: Signer, sequences: List<Long>, payer: FeePayer? = null): Pair<String, ByteArray> {
        val account = policyAccount(signer.policy, signer.params)
        val own = paysOwnFee(payer, coinId, account)
        val cancel = Coin.Cancel(sequences.sorted(), if (own) payer!!.fee else BigInteger.ZERO, signedExpiry())
        val message = Coin.cancelMessage(chainId, coinId, account, cancel)
        val input = Coin.cancelInput(Coin.CancelAuth(signer.policy, signer.params, signer.witness(message)), cancel)
        val txId = submitWithFee(Contracts.canonical(chainId), chainId, BatchCall(coinId, input), if (own) null else payer, Budgets.EXECUTE)
        return txId to account
    }

    /**
     * Deploys a new coin with `options`. A deployment names no sender and carries
     * no fee: it waits for room in a block. Its ContractId follows from the
     * transaction, so it is known before a block includes it.
     */
    suspend fun deployCoin(chainId: ByteArray, options: Coin.Options): Pair<String, ByteArray> {
        Coin.deployProblem(options)?.let { throw IllegalArgumentException(it) }
        val tx = encodeDeploy(Contracts.code(ContractName.Coin), Coin.encodeDeployInput(options), Budgets.coinDeploy(options.allocations.size))
        val txId = node.submit(tx)
        return txId to contractId(chainId, tx)
    }

    /** Submits an unsigned coin call (run, claim). */
    suspend fun submitCoinCall(coinId: ByteArray, input: ByteArray): String = node.submit(encodeCall(coinId, input, Budgets.CALL))

    // ---- Running scheduled actions --------------------------------------------------
    //
    // The run of a scheduled action is an unsigned call that anyone may submit once
    // the action is due. Wallets hand it to the node right away as a deferred
    // transaction: the node holds it and includes it once it is due and succeeds.
    // Every wallet builds the same bytes (the CLI too), so queues merge by id.

    /** Queues the run on the node for when the action is due (`notBefore`, seconds). */
    suspend fun queueRun(coinId: ByteArray, account: ByteArray, sequence: Long, notBefore: Long): String =
        node.submit(runTransaction(coinId, account, sequence), notBefore)

    /** Submits the run now (for an action that is due). */
    suspend fun submitRun(coinId: ByteArray, account: ByteArray, sequence: Long): String = node.submit(runTransaction(coinId, account, sequence))

    suspend fun fetchRunStatus(chainId: ByteArray, coinId: ByteArray, account: ByteArray, sequence: Long): RunStatus? {
        val lookup = node.transaction(transactionId(chainId, runTransaction(coinId, account, sequence)).hex()) ?: return null
        return when {
            lookup.occurrences.any { it.receipt.status == 0 } -> RunStatus.Ran
            lookup.pending -> RunStatus.Pending
            lookup.deferred != null -> RunStatus.Queued
            else -> null
        }
    }

    /** The run status of each scheduled action that has one known, by [runKey], from the cache; read again with each block. */
    fun runStatuses(refs: List<RunRef>): Flow<Map<String, RunStatus?>> {
        val keys = refs.map { "run:${runKey(it)}" }
        return watch(keys, RunStatus.serializer().nullable) { stale ->
            val chainId = info.value?.status?.chainId ?: throw IllegalStateException("no chain yet")
            stale.map { k -> refs[keys.indexOf(k)].let { r -> fetchRunStatus(chainId, r.coinId, r.account, r.sequence) } }
        }.map { v -> v.values.mapKeys { it.key.removePrefix("run:") } }
    }

    // ---- Registry ----------------------------------------------------------------

    /**
     * A registry operation on `id` signed by key `signer.keyId` (of `id` itself, or
     * of the guardian for approvals and withdrawals); the message names that key.
     */
    suspend fun submitRegistryOp(
        chainId: ByteArray,
        registry: ByteArray,
        id: ByteArray,
        signer: RegistrySigner,
        body: ByteArray,
        build: (auth: ByteArray) -> ByteArray,
        payer: FeePayer? = null,
    ): String {
        val message = Id.registryMessage(chainId, registry, id, signer.keyId, body)
        val auth = Id.keyAuth(signer.keyId, signer.key.sign(app.tingid.chain.signingDigest(message)))
        return submitWithFee(Contracts.canonical(chainId), chainId, BatchCall(registry, build(auth)), payer)
    }

    suspend fun submitRegistryCall(registry: ByteArray, input: ByteArray): String = node.submit(encodeCall(registry, input, Budgets.CALL))

    // ---- Transaction status ---------------------------------------------------

    /** A transaction as the nodes have it, asked every `pollMs` until a block settled it. */
    fun transaction(txId: String, pollMs: Long = 2_500): Flow<TxLookup> = flow {
        while (true) {
            try {
                val l = node.transaction(txId)
                emit(TxLookup(l, null, l == null))
                if (l != null && l.occurrences.isNotEmpty() && !l.pending) return@flow
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                emit(TxLookup(null, e.message ?: "the node does not answer", false))
            }
            delay(pollMs)
        }
    }

    companion object {
        /** How often the nodes' status is asked. */
        const val STATUS_MS = 8_000L

        /** The cache key of the last status, so the app starts with it. */
        const val STATUS_KEY = "status"

        /** The longest a signed action stays valid: [signedExpiry] is at most two days after signing. */
        const val SIGNED_LIFE = 2 * 86_400L
    }
}

/** A key of an identity signing for a registry operation. */
class RegistrySigner(val key: SigningKey, val keyId: Long)

/** A transaction's lookup, or why it could not be looked up; `missing`: the node does not know it. */
class TxLookup(val lookup: TransactionLookup?, val error: String?, val missing: Boolean)

// ---- Coin accounts -----------------------------------------------------------

suspend fun fetchAccount(node: ChainApi, coinId: ByteArray, account: ByteArray): Coin.AccountRecord =
    Coin.decodeAccount(node.storage(coinId, account))

/** Several coin accounts in one read: each pairs a coin with an account. */
suspend fun fetchAccounts(node: ChainApi, reads: List<Pair<ByteArray, ByteArray>>): List<Coin.AccountRecord> {
    if (reads.isEmpty()) return emptyList()
    return node.storageMany(reads.map { StorageRead(it.first, it.second) }).map { Coin.decodeAccount(it) }
}

@Serializable
class ScheduledItem(val sequence: Long, val record: Coin.Scheduled)

class ScheduledQuery(
    val coinId: ByteArray,
    val account: ByteArray,
    /** The account's nonce: its scheduled actions have sequences below it. */
    val nonce: Long,
    /** What its pending actions hold: the sum of their `reserved`. */
    val locked: BigInteger,
    /** Read back to the first sequence: for a coin's minter, whose mints and minter changes may reserve nothing. */
    val everything: Boolean = false,
)

/** Sequences or changes read in the first look back; later looks read a full batch. */
private const val FIRST_LOOK = 40L
private const val LOOK = 1024L

/** Looks back for scheduled actions, at most. */
private const val MAX_LOOKS = 64

/**
 * Scheduled actions still pending of several coin accounts, soonest first.
 * Each account's are read back from its nonce until the amounts they reserve
 * add up to its locked amount, which is exactly what its pending actions hold
 * (wiki: programs/coin), so none hides behind later ones; an account with
 * nothing locked has none to read.
 */
suspend fun fetchScheduledMany(node: ChainApi, of: List<ScheduledQuery>): List<List<ScheduledItem>> {
    val found = of.map { ArrayList<ScheduledItem>() }
    val held = Array(of.size) { BigInteger.ZERO }
    val below = LongArray(of.size) { of[it].nonce }
    fun open(i: Int) = below[i] > 0 && (of[i].everything || held[i] < of[i].locked)
    var span = FIRST_LOOK
    // Bounded, whatever an account's nonce: past this many looks back, what is
    // older is not shown (an account with that many actions is not a person's).
    for (look in 0 until MAX_LOOKS) {
        val reads = ArrayList<Pair<Int, Long>>()
        for (i in of.indices) {
            if (!open(i)) continue
            val from = if (below[i] > span) below[i] - span else 0
            var sequence = below[i] - 1
            while (sequence >= from) reads.add(i to sequence--)
            below[i] = from
        }
        if (reads.isEmpty()) break
        val values = node.storageMany(reads.map { (i, s) -> StorageRead(of[i].coinId, Coin.scheduledKey(of[i].account, s)) })
        reads.forEachIndexed { k, (i, sequence) ->
            val record = values[k]?.let { Coin.decodeScheduled(it) } ?: return@forEachIndexed
            found[i].add(ScheduledItem(sequence, record))
            held[i] += record.reserved
        }
        span = LOOK
    }
    return found.map { list -> list.sortedBy { it.record.executeAt } }
}

// ---- Identities ----------------------------------------------------------------

@Serializable
data class IdentityKey(val keyId: Long, val key: Id.Key)

@Serializable
data class IdentitySlot(val slot: Long, val delay: Long)

@Serializable
data class PendingChange(val changeNo: Long, val record: Id.ChangeRecord)

@Serializable
class IdentityInfo(
    val id: ByteArray,
    val registry: ByteArray,
    val core: Id.Core,
    val keys: List<IdentityKey>,
    val slots: List<IdentitySlot>,
    val pending: List<PendingChange>,
)

/** Reads of an identity that may find its core changed before it gives up. */
private const val IDENTITY_TRIES = 5

/** The `span` change numbers below `below`, newest first. */
private fun changeNos(below: Long, span: Long): List<Long> {
    val from = if (below > span) below - span else 0
    return (below - 1 downTo from).toList()
}

/**
 * An identity as the registry has it: the core record, then the core again
 * with its keys, slots and newest changes in one read, so they all come from
 * the same block. A core that changed in between is read from again. Changes
 * further back, when there are many, take more reads.
 */
suspend fun fetchIdentity(node: ChainApi, registry: ByteArray, id: ByteArray): IdentityInfo? {
    val coreRead = StorageRead(registry, Id.coreKey(id))
    var coreBytes = node.storage(registry, Id.coreKey(id))
    var tries = 0
    while (true) {
        // A core that changes on every read is not one to wait for: a block is a minute.
        if (tries++ == IDENTITY_TRIES) throw IllegalStateException("the identity kept changing while it was read: try again")
        val core = coreBytes?.let { Id.decodeCore(it) } ?: return null
        // A node's record is not taken on trust as to its size: far more keys or slots than any identity has is refused.
        if (core.nextKeyId > 4096 || core.slotCount > 4096) throw IllegalStateException("the identity's record is not one this app can read")
        val keyIds = (0 until core.nextKeyId).toList()
        val slotNos = (0 until core.slotCount).toList()
        val firstChanges = changeNos(core.nextChange, FIRST_LOOK)
        val values = node.storageMany(
            listOf(coreRead) +
                keyIds.map { StorageRead(registry, Id.keyKey(id, core.keyEpoch, it)) } +
                slotNos.map { StorageRead(registry, Id.slotKey(id, it)) } +
                firstChanges.map { StorageRead(registry, Id.changeKey(id, it)) },
        )
        if (values[0] == null || !equal(values[0]!!, coreBytes)) {
            coreBytes = values[0]
            continue
        }
        var next = 1
        val keys = keyIds.mapNotNull { keyId -> values[next++]?.let { Id.decodeKeyRecord(it) }?.let { IdentityKey(keyId, it) } }
        val slots = slotNos.map { slot ->
            val b = values[next++]
            IdentitySlot(slot, if (b != null && b.size == 8) app.tingid.chain.Reader(b).u64() else 0)
        }
        // Changes are read back from the newest until one of an earlier change
        // epoch: every change before it is older still, and none of those is pending.
        val changes = ArrayList<PendingChange>()
        var older = false
        fun take(nos: List<Long>, records: List<ByteArray?>) = nos.forEachIndexed { k, changeNo ->
            val record = records[k]?.let { Id.decodeChangeRecord(it) } ?: return@forEachIndexed
            if (record.changeEpoch < core.changeEpoch) older = true else changes.add(PendingChange(changeNo, record))
        }
        take(firstChanges, values.subList(next, values.size))
        var below = core.nextChange - firstChanges.size
        while (below > 0 && !older) {
            val nos = changeNos(below, LOOK)
            take(nos, node.storageMany(nos.map { StorageRead(registry, Id.changeKey(id, it)) }))
            below -= minOf(LOOK, below)
        }
        changes.sortBy { it.changeNo }
        // A change stays pending only while the key that proposed it does.
        val current = keys.map { it.keyId }
        val pending = changes.filter { Id.isPending(it.record, core, current) }
        return IdentityInfo(id, registry, core, keys, slots, pending)
    }
}

// ---- Accounts of a user ------------------------------------------------------

val SLOT_LABELS = listOf("Spending", "Savings")

/** What the app calls an identity's accounts: by slot, Spending and Savings, then Account 2, 3, …. */
fun slotLabel(slot: Long): String = if (slot in SLOT_LABELS.indices) SLOT_LABELS[slot.toInt()] else "Account $slot"

class UserAccount(val slot: Long, val label: String, val account: ByteArray, val delay: Long)

/** The accounts a user can act from: one per identity slot, or the key's own account. */
fun userAccounts(user: User, c: Canonical, keys: List<KeyMeta>, identity: IdentityInfo?): List<UserAccount> {
    if (user.kind == UserKind.Key) {
        val key = keys.firstOrNull { it.id == user.keyId } ?: return emptyList()
        return listOf(UserAccount(0, "Account", policyAccount(c.policyFor(key.scheme), unhex(key.pub)), 0))
    }
    val registry = unhex(user.registry!!)
    val id = unhex(user.identityId!!)
    val slots = identity?.slots?.map { it.slot to it.delay } ?: settingsSlots(user)
    return slots.map { (slot, delay) -> UserAccount(slot, slotLabel(slot), Id.account(registry, id, slot), delay) }
}

private fun settingsSlots(user: User): List<Pair<Long, Long>> {
    val s = user.creation?.let { Id.decodeCreation(unhex(it)) }
    return (s?.slots ?: Id.DEFAULT_SLOTS).mapIndexed { slot, delay -> slot.toLong() to delay }
}

// ---- Runs ----------------------------------------------------------------------

@Serializable
enum class RunStatus {
    /** Held by the node until due. */
    Queued,

    /** In the pool. */
    Pending,

    /** In a block. */
    Ran,
}

class RunRef(val coinId: ByteArray, val account: ByteArray, val sequence: Long)

fun runKey(r: RunRef): String = "${r.coinId.hex()}:${r.account.hex()}:${r.sequence}"

fun runTransaction(coinId: ByteArray, account: ByteArray, sequence: Long): ByteArray =
    encodeCall(coinId, Coin.targetInput(Coin.OP_RUN, account, sequence), Budgets.CLAIM)

// ---- Submissions -----------------------------------------------------------------

/** A submission's id, and whether its answer was lost: the node may have it, and it is kept to be sent again with the same bytes. */
class Sent(val txId: String, val unsure: Boolean, val action: Pair<ByteArray, Long>? = null)

/** Runs a submission; one whose answer was lost still gives its id. */
suspend fun sent(f: suspend () -> String): Sent = try {
    Sent(f(), false)
} catch (e: MaybeSent) {
    Sent(e.txId, true, e.action)
}

// ---- Activity ---------------------------------------------------------------------

@Serializable
enum class Settled {
    Executed,
    Cancelled,
}

@Serializable
class FeePaid(val contract: ByteArray, val amount: BigInteger)

@Serializable
data class ActivityItem(
    val height: Long,
    /** The block's timestamp, in seconds. */
    val time: Long,
    val transaction: String,
    val contract: ByteArray,
    val coin: Coin.Event? = null,
    val identity: Id.Event? = null,
    /** For a scheduled action: whether a later block ran it or cancelled it. */
    val settled: Settled? = null,
    /** For a transfer: it paid a block producer, a fee or a tip, and no other event of its transaction is shown to carry it. */
    val fee: Boolean = false,
    /** The fees its transaction paid from the user's accounts, in their coins. */
    val fees: List<FeePaid> = emptyList(),
    /** For a scheduled action that ran or was cancelled: what it was to do, when its scheduling is among the items. */
    val plan: Coin.Effect? = null,
    /** For payments to a miner grouped by day: how many the row stands for, its amount their sum. */
    val count: Int? = null,
)

/** Serializer of a list of activity items, for the cache. */
val ACTIVITY_ITEMS: KSerializer<List<ActivityItem>> = ListSerializer(ActivityItem.serializer())
