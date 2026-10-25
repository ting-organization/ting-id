// What a program's call says (TCR-1.md): its sentence with each value shown
// as its type is shown, so the program's own words and the app's reading of
// the values stay apart from what the app says itself.
package app.tingid.text

import app.tingid.chain.Slot
import app.tingid.chain.SlotValue
import app.tingid.chain.Template
import app.tingid.chain.formatAmount
import app.tingid.chain.hex
import app.tingid.chain.shortId
import app.tingid.state.CoinInfo
import java.math.BigInteger

/** A piece of a call's sentence: the program's words, or a value as the app reads it. */
sealed class CallPart {
    class Words(val text: String) : CallPart()

    /** `strange`: an account that is neither the user's nor a contact, or a coin this app cannot read. */
    class Value(val text: String, val strange: Boolean = false) : CallPart()
}

/**
 * The sentence with its values in, as the parts a screen styles. `payment`:
 * the call's payment as the app shows it, for `{payment}`. `time` formats Unix
 * seconds.
 */
fun callSentence(template: Template, values: List<SlotValue>, payment: String?, names: Names, coins: Map<String, CoinInfo?>, time: (Long) -> String): List<CallPart> =
    template.parts.map { part ->
        when (part) {
            is Template.Part.Words -> CallPart.Words(part.text)
            is Template.Part.Field -> values[part.index].let { CallPart.Value(showValue(it, names, coins, time), strange(it, names, coins)) }
            Template.Part.Payment -> CallPart.Value(payment ?: "no payment", strange = payment == null)
        }
    }

/** The sentence as one line of text, for a summary. */
fun callLine(parts: List<CallPart>): String = parts.joinToString("") {
    when (it) {
        is CallPart.Words -> it.text
        is CallPart.Value -> it.text
    }
}

/** One value as the app shows it. */
fun showValue(v: SlotValue, names: Names, coins: Map<String, CoinInfo?>, time: (Long) -> String): String = when (v) {
    is SlotValue.Integer -> v.value.toString()
    is SlotValue.Amount -> coins[v.coin.hex()]?.let { "${formatAmount(v.value, it.config.decimals)} ${it.config.symbol}" }
        ?: "${v.value} units of coin ${shortId(v.coin)}"
    is SlotValue.CoinId -> coins[v.id.hex()]?.config?.let { "${it.name} (${it.symbol})" } ?: "coin ${shortId(v.id)}"
    is SlotValue.Account -> names[v.account.hex()] ?: shortId(v.account)
    is SlotValue.Bytes32 -> shortId(v.bytes)
    is SlotValue.Time -> v.at.toLongOrNull()?.let(time) ?: v.at.toString()
    is SlotValue.Duration -> v.seconds.toLongOrNull()?.let(::relativeDuration) ?: "${v.seconds} seconds"
    is SlotValue.Text -> "“${v.text}”"
    is SlotValue.Bytes -> if (v.bytes.size == 1) "1 byte" else "${v.bytes.size} bytes"
    is SlotValue.Enum -> v.label
    is SlotValue.ListOf -> v.items.size.toString()
}

/** A value the person should look at twice: an account they do not know, or a coin the app cannot read. */
fun strange(v: SlotValue, names: Names, coins: Map<String, CoinInfo?>): Boolean = when (v) {
    is SlotValue.Account -> !names.containsKey(v.account.hex())
    is SlotValue.Amount -> coins[v.coin.hex()] == null
    is SlotValue.CoinId -> coins[v.id.hex()] == null
    else -> false
}

/** A list's item as one line: each field by its name, `pixel 1000 · color 3`. */
fun listItemLine(fields: List<Slot>, item: List<SlotValue>, names: Names, coins: Map<String, CoinInfo?>, time: (Long) -> String): String =
    fields.zip(item).joinToString(" · ") { (f, v) -> "${f.name} ${showValue(v, names, coins, time)}" }

/** Accounts the values name that are neither the user's nor a contact's, in full, in the order they appear. */
fun strangeAccounts(values: List<SlotValue>, names: Names): List<String> = values.flatMap { v ->
    when (v) {
        is SlotValue.Account -> if (names.containsKey(v.account.hex())) emptyList() else listOf(v.account.hex())
        is SlotValue.ListOf -> v.items.flatMap { strangeAccounts(it, names) }
        else -> emptyList()
    }
}.distinct()

private fun BigInteger.toLongOrNull(): Long? = if (bitLength() < 63) toLong() else null
