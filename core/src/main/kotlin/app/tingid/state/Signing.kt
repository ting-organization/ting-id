// Answering a site's signing request (TOCS-1.md): fetching it, checking it
// against this device's chain and users, and sending the signed answer, or the
// refusal, back to the site.
//
// A site is asked as a stranger: no cookies go with a request and none are
// kept, so one site's answers cannot follow the person to another, and no
// redirect is followed: the site that answers is the site at the address.
package app.tingid.state

import app.tingid.chain.BadJson
import app.tingid.chain.Canonical
import app.tingid.chain.Contracts
import app.tingid.chain.Http
import app.tingid.chain.HttpFailure
import app.tingid.chain.HttpRequest
import app.tingid.chain.HttpResponse
import app.tingid.chain.Offchain
import app.tingid.chain.OffchainError
import app.tingid.chain.SignRequest
import app.tingid.chain.UrlConnectionHttp
import app.tingid.chain.WHITE_SPACE
import app.tingid.chain.equal
import app.tingid.chain.hex
import app.tingid.chain.parseJson
import app.tingid.chain.shortId
import app.tingid.chain.trimWhiteSpace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Why this device cannot answer `request` with `user` on chain `chainHex`, or
 * null when it can: the request must be for the chain the app uses, and for
 * the ID it names, when it names one.
 */
fun signerProblem(request: SignRequest, user: User?, chainHex: String?): String? {
    if (chainHex == null) return "the app is not connected to a node"
    if (request.chainId.hex() != chainHex) return "this request is for another Ting network than the one this app uses"
    if (user == null) return "create a user first"
    val signer = request.signer
    if (signer != null && (user.kind != UserKind.Identity || user.identityId != signer.hex())) return "this request is for the ID ${shortId(signer, 8, 6)}"
    if (user.kind == UserKind.Identity && user.chainId != null && user.chainId != chainHex) return "this user belongs to another Ting network"
    return null
}

/** The users of this device that may answer: the one the request names, or every user of this chain. */
fun possibleSigners(request: SignRequest, users: List<User>, chainHex: String?): List<User> =
    users.filter { signerProblem(request, it, chainHex) == null }

class Signing(
    private val chain: Chain,
    private val http: Http = UrlConnectionHttp,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    /** Where keys sign: the vault blocks while it opens one. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private class Reply(val ok: Boolean, val status: Int, val json: JsonElement?)

    /** A 3xx answer: the site sent the request elsewhere, which is never followed. */
    private fun HttpResponse.redirected(): Boolean = redirect || status in 300..399

    private suspend fun exchange(url: String, method: String = "GET", body: JsonObject? = null): Reply {
        val response = try {
            http.exchange(
                HttpRequest(
                    url = url,
                    method = method,
                    body = body?.toString()?.encodeToByteArray(),
                    headers = buildMap {
                        put("accept", "application/json")
                        if (body != null) put("content-type", "application/json")
                    },
                    timeoutMs = TIMEOUT_MS,
                    maxBytes = MAX_REPLY,
                ),
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            throw OffchainError(
                when {
                    e is HttpFailure && e.timedOut -> "the site did not answer in time"
                    e is HttpFailure && e.tooLarge -> "the site sent an answer far too large"
                    else -> "the site could not be reached"
                },
            )
        }
        if (response.redirected()) throw OffchainError("the site sent the request on to another address, which Ting ID does not follow")
        if (response.body.size > MAX_REPLY) throw OffchainError("the site sent an answer far too large")
        val json = try {
            parseJson(response.text())
        } catch (e: BadJson) {
            null
        }
        return Reply(response.ok, response.status, json)
    }

    /** Fetches the request at `url` and checks it before anything of it is shown. */
    suspend fun fetchSignRequest(url: String): SignRequest {
        val r = exchange(url)
        if (!r.ok) throw OffchainError(siteMessage(r.json, "the site answered ${r.status}"))
        return Offchain.readRequest(r.json, url, now())
    }

    /**
     * The file `request`'s statement covers, fetched from the site and checked
     * against the hash the statement signs: only that exact file comes back.
     */
    suspend fun fetchAttachment(request: SignRequest): ByteArray {
        val attachment = (request.request.request as? Offchain.Request.Statement)?.attachment
        val file = request.file
        if (attachment == null || file == null) throw OffchainError("this request covers no file")
        val response = try {
            http.exchange(HttpRequest(url = file, timeoutMs = TIMEOUT_MS, maxBytes = MAX_FILE))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            throw OffchainError(
                when {
                    e is HttpFailure && e.timedOut -> "the site did not send the file in time"
                    e is HttpFailure && e.tooLarge -> "the site sent a file far too large"
                    else -> "the file could not be fetched"
                },
            )
        }
        if (response.redirected()) throw OffchainError("the site sent the file request on to another address, which Ting ID does not follow")
        if (!response.ok) throw OffchainError("the site answered ${response.status} for the file")
        if (response.body.size > MAX_FILE) throw OffchainError("the site sent a file far too large")
        if (!Offchain.isAttachment(response.body, attachment)) throw OffchainError("the site sent another file than the one the statement covers")
        return response.body
    }

    /**
     * Signs `request` as `user` (an identity signs with its spending account) and
     * sends the answer. Returns what the site said; throws when the site refused.
     */
    suspend fun signAndAnswer(request: SignRequest, user: User, c: Canonical, keys: List<KeyMeta>, identity: IdentityInfo?): String {
        // The screen checked this already; signing checks again, as it cannot be taken back.
        if (!equal(Contracts.canonical(request.chainId).registry, c.registry)) throw OffchainError("this request is for another Ting network than the one this app uses")
        signerProblem(request, user, request.chainId.hex())?.let { throw OffchainError(it) }
        val body = withContext(io) {
            val signer = chain.resolveSigner(user, 0, c, keys, identity)
            val account = signer.account
            val witness = signer.witness(Offchain.signedMessage(request, account))
            Offchain.answerJson(request.nonce, account, signer.policy, signer.params, witness)
        }
        val r = exchange(request.callback, "POST", body)
        val message = siteMessage(r.json, if (r.ok) "Done." else "the site refused the answer (${r.status})")
        if (!r.ok) throw OffchainError(message)
        return message
    }

    /** Tells the site the user declined, so it stops waiting; a site that cannot be reached does not matter here. */
    suspend fun declineRequest(request: SignRequest) {
        try {
            exchange(request.callback, "POST", Offchain.declineJson(request.nonce))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
        }
    }

    companion object {
        const val TIMEOUT_MS = 30_000L

        /** A request or a site's reply larger than this is not one. */
        const val MAX_REPLY = 256 * 1024

        /** The largest file a statement can cover. */
        const val MAX_FILE = 25 * 1024 * 1024

        private val SPACES = Regex("[$WHITE_SPACE]+")

        /** The longest site message shown, in characters. */
        private const val MAX_MESSAGE = 240

        /**
         * The site's own words, one line, marked as the site's so they never read as
         * the app's; `fallback`, the app's, when the site gave none. Characters that
         * could hide or reorder text, such as direction overrides, are left out, so
         * the site's words cannot seem to end before the closing mark.
         */
        fun siteMessage(json: JsonElement?, fallback: String): String {
            val m = ((json as? JsonObject)?.get("message") as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return fallback
            val visible = buildString {
                m.codePoints().forEach { cp ->
                    val type = Character.getType(cp)
                    if (type != Character.FORMAT.toInt() && type != Character.SURROGATE.toInt() && (type != Character.CONTROL.toInt() || app.tingid.chain.isWhiteSpace(cp))) appendCodePoint(cp)
                }
            }
            val line = trimWhiteSpace(visible.replace(SPACES, " "))
            if (line.isEmpty()) return fallback
            val shown = if (line.length > MAX_MESSAGE) {
                // Not between the two halves of a character outside the BMP.
                val end = if (Character.isHighSurrogate(line[MAX_MESSAGE - 1])) MAX_MESSAGE - 1 else MAX_MESSAGE
                "${line.substring(0, end)}…"
            } else {
                line
            }
            return "the site says: “$shown”"
        }
    }
}
