// Ting Call Requests (TCR-1.md): an operation of a program in the program's
// own words, a template and its values, and the call that does it for an
// account. The template is checked by the program itself: its hash starts
// the call, and the program accepts only its own.
package app.tingid.chain

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** Why a template, its values or a call link cannot be used. */
class CallError(message: String) : Exception(message)

object Tcr {
    const val TAG = "app/call-v1"

    /** The longest template, in UTF-8 bytes. */
    const val TEMPLATE_MAX = 512

    /** The most a link's compressed values may unpack to. */
    const val UNPACKED_MAX = 65_536

    val RUN: ByteArray get() = taggedHash("app/call-v1/run")
    val CANCEL: ByteArray get() = taggedHash("app/call-v1/cancel")
    val SCHEDULED_TOPIC: ByteArray get() = taggedHash("app/call-v1/event/scheduled")
    val EXECUTED_TOPIC: ByteArray get() = taggedHash("app/call-v1/event/executed")
    val CANCELLED_TOPIC: ByteArray get() = taggedHash("app/call-v1/event/cancelled")

    // ---- Records -----------------------------------------------------------------

    val TEMPLATES_KEY: ByteArray get() = taggedHash("app/call-v1/templates")
    fun nonceKey(account: ByteArray): ByteArray = taggedHash("app/call-v1/nonce", account)
    fun scheduledKey(account: ByteArray, sequence: Long): ByteArray = taggedHash("app/call-v1/scheduled", account, u64le(sequence))

    /** The template list, `u64 count ‖ count × Bytes(template)`, each template valid UTF-8 in its shortest form. */
    fun decodeTemplateList(bytes: ByteArray): List<String>? = tryDecode {
        val r = Reader(bytes)
        val count = r.u64()
        // Each takes at least its 4-byte length: a count beyond that is a lie, not a reason to loop.
        if (count > r.remaining / 4) throw DecodeError("truncated")
        val out = (0 until count).map { strictUtf8(r.bytes()) ?: throw DecodeError("not UTF-8") }
        r.end()
        out
    }

    /**
     * Why a program's template list cannot vouch for a call under `template`, or
     * null: it holds that template, each template once, and every template of the
     * same operation with the same declaration, so the program reads its values
     * one way. Listed templates that break the rules hold no declaration to
     * compare, and are left out of that check.
     */
    fun listProblem(templates: List<String>, template: Template): String? {
        if (templates.toSet().size != templates.size) return "the program lists a template twice"
        if (template.text !in templates) return "the program has no operation that reads like this: the link may have been changed"
        for (text in templates) {
            val other = try {
                Template.parse(text)
            } catch (e: CallError) {
                continue
            }
            if (other.operation == template.operation && other.declaration != template.declaration) {
                return "the program reads this operation’s values in two ways, which a program must not"
            }
        }
        return null
    }

    /** The nonce record of an account; absent means 0. */
    fun decodeNonce(bytes: ByteArray?): Long? = if (bytes == null) 0 else tryDecode { Reader(bytes).run { u64().also { end() } } }

    // ---- Hashes and messages -----------------------------------------------------

    fun templateHash(template: String): ByteArray = taggedHash("app/call-v1/template", utf8(template))

    fun operationHash(t: ByteArray, values: ByteArray): ByteArray = taggedHash("app/call-v1/operation", t, values)

    /** What a policy account approves: `Bytes(tag) ‖ chain ‖ program ‖ account ‖ 01 ‖ u64 nonce ‖ u64 valid_until ‖ operation`. */
    fun message(chain: ByteArray, program: ByteArray, account: ByteArray, nonce: Long, validUntil: Long, operation: ByteArray): ByteArray =
        Writer().bytes(utf8(TAG)).put32(chain).put32(program).put32(account).u8(1).u64(nonce).u64(validUntil).put32(operation).finish()

    // ---- The call ----------------------------------------------------------------

    /** A policy account's approval: its witness over the message's digest, or empty when the call's payment is its own. */
    class Auth(val policy: ByteArray, val params: ByteArray, val nonce: Long, val validUntil: Long, val witness: ByteArray)

    /** A coin payment: the coin and its authorized action's input (operation 10), bound to the program and the digest. */
    class Payment(val coin: ByteArray, val input: ByteArray)

    /** A fee payment's effect: a transfer of nothing to the program's own account, which only carries the fee. */
    fun feeEffect(program: ByteArray): Coin.Effect = Coin.Effect.Transfer(contractAccount(program), BigInteger.ZERO)

    /** A run of a scheduled operation: `H("app/call-v1/run", "") ‖ account ‖ u64 sequence`. */
    fun runInput(account: ByteArray, sequence: Long): ByteArray = Writer().put32(RUN).put32(account).u64(sequence).finish()

    /** What a policy account approves to cancel `sequences`: `Bytes(tag) ‖ chain ‖ program ‖ account ‖ 02 ‖ u64 valid_until ‖ u64 count ‖ sequences`. */
    fun cancelMessage(chain: ByteArray, program: ByteArray, account: ByteArray, validUntil: Long, sequences: List<Long>): ByteArray {
        val w = Writer().bytes(utf8(TAG)).put32(chain).put32(program).put32(account).u8(2).u64(validUntil).u64(sequences.size)
        for (s in sequences) w.u64(s)
        return w.finish()
    }

    /** A cancel: `H("app/call-v1/cancel", "") ‖ policy ‖ Bytes(params) ‖ u64 valid_until ‖ Bytes(witness) ‖ u64 count ‖ sequences`. */
    fun cancelInput(policy: ByteArray, params: ByteArray, validUntil: Long, witness: ByteArray, sequences: List<Long>): ByteArray {
        val w = Writer().put32(CANCEL).put32(policy).bytes(params).u64(validUntil).bytes(witness).u64(sequences.size)
        for (s in sequences) w.u64(s)
        return w.finish()
    }

    /** `T ‖ values ‖ 00 ‖ policy ‖ Bytes(params) ‖ u64 nonce ‖ u64 valid_until ‖ Bytes(witness) ‖ payments`. */
    fun callInput(t: ByteArray, values: ByteArray, auth: Auth, payment: Payment?): ByteArray {
        val w = Writer().put32(t).put(values)
            .u8(0).put32(auth.policy).bytes(auth.params).u64(auth.nonce).u64(auth.validUntil).bytes(auth.witness)
        if (payment == null) w.u8(0) else w.u8(1).put32(payment.coin).bytes(payment.input)
        return w.finish()
    }

    /** The values a link carries compressed: raw DEFLATE, unpacked exactly, at most [UNPACKED_MAX] bytes. */
    fun inflate(packed: ByteArray): ByteArray {
        val inflater = Inflater(true)
        try {
            inflater.setInput(packed)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (!inflater.finished()) {
                val n = try {
                    inflater.inflate(buf)
                } catch (e: DataFormatException) {
                    throw CallError("the link’s compressed values are not written right")
                }
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) throw CallError("the link’s compressed values end too soon")
                out.write(buf, 0, n)
                if (out.size() > UNPACKED_MAX) throw CallError("the link’s values unpack to more than $UNPACKED_MAX bytes")
            }
            if (inflater.remaining != 0) throw CallError("the link’s compressed values have bytes after their end")
            return out.toByteArray()
        } finally {
            inflater.end()
        }
    }
}

// ---- Templates -------------------------------------------------------------------

/** What a field holds. */
sealed class SlotType {
    /** An unsigned integer of `size` bytes, little-endian: u8 to u128. */
    class Integer(val size: Int) : SlotType()

    /** A coin's ContractId and an amount of it in its smallest units. */
    data object Amount : SlotType()
    data object CoinId : SlotType()
    data object Account : SlotType()
    data object Bytes32 : SlotType()

    /** Unix seconds. */
    data object Time : SlotType()

    /** Seconds. */
    data object Duration : SlotType()
    data object Text : SlotType()
    data object Bytes : SlotType()

    /** A u8 index into `labels`, shown by its label. */
    class Enum(val labels: List<String>) : SlotType()

    /** `u64 count ‖ count × fields`. */
    class ListOf(val fields: List<Slot>) : SlotType()

    /** The fewest bytes a value of this type takes, so a list's count can be checked against what is left. */
    val leastBytes: Int get() = when (this) {
        is Integer -> size
        Amount -> 48
        CoinId, Account, Bytes32 -> 32
        Time, Duration -> 8
        Text, Bytes -> 4
        is Enum -> 1
        is ListOf -> 8
    }
}

/** A declared field of a template, or a field of a list. */
class Slot(val name: String, val type: SlotType)

/**
 * An operation of a program (TCR-1.md: templates): its name and fields, in
 * the order their bytes come, and the sentence that shows them.
 */
class Template private constructor(
    val text: String,
    val operation: String,
    /** The text up to and including `"): "`: templates of the same operation share it. */
    val declaration: String,
    val fields: List<Slot>,
    val parts: List<Part>,
) {
    sealed class Part {
        class Words(val text: String) : Part()

        /** A field of [fields], by its index there. */
        class Field(val index: Int) : Part()

        /** `{payment}`: the call's payment, as the wallet checked it. */
        data object Payment : Part()
    }

    /** Whether the sentence shows `{payment}`, which takes a payment from the acting account. */
    val showsPayment: Boolean get() = parts.any { it is Part.Payment }

    val hash: ByteArray get() = Tcr.templateHash(text)

    companion object {
        private val NAME = Regex("^[a-z][a-z0-9_]{0,31}$")
        private const val PAYMENT = "payment"

        /** The template `text`; throws [CallError] saying what is wrong with it. */
        fun parse(text: String): Template {
            try {
                Offchain.line(text, "the template")
            } catch (e: OffchainError) {
                throw CallError(e.message ?: "the template cannot be shown")
            }
            if (utf8(text).size > Tcr.TEMPLATE_MAX) throw CallError("the template is longer than ${Tcr.TEMPLATE_MAX} bytes")
            val open = text.indexOf('(')
            if (open < 0) throw CallError("the template declares no operation")
            val operation = text.substring(0, open)
            if (!NAME.matches(operation)) throw CallError("the template’s operation “$operation” is not a name")
            val close = closing(text, open) ?: throw CallError("the template’s declaration does not end")
            if (!text.startsWith("): ", close)) throw CallError("the template’s declaration is not followed by “: ”")
            val fields = split(text.substring(open + 1, close), ',').map { field(it, inList = false) }
            val names = fields.map { it.name }
            if (names.toSet().size != names.size) throw CallError("the template declares a field twice")
            if (PAYMENT in names) throw CallError("the template declares a field named payment, which names the payment")
            val declaration = text.substring(0, close + 3)
            val parts = sentence(text.substring(close + 3), names)
            for (n in names) {
                if (parts.none { it is Part.Field && names[it.index] == n }) throw CallError("the template’s sentence does not show its field $n")
            }
            return Template(text, operation, declaration, fields, parts)
        }

        /** The index of the `)` that closes the `(` at `open`; labels hold no parentheses, so every `(` is matched. */
        private fun closing(text: String, open: Int): Int? {
            var depth = 0
            for (i in open until text.length) {
                when (text[i]) {
                    '(' -> depth++
                    ')' -> if (--depth == 0) return i
                    '{', '}' -> return null
                }
            }
            return null
        }

        /** `text` split at `separator` outside parentheses; empty text is no part. */
        private fun split(text: String, separator: Char): List<String> {
            if (text.isEmpty()) return emptyList()
            val out = ArrayList<String>()
            var depth = 0
            var start = 0
            for ((i, c) in text.withIndex()) {
                when {
                    c == '(' -> depth++
                    c == ')' -> depth--
                    c == separator && depth == 0 -> {
                        out.add(text.substring(start, i))
                        start = i + 1
                    }
                }
            }
            out.add(text.substring(start))
            return out
        }

        private fun field(text: String, inList: Boolean): Slot {
            val colon = text.indexOf(':')
            if (colon < 0) throw CallError("the template declares a field without a type: $text")
            val name = text.substring(0, colon)
            if (!NAME.matches(name)) throw CallError("the template declares a field named “$name”, which is not a name")
            return Slot(name, type(text.substring(colon + 1), inList))
        }

        private fun type(text: String, inList: Boolean): SlotType = when (text) {
            "u8" -> SlotType.Integer(1)
            "u16" -> SlotType.Integer(2)
            "u32" -> SlotType.Integer(4)
            "u64" -> SlotType.Integer(8)
            "u128" -> SlotType.Integer(16)
            "amount" -> SlotType.Amount
            "coin" -> SlotType.CoinId
            "account" -> SlotType.Account
            "bytes32" -> SlotType.Bytes32
            "time" -> SlotType.Time
            "duration" -> SlotType.Duration
            "text" -> SlotType.Text
            "bytes" -> SlotType.Bytes
            else -> when {
                text.startsWith("enum(") && text.endsWith(")") -> {
                    val labels = text.substring(5, text.length - 1).split('|')
                    for (l in labels) {
                        if (l.isEmpty() || trimWhiteSpace(l) != l || l.any { it in "(){}:,|" }) throw CallError("the template has an enum label “$l”, which is not a label")
                    }
                    if (labels.toSet().size != labels.size) throw CallError("the template has an enum with a label twice")
                    if (labels.size > 256) throw CallError("the template has an enum of more than 256 labels")
                    SlotType.Enum(labels)
                }
                !inList && text.startsWith("list(") && text.endsWith(")") -> {
                    val fields = split(text.substring(5, text.length - 1), ',').map { field(it, inList = true) }
                    if (fields.isEmpty()) throw CallError("the template has a list without fields")
                    if (fields.map { it.name }.toSet().size != fields.size) throw CallError("the template has a list that names a field twice")
                    SlotType.ListOf(fields)
                }
                else -> throw CallError("the template has a field of type “$text”, which Ting ID does not know")
            }
        }

        /** The sentence's words and slots; a slot names a declared field or the payment. */
        private fun sentence(text: String, names: List<String>): List<Part> {
            if (text.isEmpty()) throw CallError("the template has no sentence")
            val parts = ArrayList<Part>()
            var at = 0
            while (at < text.length) {
                val open = text.indexOf('{', at)
                val close = text.indexOf('}', at)
                if (close >= 0 && (open < 0 || close < open)) throw CallError("the template’s sentence has a } outside a slot")
                if (open < 0) {
                    parts.add(Part.Words(text.substring(at)))
                    break
                }
                if (open > at) parts.add(Part.Words(text.substring(at, open)))
                if (close < 0) throw CallError("the template’s sentence has a slot without its }")
                val name = text.substring(open + 1, close)
                parts.add(
                    when {
                        name == PAYMENT -> Part.Payment
                        name in names -> Part.Field(names.indexOf(name))
                        else -> throw CallError("the template’s sentence shows {$name}, which it does not declare")
                    },
                )
                at = close + 1
            }
            return parts
        }
    }
}

// ---- Values ----------------------------------------------------------------------

/** A field's value, decoded. */
sealed class SlotValue {
    class Integer(val value: BigInteger) : SlotValue()
    class Amount(val coin: ByteArray, val value: BigInteger) : SlotValue()
    class CoinId(val id: ByteArray) : SlotValue()
    class Account(val account: ByteArray) : SlotValue()
    class Bytes32(val bytes: ByteArray) : SlotValue()
    class Time(val at: BigInteger) : SlotValue()
    class Duration(val seconds: BigInteger) : SlotValue()
    class Text(val text: String) : SlotValue()
    class Bytes(val bytes: ByteArray) : SlotValue()
    class Enum(val index: Int, val label: String) : SlotValue()

    /** Each item's fields, in the list's order. */
    class ListOf(val fields: List<Slot>, val items: List<List<SlotValue>>) : SlotValue()
}

/** The values of `template` decoded exactly from `bytes`, one per declared field; throws [CallError] on anything else. */
fun decodeValues(template: Template, bytes: ByteArray): List<SlotValue> {
    val r = Reader(bytes)
    return try {
        val out = template.fields.map { readValue(r, it) }
        r.end()
        out
    } catch (e: DecodeError) {
        throw CallError(if (e.message == "trailing bytes") "the values have bytes after the last field" else "the values are shorter than the template calls for")
    }
}

private fun readValue(r: Reader, slot: Slot): SlotValue = when (val type = slot.type) {
    is SlotType.Integer -> SlotValue.Integer(BigInteger(1, r.take(type.size).reversedArray()))
    SlotType.Amount -> SlotValue.Amount(r.array32(), r.u128())
    SlotType.CoinId -> SlotValue.CoinId(r.array32())
    SlotType.Account -> SlotValue.Account(r.array32())
    SlotType.Bytes32 -> SlotValue.Bytes32(r.array32())
    SlotType.Time -> SlotValue.Time(BigInteger(1, r.take(8).reversedArray()))
    SlotType.Duration -> SlotValue.Duration(BigInteger(1, r.take(8).reversedArray()))
    SlotType.Text -> {
        val text = try {
            Offchain.textOf(r.bytes(), "the text ${slot.name}")
        } catch (e: OffchainError) {
            throw CallError(e.message ?: "the text ${slot.name} cannot be shown")
        }
        if (trimWhiteSpace(text) != text) throw CallError("the text ${slot.name} starts or ends with spaces")
        SlotValue.Text(text)
    }
    SlotType.Bytes -> SlotValue.Bytes(r.bytes())
    is SlotType.Enum -> {
        val index = r.u8()
        if (index >= type.labels.size) throw CallError("the value of ${slot.name} is past its last label")
        SlotValue.Enum(index, type.labels[index])
    }
    is SlotType.ListOf -> {
        val count = r.u64()
        if (count > r.remaining / type.fields.sumOf { it.type.leastBytes }) throw DecodeError("truncated")
        SlotValue.ListOf(type.fields, (0 until count).map { type.fields.map { f -> readValue(r, f) } })
    }
}

/** `bytes` as UTF-8 text when they are valid and in their shortest form; null otherwise. */
private fun strictUtf8(bytes: ByteArray): String? {
    val text = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        return null
    }
    return if (utf8(text).contentEquals(bytes)) text else null
}
