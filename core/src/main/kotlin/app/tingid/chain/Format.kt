// Display helpers: amounts in a coin's display units, short identifiers,
// durations. Dates and clock times are the app's, in the phone's locale.
package app.tingid.chain

import java.math.BigInteger
import kotlin.math.abs
import kotlin.math.roundToLong

fun formatAmount(amount: BigInteger, decimals: Int, maxFraction: Int = decimals): String {
    if (decimals == 0) return group(amount.toString())
    val scale = BigInteger.TEN.pow(decimals)
    val (whole, rest) = amount.divideAndRemainder(scale)
    val fraction = rest.toString().padStart(decimals, '0').take(maxFraction).trimEnd('0')
    return if (fraction.isNotEmpty()) "${group(whole.toString())}.$fraction" else group(whole.toString())
}

/** Digits in groups of three, a space between: 1 234 567. */
private fun group(digits: String): String {
    val sign = if (digits.startsWith('-')) "-" else ""
    val d = digits.removePrefix("-")
    val out = StringBuilder()
    for ((i, c) in d.withIndex()) {
        if (i > 0 && (d.length - i) % 3 == 0) out.append(' ')
        out.append(c)
    }
    return sign + out
}

class AmountError(message: String) : Exception(message)

/**
 * Parses `12.5` (or `12,5`) into base units; throws [AmountError] on bad input.
 * Groups may be spaced (`1 000`), but a comma before exactly three digits is
 * refused: `1,000` reads as one to some people and a thousand to others.
 */
fun parseAmount(text: String, decimals: Int): BigInteger {
    val spaced = trimWhiteSpace(text).replace(Regex("[$WHITE_SPACE_]"), "")
    if (Regex("^[1-9][0-9]*,[0-9]{3}$").matches(spaced)) throw AmountError("write a thousand as 1000 and decimals with a dot, like 1.5")
    val t = Regex("^([0-9]+),([0-9]*)$").replace(spaced, "$1.$2")
    val m = Regex("^([0-9]+)(?:\\.([0-9]*))?$").matchEntire(t) ?: throw AmountError("enter an amount like 12.5")
    val fraction = m.groupValues[2]
    if (fraction.length > decimals) throw AmountError("at most $decimals decimal places")
    val scale = BigInteger.TEN.pow(decimals)
    return BigInteger(m.groupValues[1]) * scale + (if (fraction.isNotEmpty()) BigInteger(fraction.padEnd(decimals, '0')) else BigInteger.ZERO)
}

private const val WHITE_SPACE_ = "${WHITE_SPACE}_"

fun shortId(bytes: ByteArray, head: Int = 6, tail: Int = 4): String = shortId(bytes.hex(), head, tail)

fun shortId(hex: String, head: Int = 6, tail: Int = 4): String =
    if (hex.length <= head + tail) hex else "${hex.take(head)}…${hex.takeLast(tail)}"

fun nowSeconds(): Long = System.currentTimeMillis() / 1000

/** `45 s`, `12 min`, `1.5 h`, `7 d`. */
fun formatDuration(seconds: Long): String = when {
    seconds < 60 -> "$seconds s"
    seconds < 3600 -> "${(seconds / 60.0).roundToLong()} min"
    seconds < 86_400 -> "${oneDecimal(seconds / 3600.0, seconds % 3600 != 0L)} h"
    else -> "${oneDecimal(seconds / 86_400.0, seconds % 86_400 != 0L)} d"
}

/** As JavaScript's `toFixed(1)` when `fraction`, else `toFixed(0)`. */
private fun oneDecimal(v: Double, fraction: Boolean): String =
    if (fraction) java.math.BigDecimal(v).setScale(1, java.math.RoundingMode.HALF_UP).toPlainString()
    else java.math.BigDecimal(v).setScale(0, java.math.RoundingMode.HALF_UP).toPlainString()

/** `in 2.5 d`, `3 min ago` or `now`. */
fun relativeTime(at: Long, now: Long = nowSeconds()): String {
    val delta = at - now
    if (abs(delta) < 5) return "now"
    return if (delta > 0) "in ${formatDuration(delta)}" else "${formatDuration(-delta)} ago"
}

class DelayPreset(val label: String, val seconds: Long)

/** Delay presets offered for slots and settings. */
val DELAY_PRESETS = listOf(
    DelayPreset("Instant", 0),
    DelayPreset("1 hour", 3600),
    DelayPreset("1 day", 86_400),
    DelayPreset("2 days", 172_800),
    DelayPreset("7 days", 604_800),
    DelayPreset("30 days", 2_592_000),
)
