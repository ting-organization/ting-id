// TCR-1 (TCR-1.md): the wallet's side against docs/tcr-1-vectors.json, which
// the canvas page's tcr.js wrote and the canvas program's Rust checks too.
package app.tingid.chain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.math.BigInteger

private val tcr: JsonObject = Json.parseToJsonElement(
    File(System.getProperty("ting.repo") ?: "..", "docs/tcr-1-vectors.json").readText(),
).jsonObject

private val JsonElement.text: String get() = jsonPrimitive.content
private val JsonElement.bytes: ByteArray get() = unhex(text)
private val JsonElement.long: Long get() = text.toLong()
private fun JsonObject.items(key: String): List<JsonObject> = getValue(key).jsonArray.map { it.jsonObject }

class TcrVectorsTest {
    private val chain = tcr.getValue("chain_id").bytes
    private val program = tcr.getValue("program").bytes

    /** A type as the vectors write it: its name, `{"enum": labels}` or `{"list": fields}`. */
    private fun typeOf(t: SlotType): JsonElement = when (t) {
        is SlotType.Integer -> JsonPrimitive("u${t.size * 8}")
        SlotType.Amount -> JsonPrimitive("amount")
        SlotType.CoinId -> JsonPrimitive("coin")
        SlotType.Account -> JsonPrimitive("account")
        SlotType.Bytes32 -> JsonPrimitive("bytes32")
        SlotType.Time -> JsonPrimitive("time")
        SlotType.Duration -> JsonPrimitive("duration")
        SlotType.Text -> JsonPrimitive("text")
        SlotType.Bytes -> JsonPrimitive("bytes")
        is SlotType.Enum -> buildJsonObject { putJsonArray("enum") { t.labels.forEach { add(JsonPrimitive(it)) } } }
        is SlotType.ListOf -> buildJsonObject { put("list", fieldsOf(t.fields)) }
    }

    private fun fieldsOf(fields: List<Slot>): JsonArray = JsonArray(
        fields.map {
            buildJsonObject {
                put("name", it.name)
                put("type", typeOf(it.type))
            }
        },
    )

    private fun sentenceOf(t: Template): JsonArray = JsonArray(
        t.parts.map { p ->
            when (p) {
                is Template.Part.Words -> buildJsonObject { put("text", p.text) }
                is Template.Part.Field -> buildJsonObject { put("slot", t.fields[p.index].name) }
                Template.Part.Payment -> buildJsonObject { put("payment", true) }
            }
        },
    )

    /** A decoded value as the vectors write it: integers in decimal, bytes in hex. */
    private fun valueOf(v: SlotValue): JsonElement = when (v) {
        is SlotValue.Integer -> JsonPrimitive(v.value.toString())
        is SlotValue.Amount -> buildJsonObject {
            put("coin", v.coin.hex())
            put("amount", v.value.toString())
        }
        is SlotValue.CoinId -> JsonPrimitive(v.id.hex())
        is SlotValue.Account -> JsonPrimitive(v.account.hex())
        is SlotValue.Bytes32 -> JsonPrimitive(v.bytes.hex())
        is SlotValue.Time -> JsonPrimitive(v.at.toString())
        is SlotValue.Duration -> JsonPrimitive(v.seconds.toString())
        is SlotValue.Text -> JsonPrimitive(v.text)
        is SlotValue.Bytes -> JsonPrimitive(v.bytes.hex())
        is SlotValue.Enum -> buildJsonObject {
            put("index", v.index)
            put("label", v.label)
        }
        is SlotValue.ListOf -> JsonArray(v.items.map { item -> buildJsonObject { v.fields.zip(item).forEach { (f, x) -> put(f.name, valueOf(x)) } } })
    }

    private fun decodedOf(t: Template, values: List<SlotValue>): JsonObject =
        buildJsonObject { t.fields.zip(values).forEach { (f, v) -> put(f.name, valueOf(v)) } }

    @Test
    fun `templates parse into their declarations and sentences`() {
        val templates = tcr.items("templates")
        assertEquals(16, templates.size)
        for (j in templates) {
            val name = j.getValue("name").text
            val t = Template.parse(j.getValue("template").text)
            assertEquals(name, j.getValue("hex").text, utf8(t.text).hex())
            assertEquals(name, j.getValue("T").text, t.hash.hex())
            assertEquals(name, j.getValue("operation").text, t.operation)
            assertEquals(name, j.getValue("declaration").text, t.declaration)
            assertEquals(name, j.getValue("fields"), fieldsOf(t.fields))
            assertEquals(name, j.getValue("sentence"), sentenceOf(t))
        }
    }

    @Test
    fun `refused templates are refused`() {
        val refused = tcr.items("refused_templates")
        assertEquals(27, refused.size)
        for (j in refused) {
            val text = String(j.getValue("hex").bytes, Charsets.UTF_8)
            assertThrows(j.getValue("name").text, CallError::class.java) { Template.parse(text) }
        }
    }

    @Test
    fun `values decode as the vectors do, and refused ones are refused`() {
        for (j in tcr.items("values")) {
            val name = j.getValue("name").text
            val t = Template.parse(j.getValue("template").text)
            val bytes = j.getValue("values").bytes
            assertEquals(name, j.getValue("decoded"), decodedOf(t, decodeValues(t, bytes)))
            assertEquals(name, j.getValue("operation").text, Tcr.operationHash(t.hash, bytes).hex())
        }
        for (j in tcr.items("refused_values")) {
            val t = Template.parse(j.getValue("template").text)
            assertThrows(j.getValue("name").text, CallError::class.java) { decodeValues(t, j.getValue("values").bytes) }
        }
    }

    @Test
    fun `operations, messages and digests`() {
        for (j in tcr.items("operations")) {
            val name = j.getValue("name").text
            val t = Template.parse(j.getValue("template").text)
            val operation = Tcr.operationHash(t.hash, j.getValue("values").bytes)
            assertEquals(name, j.getValue("T").text, t.hash.hex())
            assertEquals(name, j.getValue("operation").text, operation.hex())
            val message = Tcr.message(chain, program, j.getValue("account").bytes, j.getValue("nonce").long, j.getValue("valid_until").long, operation)
            assertEquals(name, j.getValue("message").text, message.hex())
            assertEquals(name, j.getValue("digest").text, signingDigest(message).hex())
        }
    }

    @Test
    fun `calls of every kind of approval`() {
        val calls = tcr.items("calls")
        assertEquals(setOf("witness", "payment", "fee", "program"), calls.map { it.getValue("kind").text }.toSet())
        for (j in calls) {
            val name = j.getValue("name").text
            val t = Template.parse(j.getValue("template").text)
            val values = j.getValue("values").bytes
            val operation = Tcr.operationHash(t.hash, values)
            if (j.getValue("kind").text == "program") {
                val account = contractAccount(j.getValue("caller").bytes)
                assertEquals(name, j.getValue("account").text, account.hex())
                assertEquals(name, j.getValue("call").text, (t.hash + values + byteArrayOf(1, 0)).hex())
                assertEquals(name, j.getValue("executed").text, (Tcr.EXECUTED_TOPIC + account + u64le(0) + operation).hex())
                continue
            }
            val policy = j.getValue("policy").bytes
            val params = j.getValue("params").bytes
            val account = policyAccount(policy, params)
            val nonce = j.getValue("nonce").long
            val validUntil = j.getValue("valid_until").long
            assertEquals(name, j.getValue("account").text, account.hex())
            val message = Tcr.message(chain, program, account, nonce, validUntil, operation)
            assertEquals(name, j.getValue("message").text, message.hex())
            val digest = signingDigest(message)
            assertEquals(name, j.getValue("digest").text, digest.hex())
            val witness = j.getValue("witness").bytes
            val payment = (j["payment"] as? JsonObject)?.let { pay ->
                val coin = pay.getValue("coin").bytes
                val coinNonce = pay.getValue("coin_nonce").long
                val effect = Coin.readEffect(Reader(pay.getValue("effect").bytes))
                // A fee payment moves nothing: a transfer of 0 to the program's own account.
                if (j.getValue("kind").text == "fee") assertEquals(name, Tcr.feeEffect(program), effect)
                val action = Coin.Action(effect, Coin.Timing(fee = BigInteger(pay.getValue("fee").text), validUntil = validUntil, within = Coin.Within(program, digest)))
                assertEquals(name, pay.getValue("action").text, Writer().also { Coin.encodeAction(it, action) }.finish().hex())
                val coinMessage = Coin.message(chain, coin, account, coinNonce, action)
                assertEquals(name, pay.getValue("coin_message").text, coinMessage.hex())
                assertEquals(name, pay.getValue("coin_digest").text, signingDigest(coinMessage).hex())
                val input = Coin.executeInput(Coin.PolicyAuth(policy, params, coinNonce, pay.getValue("coin_witness").bytes), action)
                assertEquals(name, pay.getValue("coin_input").text, input.hex())
                // A payment from the acting account is its approval: the call's own witness is empty.
                assertEquals(name, 0, witness.size)
                Tcr.Payment(coin, input)
            }
            val call = Tcr.callInput(t.hash, values, Tcr.Auth(policy, params, nonce, validUntil, witness), payment)
            assertEquals(name, j.getValue("call").text, call.hex())
        }
    }

    @Test
    fun `compressed values unpack exactly, or are refused`() {
        for (j in tcr.items("zvalues")) {
            val name = j.getValue("name").text
            val packed = fromBase64url(j.getValue("zvalues").text)
            assertNotNull(name, packed)
            when {
                j.containsKey("refused") -> assertThrows(name, CallError::class.java) { Tcr.inflate(packed!!) }
                j.containsKey("values") -> assertEquals(name, j.getValue("values").text, Tcr.inflate(packed!!).hex())
                else -> assertEquals(name, j.getValue("unpacked_length").long, Tcr.inflate(packed!!).size.toLong())
            }
        }
    }

    @Test
    fun `template lists, and the ones a wallet refuses`() {
        val lists = tcr.getValue("template_lists").jsonObject
        assertEquals(lists.getValue("key").text, Tcr.TEMPLATES_KEY.hex())
        for (j in lists.items("lists")) {
            val templates = Tcr.decodeTemplateList(j.getValue("hex").bytes)
            assertEquals(j.getValue("name").text, j.getValue("templates").jsonArray.map { it.text }, templates)
            for (text in templates!!) assertNull(j.getValue("name").text, Tcr.listProblem(templates, Template.parse(text)))
        }
        for (j in lists.items("refused")) {
            val name = j.getValue("name").text
            val templates = Tcr.decodeTemplateList(j.getValue("hex").bytes)
            when (j.getValue("rule").text) {
                "malformed" -> assertNull(name, templates)
                else -> {
                    val under = j["link_template"]?.text ?: templates!!.first()
                    assertNotNull(name, Tcr.listProblem(templates!!, Template.parse(under)))
                }
            }
        }
        val canvas = Tcr.decodeTemplateList(tcr.getValue("canvas").jsonObject.getValue("template_list").bytes)!!
        for (text in canvas) assertNull(text, Tcr.listProblem(canvas, Template.parse(text)))
    }

    @Test
    fun `a scheduled operation, its run, and a cancel`() {
        val s = tcr.getValue("scheduled").jsonObject
        val t = Template.parse(s.getValue("template").text)
        val values = s.getValue("values").bytes
        val account = policyAccount(s.getValue("policy").bytes, s.getValue("params").bytes)
        val sequence = s.getValue("sequence").long
        val executeAt = s.getValue("execute_at").long
        assertEquals(s.getValue("account").text, account.hex())
        assertEquals(s.getValue("key").text, Tcr.scheduledKey(account, sequence).hex())
        assertEquals(s.getValue("record").text, (u64le(executeAt) + t.hash + values).hex())
        assertEquals(s.getValue("event").text, (Tcr.SCHEDULED_TOPIC + account + u64le(sequence) + u64le(executeAt) + t.hash + values).hex())
        assertEquals(s.getValue("run").text, Tcr.runInput(account, sequence).hex())
        assertEquals(s.getValue("executed").text, (Tcr.EXECUTED_TOPIC + account + u64le(sequence) + Tcr.operationHash(t.hash, values)).hex())

        val c = tcr.getValue("cancel").jsonObject
        val policy = c.getValue("policy").bytes
        val params = c.getValue("params").bytes
        val sequences = c.getValue("sequences").jsonArray.map { it.long }
        val validUntil = c.getValue("valid_until").long
        val canceller = policyAccount(policy, params)
        assertEquals(c.getValue("account").text, canceller.hex())
        val message = Tcr.cancelMessage(chain, program, canceller, validUntil, sequences)
        assertEquals(c.getValue("message").text, message.hex())
        assertEquals(c.getValue("digest").text, signingDigest(message).hex())
        assertEquals(c.getValue("input").text, Tcr.cancelInput(policy, params, validUntil, c.getValue("witness").bytes, sequences).hex())
        assertEquals(c.getValue("cancelled").jsonArray.map { it.text }, sequences.map { (Tcr.CANCELLED_TOPIC + canceller + u64le(it)).hex() })
    }

    @Test
    fun `links read as the vectors say, and refused ones are refused`() {
        for (j in tcr.items("links")) {
            val name = j.getValue("name").text
            val link = Links.parse(j.getValue("link").text) as Link.Call
            assertEquals(name, j.getValue("template").text, link.template.text)
            assertEquals(name, j.getValue("values").text, link.values.hex())
            assertEquals(name, j["coin"]?.text, link.coin)
            assertEquals(name, j["burn"]?.text, link.burn)
            assertEquals(name, j["to"]?.text, link.to)
            assertEquals(name, j["amount"]?.text, link.amount)
            assertEquals(name, j["from"]?.text, link.from)
            assertEquals(name, j["delayed"]?.text == "1", link.delayed)
            assertEquals(name, j["return"]?.text, link.returnTo)
        }
        val refused = tcr.items("refused_links")
        assertTrue(refused.size >= 22)
        for (j in refused) assertThrows(j.getValue("name").text, LinkError::class.java) { Links.parse(j.getValue("link").text) }
    }
}
