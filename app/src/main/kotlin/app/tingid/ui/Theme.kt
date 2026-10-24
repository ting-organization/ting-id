// The app's look: a dark ink header over a light page, white cards with a
// hairline and no shadow, ink buttons, and indigo for links and highlights.
// Manrope, one variable font, kept to light weights: semibold at most.
package app.tingid.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.tingid.R
import app.tingid.state.Theme

@Immutable
data class Palette(
    val dark: Boolean,
    val background: Color,
    val card: Color,
    val cardAlt: Color,
    val text: Color,
    val muted: Color,
    val faint: Color,
    val border: Color,
    /** Links, selection and highlights. */
    val primary: Color,
    val onPrimary: Color,
    val primarySoft: Color,
    /** The main buttons. */
    val ink: Color,
    val onInk: Color,
    /** The dark band at the top of the wallet, an account and the identity. */
    val header: Color,
    val headerRaised: Color,
    val onHeader: Color,
    val onHeaderMuted: Color,
    val accent: Color,
    val accentSoft: Color,
    val danger: Color,
    val dangerSoft: Color,
    val dangerLine: Color,
    val warning: Color,
    val warningSoft: Color,
    val warningLine: Color,
    val success: Color,
    val successSoft: Color,
)

private fun c(hex: Long) = Color(0xFF000000 or hex)

val LightPalette = Palette(
    dark = false,
    background = c(0xF4F5F7), card = c(0xFFFFFF), cardAlt = c(0xF1F3F6), text = c(0x0B1220), muted = c(0x64748B), faint = c(0x94A3B8),
    border = c(0xE2E8F0), primary = c(0x4F46E5), onPrimary = c(0xFFFFFF), primarySoft = c(0xEEF2FF), ink = c(0x0B1220), onInk = c(0xFFFFFF),
    header = c(0x0B1220), headerRaised = c(0x1B2540), onHeader = c(0xF8FAFC), onHeaderMuted = c(0x94A3B8), accent = c(0x0F766E),
    accentSoft = c(0xE6F6F4), danger = c(0xB91C1C), dangerSoft = c(0xFEF2F2), dangerLine = c(0xFCA5A5), warning = c(0x92400E),
    warningSoft = c(0xFFFBEB), warningLine = c(0xFDE68A), success = c(0x047857), successSoft = c(0xECFDF5),
)

val DarkPalette = Palette(
    dark = true,
    background = c(0x0A0E16), card = c(0x121826), cardAlt = c(0x182033), text = c(0xF1F5F9), muted = c(0x94A3B8), faint = c(0x64748B),
    border = c(0x232C3F), primary = c(0xA5B4FC), onPrimary = c(0x0B1220), primarySoft = c(0x1E2547), ink = c(0xF8FAFC), onInk = c(0x0B1220),
    header = c(0x141C2E), headerRaised = c(0x212B44), onHeader = c(0xF8FAFC), onHeaderMuted = c(0x94A3B8), accent = c(0x2DD4BF),
    accentSoft = c(0x10302D), danger = c(0xF87171), dangerSoft = c(0x2A1518), dangerLine = c(0x7F2A2E), warning = c(0xFBBF24),
    warningSoft = c(0x221B0C), warningLine = c(0x5C4615), success = c(0x34D399), successSoft = c(0x0F2E25),
)

val LocalPalette = staticCompositionLocalOf { LightPalette }

/** The palette in use: the one chosen in Settings, else the system's. */
val t: Palette @Composable get() = LocalPalette.current

object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

object Radius {
    val sm = 10.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
}

private fun manrope(weight: Int) = Font(R.font.manrope, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

/** Manrope, every weight from the one variable font. */
val Manrope = FontFamily(manrope(400), manrope(500), manrope(600), manrope(700), manrope(800))

/** Figures that line up in columns. */
const val TABULAR = "tnum"

object Type {
    val title = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, letterSpacing = (-0.4 / 24).em)
    val heading = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, letterSpacing = (-0.2 / 18).em)
    val subheading = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    val body = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp)
    val caption = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Medium, fontSize = 12.5.sp, lineHeight = 18.sp)
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    val amount = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 38.sp, letterSpacing = (-0.8 / 38).em, fontFeatureSettings = TABULAR)
}

/** A weight of the app's font at a size. */
fun manropeStyle(size: Float, weight: FontWeight = FontWeight.Normal, tabular: Boolean = false) =
    TextStyle(fontFamily = Manrope, fontWeight = weight, fontSize = size.sp, fontFeatureSettings = if (tabular) TABULAR else null)

@Composable
fun TingTheme(choice: Theme, content: @Composable () -> Unit) {
    val dark = choice == Theme.Dark || (choice != Theme.Light && isSystemInDarkTheme())
    val p = if (dark) DarkPalette else LightPalette
    // Material components (dialogs, text selection, the date picker) follow the same colors.
    val scheme = if (dark) {
        darkColorScheme(primary = p.primary, onPrimary = p.onPrimary, background = p.background, surface = p.card, onSurface = p.text, error = p.danger, outline = p.border)
    } else {
        lightColorScheme(primary = p.primary, onPrimary = p.onPrimary, background = p.background, surface = p.card, onSurface = p.text, error = p.danger, outline = p.border)
    }
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, content = content)
    }
}
