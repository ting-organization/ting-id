// Answering a site's signing request (TOCS-1): fetched as a stranger, never
// through a redirect, checked against this device's chain and users, and
// answered with a witness the site can check as the chain would.
package app.tingid.state

import app.tingid.chain.Contracts
import app.tingid.chain.Http
import app.tingid.chain.HttpFailure
import app.tingid.chain.HttpRequest
import app.tingid.chain.HttpResponse
import app.tingid.chain.Id
import app.tingid.chain.Offchain
import app.tingid.chain.OffchainError
import app.tingid.chain.Reader
import app.tingid.chain.Scheme
import app.tingid.chain.SignRequest
import app.tingid.chain.filled
import app.tingid.chain.hex
import app.tingid.chain.policyAccount
import app.tingid.chain.publicKey
import app.tingid.chain.sha256
import app.tingid.chain.signingDigest
import app.tingid.chain.unhex
import app.tingid.chain.verifyDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class SigningTest {
    private val chainId = filled(0x26)
    private val c = Contracts.canonical(chainId)
    private val now = 1_800_000_000L
    private val site = "https://forum.example"
    private val requestUrl = "$site/api/ting/requests/abc"
    private val callback = "$requestUrl/answer"
    private val file = utf8Bytes("the contract, as agreed")

    /** What the site answers, by address and method; every request it got. */
    private val answers = HashMap<String, (HttpRequest) -> HttpResponse>()
    private val asked: MutableList<HttpRequest> = Collections.synchronizedList(ArrayList())
    private val http = Http { r ->
        asked.add(r)
        answers["${r.method} ${r.url}"]?.invoke(r) ?: jsonResponse(buildJsonObject { put("message", "no such page") }, 404)
    }

    private val node = FakeRestNode(chainId)
    private val app = TestApp(node)
    private val signing = Signing(app.chain, http, now = { now })

    private val secret = filled(1)
    private val pub = publicKey(secret)
    private val key = KeyMeta("k1", "Anna · Android phone", Scheme.Secp256k1, pub.hex(), 0)
    private val single = User("u1", "Anna", "#000", UserKind.Key, keyId = key.id)
    private val identityId = filled(0x2e)
    private val identityUser = User("u2", "Anna", "#000", UserKind.Identity, identityId = identityId.hex(), registry = c.registry.hex(), chainId = chainId.hex(), keyId = key.id)
    private val identity = IdentityInfo(
        identityId,
        c.registry,
        Id.Core(0, 0, 0, 0, 4, 1, 0, 2, 0, 604_800, 172_800, 0),
        listOf(IdentityKey(3, Id.Key(c.policySecp256k1, pub))),
        listOf(IdentitySlot(0, 0), IdentitySlot(1, 604_800)),
        emptyList(),
    )

    init {
        app.secrets.saveKey(key.id, secret)
    }

    private fun utf8Bytes(s: String) = s.encodeToByteArray()

    private fun document(request: Offchain.Request = Offchain.Request.SignIn(now + 3600, null)) =
        Offchain.encodeDocument(Offchain.Document(request, "Sign in to Forum", "Welcome back.", emptyList()))

    private fun requestJson(document: ByteArray = document(), extra: Map<String, String> = emptyMap(), chain: ByteArray = chainId) = buildJsonObject {
        put("type", "ting-sign-request")
        put("version", 1)
        put("chain", chain.hex())
        put("nonce", "03".repeat(32))
        put("issued_at", now - 10)
        put("expires_at", now + 600)
        put("callback", callback)
        put("document", document.hex())
        for ((k, v) in extra) put(k, v)
    }

    private fun serve(json: JsonObject) {
        answers["GET $requestUrl"] = { jsonResponse(json) }
    }

    private fun fetched(json: JsonObject = requestJson()): SignRequest = runBlocking {
        serve(json)
        signing.fetchSignRequest(requestUrl)
    }

    private fun refusal(block: suspend () -> Unit): String? = runCatching { runBlocking { block() } }.exceptionOrNull().let {
        assertTrue("expected an OffchainError, got $it", it is OffchainError)
        it!!.message
    }

    // ---- Fetching ----------------------------------------------------------------------

    @Test
    fun `fetches a request as a stranger, and takes its origin from the address`() {
        val r = fetched()
        assertEquals(site, r.origin)
        assertEquals(callback, r.callback)
        val get = asked.single()
        assertEquals("GET", get.method)
        assertEquals("application/json", get.headers["accept"])
        assertTrue(get.headers.keys.none { it.equals("cookie", ignoreCase = true) })
        assertEquals(Signing.TIMEOUT_MS, get.timeoutMs)
        assertEquals(Signing.MAX_REPLY, get.maxBytes)
    }

    @Test
    fun `never follows a redirect`() {
        answers["GET $requestUrl"] = { HttpResponse(302, ByteArray(0), mapOf("location" to "https://elsewhere.example/r")) }
        assertEquals("the site sent the request on to another address, which Ting ID does not follow", refusal { signing.fetchSignRequest(requestUrl) })
        answers["GET $requestUrl"] = { HttpResponse(307, ByteArray(0)) }
        assertEquals("the site sent the request on to another address, which Ting ID does not follow", refusal { signing.fetchSignRequest(requestUrl) })
    }

    @Test
    fun `says why a request could not be fetched`() {
        answers["GET $requestUrl"] = { jsonResponse(buildJsonObject { put("message", "  this  request\nexpired ") }, 410) }
        assertEquals("the site says: “this request expired”", refusal { signing.fetchSignRequest(requestUrl) })
        answers["GET $requestUrl"] = { HttpResponse(500, "<html>".encodeToByteArray()) }
        assertEquals("the site answered 500", refusal { signing.fetchSignRequest(requestUrl) })
        answers["GET $requestUrl"] = { throw HttpFailure("no answer in time", timedOut = true) }
        assertEquals("the site did not answer in time", refusal { signing.fetchSignRequest(requestUrl) })
        answers["GET $requestUrl"] = { throw HttpFailure("the answer is too large", tooLarge = true) }
        assertEquals("the site sent an answer far too large", refusal { signing.fetchSignRequest(requestUrl) })
        answers["GET $requestUrl"] = { throw HttpFailure("could not be reached") }
        assertEquals("the site could not be reached", refusal { signing.fetchSignRequest(requestUrl) })
        answers["GET $requestUrl"] = { HttpResponse(200, ByteArray(Signing.MAX_REPLY + 1) { ' '.code.toByte() }) }
        assertEquals("the site sent an answer far too large", refusal { signing.fetchSignRequest(requestUrl) })
    }

    @Test
    fun `checks the request before anything of it is shown`() {
        serve(requestJson(extra = mapOf("callback" to "https://elsewhere.example/answer")))
        assertEquals("the answer would go to another site than the one asking", refusal { signing.fetchSignRequest(requestUrl) })
        answers["GET $requestUrl"] = { HttpResponse(200, "not json".encodeToByteArray()) }
        assertEquals("this is not a Ting signing request", refusal { signing.fetchSignRequest(requestUrl) })
    }

    // ---- Who may answer --------------------------------------------------------------

    @Test
    fun `answers only for the chain the app uses and the ID the request names`() {
        val r = fetched()
        val here = chainId.hex()
        assertEquals("the app is not connected to a node", signerProblem(r, single, null))
        assertEquals("this request is for another Ting network than the one this app uses", signerProblem(r, single, "ff".repeat(32)))
        assertEquals("create a user first", signerProblem(r, null, here))
        assertNull(signerProblem(r, single, here))
        assertNull(signerProblem(r, identityUser, here))
        assertEquals("this user belongs to another Ting network", signerProblem(r, identityUser.copy(chainId = "ff".repeat(32)), here))
        val named = fetched(requestJson(extra = mapOf("signer" to "77".repeat(32))))
        assertEquals("this request is for the ID 77777777…777777", signerProblem(named, identityUser, here))
        assertEquals("this request is for the ID 77777777…777777", signerProblem(named, single, here))
        val mine = fetched(requestJson(extra = mapOf("signer" to identityId.hex())))
        assertEquals(listOf(identityUser), possibleSigners(mine, listOf(single, identityUser), here))
        assertEquals(listOf(single, identityUser), possibleSigners(r, listOf(single, identityUser), here))
    }

    // ---- Signing and answering ---------------------------------------------------------

    private fun answered(): JsonObject = Json.parseToJsonElement(asked.last { it.method == "POST" }.body!!.decodeToString()).jsonObject
    private fun JsonObject.bytes(name: String) = unhex(getValue(name).jsonPrimitive.content)

    @Test
    fun `a key signs as its own account, and the site's words come back marked as its own`() = runBlocking {
        val r = fetched()
        answers["POST $callback"] = { jsonResponse(buildJsonObject { put("message", "Welcome back, Anna") }) }
        assertEquals("the site says: “Welcome back, Anna”", signing.signAndAnswer(r, single, c, listOf(key), null))
        val post = asked.last()
        assertEquals("application/json", post.headers["content-type"])
        val a = answered()
        assertEquals("ting-sign-answer", a.getValue("type").jsonPrimitive.content)
        assertArrayEquals(r.nonce, a.bytes("nonce"))
        assertArrayEquals(c.policySecp256k1, a.bytes("policy"))
        assertArrayEquals(pub, a.bytes("params"))
        val account = policyAccount(c.policySecp256k1, pub)
        assertArrayEquals(account, a.bytes("account"))
        assertTrue(verifyDigest(pub, signingDigest(Offchain.signedMessage(r, account)), a.bytes("witness")))
    }

    @Test
    fun `an identity signs with its spending account, naming its key`() = runBlocking {
        val r = fetched(requestJson(extra = mapOf("signer" to identityId.hex())))
        answers["POST $callback"] = { jsonResponse(JsonObject(emptyMap())) }
        assertEquals("Done.", signing.signAndAnswer(r, identityUser, c, listOf(key), identity))
        val a = answered()
        val params = Id.params(identityId, 0)
        assertArrayEquals(c.registry, a.bytes("policy"))
        assertArrayEquals(params, a.bytes("params"))
        val auth = Reader(a.bytes("witness"))
        assertEquals(3L, auth.u64())
        val signature = auth.bytes()
        auth.end()
        assertTrue(verifyDigest(pub, signingDigest(Offchain.signedMessage(r, policyAccount(c.registry, params))), signature))
    }

    @Test
    fun `a refused answer is an error in the site's words, or the app's`() {
        val r = fetched()
        answers["POST $callback"] = { jsonResponse(buildJsonObject { put("message", "this sign-in expired") }, 409) }
        assertEquals("the site says: “this sign-in expired”", refusal { signing.signAndAnswer(r, single, c, listOf(key), null) })
        answers["POST $callback"] = { HttpResponse(400, ByteArray(0)) }
        assertEquals("the site refused the answer (400)", refusal { signing.signAndAnswer(r, single, c, listOf(key), null) })
        answers["POST $callback"] = { HttpResponse(303, ByteArray(0), mapOf("location" to "$site/welcome")) }
        assertEquals("the site sent the request on to another address, which Ting ID does not follow", refusal { signing.signAndAnswer(r, single, c, listOf(key), null) })
    }

    @Test
    fun `signs nothing for another network or another ID, though the screen let it through`() {
        val elsewhere = fetched(requestJson(chain = filled(0x27)))
        assertEquals("this request is for another Ting network than the one this app uses", refusal { signing.signAndAnswer(elsewhere, single, c, listOf(key), null) })
        val named = fetched(requestJson(extra = mapOf("signer" to "77".repeat(32))))
        assertEquals("this request is for the ID 77777777…777777", refusal { signing.signAndAnswer(named, identityUser, c, listOf(key), identity) })
        assertTrue(asked.none { it.method == "POST" })
    }

    @Test
    fun `declining tells the site, and a site that cannot be reached does not matter`() = runBlocking {
        val r = fetched()
        answers["POST $callback"] = { jsonResponse(JsonObject(emptyMap())) }
        signing.declineRequest(r)
        val d = answered()
        assertEquals(true, d.getValue("declined").jsonPrimitive.boolean)
        assertArrayEquals(r.nonce, d.bytes("nonce"))
        assertEquals(setOf("type", "version", "nonce", "declined"), d.keys)
        answers["POST $callback"] = { throw HttpFailure("could not be reached") }
        signing.declineRequest(r)
    }

    // ---- A statement's file ---------------------------------------------------------------

    private fun statement(): SignRequest {
        val doc = document(Offchain.Request.Statement(Offchain.Attachment(sha256(file), "text/plain", "contract.txt")))
        return fetched(requestJson(doc, mapOf("file" to "$site/files/contract.txt")))
    }

    @Test
    fun `fetches a statement's file, and only that exact file`() = runBlocking {
        val r = statement()
        answers["GET $site/files/contract.txt"] = { HttpResponse(200, file) }
        assertArrayEquals(file, signing.fetchAttachment(r))
        val get = asked.last()
        assertEquals(Signing.MAX_FILE, get.maxBytes)
        assertTrue(get.headers.keys.none { it.equals("cookie", ignoreCase = true) })
        answers["GET $site/files/contract.txt"] = { HttpResponse(200, utf8Bytes("another contract")) }
        assertEquals("the site sent another file than the one the statement covers", refusal { signing.fetchAttachment(r) })
        answers["GET $site/files/contract.txt"] = { HttpResponse(301, ByteArray(0), mapOf("location" to "$site/other")) }
        assertEquals("the site sent the file request on to another address, which Ting ID does not follow", refusal { signing.fetchAttachment(r) })
        answers["GET $site/files/contract.txt"] = { HttpResponse(404, ByteArray(0)) }
        assertEquals("the site answered 404 for the file", refusal { signing.fetchAttachment(r) })
        answers["GET $site/files/contract.txt"] = { throw HttpFailure("no answer in time", timedOut = true) }
        assertEquals("the site did not send the file in time", refusal { signing.fetchAttachment(r) })
        answers["GET $site/files/contract.txt"] = { throw HttpFailure("could not be reached") }
        assertEquals("the file could not be fetched", refusal { signing.fetchAttachment(r) })
        assertEquals("this request covers no file", refusal { signing.fetchAttachment(fetched()) })
    }

    // ---- The site's words ---------------------------------------------------------------

    @Test
    fun `the site's words are one line, short, and cannot pass for the app's`() {
        fun said(m: String) = Signing.siteMessage(buildJsonObject { put("message", m) }, "fallback")
        assertEquals("the site says: “a b c”", said("a\n\tb  c"))
        assertEquals("fallback", said(" \n "))
        assertEquals("fallback", Signing.siteMessage(buildJsonObject { put("message", 5) }, "fallback"))
        assertEquals("fallback", Signing.siteMessage(null, "fallback"))
        assertEquals("the site says: “${"x".repeat(240)}…”", said("x".repeat(300)))
        // A direction override cannot turn the closing mark around, nor can hidden characters hide words.
        assertEquals("the site says: “paid, done”", said("paid,‮ done​"))
        // A cut never splits a character in two.
        val emoji = "😀"
        assertEquals("the site says: “${"x".repeat(239)}…”", said("x".repeat(239) + emoji + "x"))
    }
}
