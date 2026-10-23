// What the coin browser says about a coin: how it is issued, who mints it,
// and whether it copies the name of a coin listed for the chain.
package app.tingid.text

import app.tingid.chain.Coin
import app.tingid.chain.equal
import app.tingid.chain.formatAmount
import app.tingid.chain.hex
import app.tingid.chain.shortId
import app.tingid.state.CoinInfo
import app.tingid.state.UserAccount
import java.math.BigInteger
import java.text.Normalizer

fun hasMinter(c: CoinInfo): Boolean = !equal(c.state.minter, Coin.ZERO_ACCOUNT)

/** How new coins come about: mining rewards, a minter, both, or neither. */
fun supplyModel(c: CoinInfo): String {
    val mined = c.config.initialReward.signum() > 0 || c.config.tailReward.signum() > 0
    return when {
        mined && hasMinter(c) -> "mined and minted"
        mined -> "mined"
        hasMinter(c) -> "minted"
        else -> "fixed supply"
    }
}

/** The user's account that may mint the coin, if any. */
fun minterAccount(c: CoinInfo, accounts: List<UserAccount>): UserAccount? = accounts.firstOrNull { equal(it.account, c.state.minter) }

/** Who mints, in words. */
fun minterName(c: CoinInfo, accounts: List<UserAccount>, names: Names): String {
    if (!hasMinter(c)) return "no one"
    val mine = minterAccount(c, accounts)
    if (mine != null) return "you · ${mine.label}"
    val h = c.state.minter.hex()
    return names[h] ?: shortId(h)
}

fun amountOf(c: CoinInfo, v: BigInteger): String = "${formatAmount(v, c.config.decimals)} ${c.config.symbol}"

fun circulating(c: CoinInfo): BigInteger = c.state.issued - c.state.burned

fun capped(c: CoinInfo): Boolean = c.config.maxSupply != Coin.UNCAPPED

/** Characters that pass for others at a glance, folded to one. */
private val LOOKALIKE = mapOf(
    '0'.code to 'o', '1'.code to 'l', 'i'.code to 'l', '|'.code to 'l', '5'.code to 's', '$'.code to 's',
    '3'.code to 'e', '4'.code to 'a', '@'.code to 'a', '7'.code to 't', '8'.code to 'b',
)

/** Whether a character is a letter or a number (Unicode's L and N), spelled out rather than left to a regex engine. */
private fun letterOrNumber(cp: Int): Boolean = Character.isLetter(cp) || when (Character.getType(cp)) {
    Character.DECIMAL_DIGIT_NUMBER.toInt(), Character.LETTER_NUMBER.toInt(), Character.OTHER_NUMBER.toInt() -> true
    else -> false
}

/** A name or ticker as it reads: lower case, accents and spacing dropped, look-alikes folded. */
fun reading(text: String): String {
    val folded = Normalizer.normalize(text, Normalizer.Form.NFKD).lowercase()
    val out = StringBuilder()
    var i = 0
    while (i < folded.length) {
        val cp = folded.codePointAt(i)
        i += Character.charCount(cp)
        if (!letterOrNumber(cp) && cp != '$'.code && cp != '@'.code && cp != '|'.code) continue
        val same = LOOKALIKE[cp]
        if (same != null) out.append(same) else out.appendCodePoint(cp)
    }
    return out.toString()
}

/** Edits (insert, delete, replace) between two strings. */
private fun distance(a: String, b: String): Int {
    var row = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        val next = IntArray(b.length + 1)
        next[0] = i
        for (j in 1..b.length) next[j] = minOf(row[j] + 1, next[j - 1] + 1, row[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        row = next
    }
    return row[b.length]
}

/** Names that read the same or nearly: equal, one inside the other, or a typo or two apart. */
fun similarNames(a: String, b: String): Boolean {
    val x = reading(a)
    val y = reading(b)
    if (x.isEmpty() || y.isEmpty()) return x == y
    if (x == y) return true
    // One name inside the other counts once the shorter is a word, not a letter or two.
    if (minOf(x.length, y.length) >= 3 && (x.contains(y) || y.contains(x))) return true
    return distance(x, y) <= maxOf(1, minOf(x.length, y.length) / 4)
}

/**
 * The coin `c` copies, if any: another coin with the same ticker and the same
 * or a very similar name that came first. The original is the listed coin when
 * one of them is listed, else the one deployed earliest (`all` in deployment
 * order breaks ties within a block). Anyone can deploy a coin with any name,
 * so a copy is what an impostor looks like.
 */
fun originalOf(c: CoinInfo, all: List<CoinInfo>, listedIds: List<String>): CoinInfo? {
    val ticker = reading(c.config.symbol)
    val family = all.filter { reading(it.config.symbol) == ticker && similarNames(it.config.name, c.config.name) }
    if (family.size < 2) return null
    fun place(o: CoinInfo) = all.indexOfFirst { it === o }
    val first = family.sortedWith(compareBy<CoinInfo>({ if (it.hex in listedIds) 0 else 1 }, { it.config.deployHeight }, { place(it) })).first()
    return if (first.hex == c.hex) null else first
}
