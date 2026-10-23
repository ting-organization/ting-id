// Marks of the listed coins (state/KnownCoins), drawn rather than bundled as
// images so they stay sharp at every size.
package app.tingid.ui.money

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import app.tingid.state.KNOWN_COINS
import app.tingid.ui.t
import app.tingid.state.CoinIcon as CoinIconName

private val ICONS: Map<String, CoinIconName> = KNOWN_COINS.values.flatten().mapNotNull { k -> k.icon?.let { k.contract to it } }.toMap()

/** The mark of a listed coin, by its contract (hex). */
fun coinIconOf(contract: String): CoinIconName? = ICONS[contract]

@Composable
fun CoinIcon(name: CoinIconName, size: Dp) {
    when (name) {
        CoinIconName.Daler -> Daler(size)
    }
}

private val GOLD = Color(0xFFE3B341)

/** The D of Daler, in the mark's 64-unit square. */
private val DALER_D = Path().apply {
    moveTo(24f, 19f)
    lineTo(30f, 19f)
    cubicTo(38f, 19f, 43.5f, 24.6f, 43.5f, 32f)
    // The SVG's smooth curve: its first control point mirrors the last one about (43.5, 32).
    cubicTo(43.5f, 39.4f, 38f, 45f, 30f, 45f)
    lineTo(24f, 45f)
    close()
}

/** Daler: a gold D with a bar through its stem, in a gold ring on ink. */
@Composable
private fun Daler(size: Dp) {
    val dark = t.dark
    Canvas(Modifier.size(size).semantics { contentDescription = "Daler" }) {
        scale(this.size.width / 64f, pivot = Offset.Zero) {
            val center = Offset(32f, 32f)
            drawCircle(Color(0xFF0B1220), radius = if (dark) 31.5f else 32f, center = center)
            // On a dark page a hairline keeps the coin's edge in sight.
            if (dark) drawCircle(Color(0xFF334155), radius = 31.5f, center = center, style = Stroke(width = 1f))
            drawCircle(GOLD, radius = 26.5f, center = center, style = Stroke(width = 2.5f))
            drawPath(DALER_D, GOLD, style = Stroke(width = 5f, join = StrokeJoin.Round))
            drawLine(GOLD, Offset(18.5f, 32f), Offset(30f, 32f), strokeWidth = 4f, cap = StrokeCap.Round)
        }
    }
}
