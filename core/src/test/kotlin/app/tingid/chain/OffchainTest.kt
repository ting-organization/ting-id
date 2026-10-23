// TOCS-1 (TOCS-1.md): the wallet's side against docs/tocs-1-vectors.json, which
// the TypeScript reference implementation wrote, and the cases a wallet must
// refuse.
package app.tingid.chain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

private val tocs: JsonObject = Json.parseToJsonElement(
    File(System.getProperty("ting.repo") ?: "..", "docs/tocs-1-vectors.json").readText(),
).jsonObject

private val JsonElement.text: String get() = jsonPrimitive.content
private val JsonElement.textOrNull: String? get() = if (this is JsonNull) null else jsonPrimitive.content

class OffchainTest {
    private fun documentOf(j: JsonObject): Offchain.Document {
        val q = j.getValue("request").jsonObject
        val request = when (q.getValue("kind").text) {
            "sign_in" -> Offchain.Request.SignIn(q.getValue("session_until").jsonPrimitive.long, q.getValue("key").textOrNull?.let(::unhex))
            "grant" -> Offchain.Request.Grant(
                q.getValue("until").jsonPrimitive.long,
                q.getValue("scopes").jsonArray.map { it.text },
                q.getValue("key").textOrNull?.let(::unhex),
            )
            else -> Offchain.Request.Statement(
                (q.getValue("attachment") as? JsonObject)?.let { Offchain.Attachment(unhex(it.getValue("hash").text), it.getValue("media_type").text, it.getValue("name").text) },
            )
        }
        return Offchain.Document(
            request,
            j.getValue("title").text,
            j.getValue("body").text,
            j.getValue("fields").jsonArray.map { Offchain.Field(it.jsonArray[0].text, it.jsonArray[1].text) },
        )
    }

    @Test
    fun `documents encode to the vectors' bytes and decode back`() {
        val documents = tocs.getValue("documents").jsonArray
        assertEquals(5, documents.size)
        for (d in documents) {
            val o = d.jsonObject
            val doc = documentOf(o.getValue("document").jsonObject)
            val bytes = Offchain.encodeDocument(doc)
            assertEquals(o.getValue("name").text, o.getValue("hex").text, bytes.hex())
            assertEquals(o.getValue("hash").text, Offchain.documentHash(bytes).hex())
            assertEquals(doc, Offchain.decodeDocument(bytes))
        }
    }

    @Test
    fun `refused documents are refused`() {
        val refused = tocs.getValue("refused_documents").jsonArray
        assertTrue(refused.size >= 20)
        for (r in refused) {
            val o = r.jsonObject
            assertThrows(o.getValue("name").text, OffchainError::class.java) { Offchain.decodeDocument(unhex(o.getValue("hex").text)) }
        }
        // And one of 65,537 bytes, which the vectors leave out for its size.
        val big = Writer().u8(1).u8(3).u8(0).bytes(utf8("T")).bytes(utf8("a".repeat(65_537 - 16))).u32(0).finish()
        assertEquals(65_537, big.size)
        assertThrows(OffchainError::class.java) { Offchain.decodeDocument(big) }
        val justFits = Writer().u8(1).u8(3).u8(0).bytes(utf8("T")).bytes(utf8("a".repeat(65_536 - 16))).u32(0).finish()
        assertEquals(65_536, justFits.size)
        Offchain.decodeDocument(justFits)
    }

    @Test
    fun `origins are the vectors'`() {
        for (o in tocs.getValue("origins").jsonArray.map { it.jsonObject }) {
            assertEquals(o.getValue("address").text, o.getValue("origin").textOrNull, Offchain.originOf(o.getValue("address").text))
        }
        // And what only a stricter reading refuses.
        assertNull(Offchain.originOf("https://forum.example\\@evil.example/"))
        assertNull(Offchain.originOf("https://forum.example:0/"))
        assertNull(Offchain.originOf("https://forum.example:65536/"))
        assertNull(Offchain.originOf("https://forum.example /"))
        assertNull(Offchain.originOf("https://.forum.example/"))
    }

    @Test
    fun `sign codes are the vectors'`() {
        for (o in tocs.getValue("sign_codes").jsonArray.map { it.jsonObject }) {
            assertEquals(o.getValue("code").text, o.getValue("url").textOrNull, Offchain.parseSignCode(o.getValue("code").text))
        }
        val first = tocs.getValue("sign_codes").jsonArray[0].jsonObject
        assertEquals(first.getValue("code").text, Offchain.signCode(first.getValue("url").text))
    }

    @Test
    fun `match codes are the vectors'`() {
        for (o in tocs.getValue("match_codes").jsonArray.map { it.jsonObject }) {
            assertEquals(o.getValue("code").text, Offchain.matchCode(unhex(o.getValue("nonce").text)))
        }
    }

    private val request = tocs.getValue("request").jsonObject
    private val requestJson = request.getValue("json").jsonObject
    private val fetchedFrom = request.getValue("fetched_from").text
    private val now = request.getValue("now").jsonPrimitive.long

    private fun read(json: JsonObject, from: String = fetchedFrom, at: Long = now) = Offchain.readRequest(json, from, at)
    private fun JsonObject.with(vararg changes: Pair<String, JsonElement?>): JsonObject =
        JsonObject(toMutableMap().also { m -> for ((k, v) in changes) if (v == null) m.remove(k) else m[k] = v })

    @Test
    fun `the request is read with the origin it was fetched from`() {
        val r = read(requestJson)
        assertEquals(request.getValue("origin").text, r.origin)
        assertEquals("https://forum.example/sign-in", r.returnTo)
        fun refuses(why: String, json: JsonObject, from: String = fetchedFrom, at: Long = now) {
            val e = assertThrows(OffchainError::class.java) { read(json, from, at) }
            assertTrue("${e.message} should say $why", e.message!!.contains(why))
        }
        refuses("another site", requestJson.with("callback" to JsonPrimitive("https://evil.example/answer")))
        refuses("back to another site", requestJson.with("return" to JsonPrimitive("https://evil.example/")))
        refuses("another site", requestJson, from = "https://evil.example/relay")
        refuses("expired", requestJson, at = requestJson.getValue("expires_at").jsonPrimitive.long)
        refuses("clock", requestJson.with("issued_at" to JsonPrimitive(now + 301), "expires_at" to JsonPrimitive(now + 400)))
        refuses("clock", requestJson.with("expires_at" to JsonPrimitive(requestJson.getValue("issued_at").jsonPrimitive.long + 86_401)))
        refuses("nonce", requestJson.with("nonce" to JsonPrimitive("00")))
        refuses("whole number", requestJson.with("issued_at" to JsonPrimitive("1792000000")))
        refuses("whole number", requestJson.with("issued_at" to JsonPrimitive(1.5)))
        refuses("whole number", requestJson.with("issued_at" to Json.parseToJsonElement("1.792E9")))
        refuses("whole number", requestJson.with("issued_at" to JsonPrimitive(-1)))
        refuses("newer", requestJson.with("version" to JsonPrimitive(2)))
        refuses("not a Ting", requestJson.with("type" to JsonPrimitive("ting-sign-answer")))
        refuses("signer", requestJson.with("signer" to JsonPrimitive("12")))
        refuses("does not cover", requestJson.with("file" to JsonPrimitive("https://forum.example/x.pdf")))
        refuses("secure sites", requestJson, from = "http://forum.example/r")
    }

    @Test
    fun `a statement's file is the one it covers, from the same site`() {
        val pdf = utf8("%PDF-1.7 the agreement")
        val covering = tocs.getValue("documents").jsonArray[3].jsonObject.getValue("hex").text
        val statement = requestJson.with("document" to JsonPrimitive(covering), "file" to JsonPrimitive("https://forum.example/files/agreement.pdf"))
        val r = read(statement)
        assertEquals("https://forum.example/files/agreement.pdf", r.file)
        assertThrows(OffchainError::class.java) { read(statement.with("file" to null)) }
        assertThrows(OffchainError::class.java) { read(statement.with("file" to JsonPrimitive("https://evil.example/agreement.pdf"))) }
        val attachment = (r.request.request as Offchain.Request.Statement).attachment!!
        assertTrue(Offchain.isAttachment(pdf, attachment))
        assertTrue(!Offchain.isAttachment(utf8("%PDF-1.7 another agreement"), attachment))
    }

    @Test
    fun `the identity answer is the reference implementation's, byte for byte`() {
        val a = tocs.getValue("identity_answer").jsonObject
        fun b(name: String) = unhex(a.getValue(name).text)
        val chain = unhex(tocs.getValue("chain_id").text)
        val c = Contracts.canonical(chain)
        assertEquals(tocs.getValue("canonical").jsonObject.getValue("id_registry").text, c.registry.hex())
        val r = read(requestJson)
        val id = b("identity")
        val slot = a.getValue("slot").jsonPrimitive.long
        assertArrayEquals(b("params"), Id.params(id, slot))
        val account = Id.account(c.registry, id, slot)
        assertArrayEquals(b("account"), account)
        val message = Offchain.signedMessage(r, account)
        assertArrayEquals(b("message"), message)
        assertArrayEquals(b("digest"), signingDigest(message))
        val signer = Signer(MemoryKey(b("secret_key")), a.getValue("key_id").jsonPrimitive.long, c.registry, Id.params(id, slot))
        assertArrayEquals(b("witness"), signer.witness(message))
        val answer = Offchain.answerJson(r.nonce, signer.account, signer.policy, signer.params, signer.witness(message))
        assertEquals(a.getValue("answer"), answer)
        // The records the site reads.
        assertArrayEquals(b("core_record_key"), Id.coreKey(id))
        val core = Id.decodeCore(b("core_record"))!!
        assertArrayEquals(b("key_record_key"), Id.keyKey(id, core.keyEpoch, a.getValue("key_id").jsonPrimitive.long))
        assertEquals(Id.Key(c.policySecp256k1, b("public_key")), Id.decodeKeyRecord(b("key_record")))
        assertEquals(tocs.getValue("decline"), Offchain.declineJson(r.nonce))
    }

    @Test
    fun `plain answers sign as the reference does`() {
        val r = read(requestJson)
        val chain = unhex(tocs.getValue("chain_id").text)
        val c = Contracts.canonical(chain)
        val plain = tocs.getValue("plain_answers").jsonArray[0].jsonObject
        val key = MemoryKey(unhex(plain.getValue("secret_key").text))
        val account = policyAccount(c.policySecp256k1, key.publicKey)
        assertEquals(plain.getValue("account").text, account.hex())
        val message = Offchain.signedMessage(r, account)
        assertEquals(plain.getValue("message").text, message.hex())
        assertEquals(plain.getValue("signature").text, Signer(key, null, c.policySecp256k1, key.publicKey).witness(message).hex())
        // The P-256 one verifies as the spec says a site checks it.
        val p256 = tocs.getValue("plain_answers").jsonArray[1].jsonObject
        assertTrue(isPublicKey(Scheme.P256, unhex(p256.getValue("public_key").text)))
        assertEquals(p256.getValue("account").text, policyAccount(c.policyP256, unhex(p256.getValue("public_key").text)).hex())
    }

    @Test
    fun `the message binds everything that matters`() {
        val r = read(requestJson)
        val account = filled(1)
        val base = Offchain.signedMessage(r, account).hex()
        assertTrue(base.startsWith(Writer().bytes(utf8("app/offchain-v1")).finish().hex()))
        assertTrue(base.endsWith(Offchain.documentHash(r.document).hex()))
        fun changed(origin: String = r.origin, nonce: ByteArray = r.nonce, chain: ByteArray = r.chainId, expires: Long = r.expiresAt) =
            SignRequest(chain, origin, nonce, r.issuedAt, expires, r.document, r.signer, r.request, r.callback, r.returnTo, r.file)
        for (other in listOf(changed(origin = "https://evil.example"), changed(nonce = filled(4)), changed(chain = filled(0xaa)), changed(expires = r.expiresAt + 1))) {
            assertNotEquals(base, Offchain.signedMessage(other, account).hex())
        }
        assertNotEquals(base, Offchain.signedMessage(r, filled(2)).hex())
    }

    private fun blocksJson(blocks: List<Offchain.Block>): JsonArray = JsonArray(
        blocks.map { b ->
            fun spans(s: List<Offchain.Span>) = JsonArray(s.map { JsonObject(mapOf("text" to JsonPrimitive(it.text), "bold" to JsonPrimitive(it.bold), "italic" to JsonPrimitive(it.italic))) })
            when (b) {
                is Offchain.Block.Paragraph -> JsonObject(mapOf("type" to JsonPrimitive("paragraph"), "lines" to JsonArray(b.lines.map(::spans))))
                is Offchain.Block.ListBlock -> JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("list"),
                        "items" to JsonArray(b.items.map { JsonObject(mapOf("marker" to JsonPrimitive(it.marker), "spans" to spans(it.spans))) }),
                    ),
                )
            }
        },
    )

    @Test
    fun `bodies lay out as the vectors'`() {
        for (o in tocs.getValue("bodies").jsonArray.map { it.jsonObject }) {
            assertEquals(o.getValue("body").text, o.getValue("blocks"), blocksJson(Offchain.bodyBlocks(o.getValue("body").text)))
        }
    }

    @Test
    fun `a long body of stray markers is shown as written, quickly`() {
        val body = "*a ".repeat(21_000)
        val started = System.nanoTime()
        val blocks = Offchain.bodyBlocks(body)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 500)
        val lines = (blocks.single() as Offchain.Block.Paragraph).lines
        val span = lines.single().single()
        assertEquals(body.trim(), span.text.trim())
        assertTrue(!span.bold && !span.italic)
    }

    @Test
    fun `scopes are refused when not lowercase`() {
        assertThrows(OffchainError::class.java) {
            Offchain.encodeDocument(Offchain.Document(Offchain.Request.Grant(0, listOf("Post Things"), null), "T", "", emptyList()))
        }
    }

    @Test
    fun `the decline is only what the spec says`() {
        val decline = Offchain.declineJson(filled(3))
        assertEquals(setOf("type", "version", "nonce", "declined"), decline.keys)
        assertTrue(decline.getValue("declined").jsonPrimitive.boolean)
    }
}
