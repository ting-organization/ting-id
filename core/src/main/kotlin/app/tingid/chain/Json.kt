// Reading JSON from nodes and sites, which are not trusted: every field is
// read with its type checked, and a missing or ill-typed one throws, rather
// than turning into a zero or an empty value the app would act on.
package app.tingid.chain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/** A JSON answer that is not what it should be. */
class BadJson(message: String) : Exception(message)

fun parseJson(text: String): JsonElement? = try {
    if (text.isBlank()) null else Json.parseToJsonElement(text)
} catch (e: Exception) {
    throw BadJson("not JSON")
}

val JsonElement?.obj: JsonObject get() = this as? JsonObject ?: throw BadJson("expected an object")
val JsonElement?.objOrNull: JsonObject? get() = this as? JsonObject

private fun JsonObject.prim(name: String): JsonPrimitive? = when (val v = this[name]) {
    null, JsonNull -> null
    is JsonPrimitive -> v
    else -> throw BadJson("$name is not a value")
}

fun JsonObject.strOrNull(name: String): String? = prim(name)?.let { if (it.isString) it.content else throw BadJson("$name is not text") }
fun JsonObject.str(name: String): String = strOrNull(name) ?: throw BadJson("$name is missing")

fun JsonObject.longOrNull(name: String): Long? = prim(name)?.let { if (it.isString) throw BadJson("$name is not a number") else it.longOrNull ?: throw BadJson("$name is not a whole number") }
fun JsonObject.long(name: String): Long = longOrNull(name) ?: throw BadJson("$name is missing")
fun JsonObject.int(name: String): Int = long(name).let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else throw BadJson("$name is out of range") }

fun JsonObject.boolOrNull(name: String): Boolean? = prim(name)?.let { if (it.isString) throw BadJson("$name is not true or false") else it.booleanOrNull ?: throw BadJson("$name is not true or false") }

fun JsonObject.arrOrNull(name: String): JsonArray? = when (val v = this[name]) {
    null, JsonNull -> null
    is JsonArray -> v
    else -> throw BadJson("$name is not a list")
}

fun JsonObject.arr(name: String): JsonArray = arrOrNull(name) ?: throw BadJson("$name is missing")

fun JsonObject.objOrNull(name: String): JsonObject? = when (val v = this[name]) {
    null, JsonNull -> null
    is JsonObject -> v
    else -> throw BadJson("$name is not an object")
}

fun JsonObject.hexOrNull(name: String): ByteArray? = strOrNull(name)?.let { if (isHex(it)) unhex(it) else throw BadJson("$name is not hex") }
fun JsonObject.hex(name: String): ByteArray = hexOrNull(name) ?: throw BadJson("$name is missing")

/** A list of whole numbers. */
fun JsonObject.longs(name: String): List<Long> = (arrOrNull(name) ?: JsonArray(emptyList())).map {
    (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.longOrNull ?: throw BadJson("$name holds something other than whole numbers")
}

fun JsonArray.objects(): List<JsonObject> = map { it as? JsonObject ?: throw BadJson("expected a list of objects") }
