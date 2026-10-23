// Building blocks shared by every screen.
package app.tingid.ui

import android.content.ClipData
import android.content.ClipDescription
import android.os.PersistableBundle
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tingid.chain.shortId
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val MAX_WIDTH = 680.dp

/**
 * A screen: an optional dark `header` band across the top, then the content.
 * `title` puts a large title above the content instead. Every screen shows
 * the app's notices first (no node answers, a change could not be saved).
 */
@Composable
fun Screen(
    title: String? = null,
    right: @Composable (() -> Unit)? = null,
    header: @Composable (() -> Unit)? = null,
    scroll: Boolean = true,
    padded: Boolean = true,
    /** Under a top bar, which keeps clear of the status bar itself. */
    belowHeader: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = t
    val top = if (header != null || belowHeader) Modifier else Modifier.windowInsetsPadding(WindowInsets.statusBars)
    Column(
        Modifier.fillMaxSize().background(p.background).then(top).then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier).imePadding(),
    ) {
        header?.invoke()
        Column(
            Modifier.fillMaxWidth().widthIn(max = MAX_WIDTH).align(Alignment.CenterHorizontally)
                .padding(if (padded) PaddingValues(start = Spacing.lg, end = Spacing.lg, top = if (header != null) Spacing.lg + 2.dp else Spacing.lg, bottom = Spacing.xxl) else PaddingValues(bottom = Spacing.xxl)),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            LocalNotices.current()
            if (title != null) {
                Row(Modifier.padding(horizontal = 4.dp).padding(bottom = Spacing.xs), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    Text(title, style = Type.title, color = p.text, modifier = Modifier.weight(1f))
                    right?.invoke()
                }
            }
            content()
        }
    }
}

/** The notices every screen shows above its content; the app's shell provides them. */
val LocalNotices = androidx.compose.runtime.staticCompositionLocalOf<@Composable () -> Unit> { {} }

/** The dark band at the top of a screen: rounded at the bottom, under the status bar. */
@Composable
fun InkHeader(content: @Composable ColumnScope.() -> Unit) {
    val p = t
    // The status bar's icons turn light over the dark band while it shows.
    DisposableEffect(Unit) {
        InkHeaders.count++
        onDispose { InkHeaders.count-- }
    }
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(bottomStart = Radius.xl, bottomEnd = Radius.xl)).background(p.header)
            .windowInsetsPadding(WindowInsets.statusBars).padding(top = 16.dp, bottom = 22.dp, start = 20.dp, end = 20.dp),
    ) {
        Column(Modifier.fillMaxWidth().widthIn(max = MAX_WIDTH - 8.dp).align(Alignment.TopCenter), verticalArrangement = Arrangement.spacedBy(18.dp), content = content)
    }
}

/** How many dark header bands show: while any does, the status bar's icons are light (AppShell). */
object InkHeaders {
    var count by androidx.compose.runtime.mutableIntStateOf(0)
}

/** The Ting ID mark: a T in a ring on indigo. */
@Composable
fun Logo(size: Dp = 24.dp) {
    Canvas(Modifier.size(size).semantics { contentDescription = "Ting ID" }) {
        val u = this.size.width / 1024f
        drawRoundRect(Color(0xFF4F46E5), cornerRadius = CornerRadius(224 * u))
        drawCircle(Color.White, radius = 300 * u, center = Offset(512 * u, 512 * u), style = Stroke(width = 76 * u))
        drawRoundRect(Color.White, topLeft = Offset(382 * u, 368 * u), size = Size(260 * u, 84 * u), cornerRadius = CornerRadius(42 * u))
        drawRoundRect(Color.White, topLeft = Offset(470 * u, 476 * u), size = Size(84 * u, 196 * u), cornerRadius = CornerRadius(42 * u))
    }
}

enum class CardTone { Plain, Alt, Warning }

@Composable
fun Card(modifier: Modifier = Modifier, tone: CardTone = CardTone.Plain, padded: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val p = t
    val (bg, line) = when (tone) {
        CardTone.Warning -> p.warningSoft to p.warningLine
        CardTone.Alt -> p.cardAlt to p.border
        CardTone.Plain -> p.card to p.border
    }
    val shape = RoundedCornerShape(Radius.lg)
    Column(
        modifier.fillMaxWidth().then(if (tone == CardTone.Plain && !p.dark) Modifier.shadow(1.dp, shape, ambientColor = Color(0x0A0F172A), spotColor = Color(0x0A0F172A)) else Modifier)
            .clip(shape).background(bg).border(1.dp, line, shape).padding(if (padded) Spacing.lg else 0.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        content = content,
    )
}

@Composable
fun H(text: String, modifier: Modifier = Modifier) = Text(text, modifier, style = Type.heading, color = t.text)

@Composable
fun Sub(text: String, modifier: Modifier = Modifier) = Text(text, modifier, style = Type.subheading, color = t.text)

@Composable
fun Body(text: String, modifier: Modifier = Modifier, muted: Boolean = false) = Text(text, modifier, style = Type.body, color = if (muted) t.muted else t.text)

@Composable
fun Caption(text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE, textAlign: TextAlign? = null) =
    Text(text, modifier, style = Type.caption, color = t.muted, maxLines = maxLines, overflow = TextOverflow.Ellipsis, textAlign = textAlign)

@Composable
fun Mono(text: String, modifier: Modifier = Modifier) = Text(text, modifier, style = Type.mono, color = t.text)

/** An amount: figures in columns. */
@Composable
fun Amount(text: String, modifier: Modifier = Modifier, style: TextStyle = manropeStyle(15f, FontWeight.SemiBold, tabular = true)) = Text(text, modifier, style = style, color = t.text)

/** An (i) that explains on tap, in a sheet. `light`: on the dark header. */
@Composable
fun Info(title: String, text: String, light: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    Icon(
        Icons.Outlined.Info,
        contentDescription = "About $title",
        tint = if (light) t.onHeaderMuted else t.faint,
        modifier = Modifier.size(18.dp).clickable(role = Role.Button) { open = true },
    )
    if (open) Sheet(onClose = { open = false }, title = title) { Body(text, muted = true) }
}

/** Section title with an optional action on the right, like "Manage". */
@Composable
fun SectionHeader(title: String, action: String? = null, onAction: (() -> Unit)? = null, icon: ImageVector? = null, info: String? = null) {
    val p = t
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp).padding(top = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (icon != null) Icon(icon, null, tint = p.muted, modifier = Modifier.size(16.dp))
            Text(title, style = manropeStyle(16f, FontWeight.ExtraBold), color = p.text)
            if (info != null) Info(title, info)
        }
        if (action != null && onAction != null) {
            Text(action, style = manropeStyle(13.5f, FontWeight.Bold), color = p.primary, modifier = Modifier.clickable(role = Role.Button, onClick = onAction).padding(4.dp))
        }
    }
}

/**
 * `Primary` ink, the main action; `Secondary` white with a hairline; `Ghost` a
 * link; `Danger` red on white; `Destructive` solid red, for what cannot be
 * undone; `Light` and `Raised` sit on the dark header.
 */
enum class ButtonKind { Primary, Secondary, Ghost, Danger, Destructive, Light, Raised }

private class ButtonColors(val bg: Color, val fg: Color, val border: Color?)

@Composable
private fun colorsOf(kind: ButtonKind): ButtonColors {
    val p = t
    return when (kind) {
        ButtonKind.Primary -> ButtonColors(p.ink, p.onInk, null)
        ButtonKind.Secondary -> ButtonColors(p.card, p.text, p.border)
        ButtonKind.Ghost -> ButtonColors(Color.Transparent, p.primary, null)
        ButtonKind.Danger -> ButtonColors(p.card, p.danger, p.dangerLine)
        ButtonKind.Destructive -> ButtonColors(p.danger, Color.White, null)
        ButtonKind.Light -> ButtonColors(Color.White, Color(0xFF0B1220), null)
        ButtonKind.Raised -> ButtonColors(p.headerRaised, p.onHeader, null)
    }
}

@Composable
fun Button(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Primary,
    enabled: Boolean = true,
    loading: Boolean = false,
    small: Boolean = false,
    /** The one big button at the end of a form. */
    large: Boolean = false,
    icon: ImageVector? = null,
) {
    val c = colorsOf(kind)
    val link = kind == ButtonKind.Ghost
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(if (large) Radius.lg else Radius.md)
    Row(
        modifier
            .then(if (link) Modifier else Modifier.fillMaxWidthIf(!small))
            .heightIn(min = if (link) 0.dp else if (large) 56.dp else if (small) 34.dp else 46.dp)
            .clip(shape)
            .background(c.bg)
            .then(if (c.border != null) Modifier.border(BorderStroke(1.dp, c.border), shape) else Modifier)
            // A layer that stays: Modifier.alpha drops its layer at 1, and the change of chain left an
            // enabled button without its background.
            .graphicsLayer { alpha = if (!enabled) 0.4f else if (pressed) 0.8f else 1f }
            .clickable(interaction, indication = null, enabled = enabled && !loading, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (link) 4.dp else if (small) 14.dp else 18.dp, vertical = if (link) (if (small) 4.dp else 8.dp) else 0.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) CircularProgressIndicator(color = c.fg, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        else if (icon != null) Icon(icon, null, tint = c.fg, modifier = Modifier.size(if (small) 15.dp else 17.dp))
        Text(title, style = manropeStyle(if (large) 16f else if (small) 13.5f else 15f, if (large) FontWeight.ExtraBold else FontWeight.Bold), color = c.fg)
    }
}

private fun Modifier.fillMaxWidthIf(fill: Boolean) = if (fill) fillMaxWidth() else this

/** A square button with only an icon. */
@Composable
fun IconButton(icon: ImageVector, label: String, onClick: () -> Unit, kind: ButtonKind = ButtonKind.Secondary, size: Dp = 44.dp, enabled: Boolean = true) {
    val c = colorsOf(kind)
    val shape = RoundedCornerShape(Radius.md)
    Box(
        Modifier.size(size).clip(shape).background(c.bg).then(if (c.border != null) Modifier.border(1.dp, c.border, shape) else Modifier)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }.clickable(enabled = enabled, role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = c.fg, modifier = Modifier.size(size * 0.42f)) }
}

@Composable
fun IconCircle(icon: ImageVector, color: Color, bg: Color, size: Dp = 40.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = color, modifier = Modifier.size(size * 0.48f))
    }
}

/** A count with its label and a short line under it. `onHeader`: in the dark header. */
@Composable
fun RowScope.StatTile(label: String, value: String, icon: ImageVector, hint: String? = null, onHeader: Boolean = false) {
    val p = t
    val fg = if (onHeader) p.onHeader else p.text
    val muted = if (onHeader) p.onHeaderMuted else p.muted
    Column(Modifier.weight(1f).clip(RoundedCornerShape(Radius.md)).background(if (onHeader) p.headerRaised else p.cardAlt).padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, null, tint = muted, modifier = Modifier.size(15.dp))
            Text(label, style = manropeStyle(12.5f, FontWeight.SemiBold), color = muted)
        }
        Text(value, style = manropeStyle(22f, FontWeight.ExtraBold, tabular = true), color = fg)
        if (hint != null) Text(hint, style = manropeStyle(12f, FontWeight.Medium), color = muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * A text field. `secret`: the keyboard learns nothing from it and offers no
 * suggestions, for recovery words.
 */
@Composable
fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    hint: String? = null,
    error: String? = null,
    placeholder: String? = null,
    multiline: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
    enabled: Boolean = true,
    onDone: (() -> Unit)? = null,
) {
    val p = t
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (label != null) Text(label, style = manropeStyle(12.5f, FontWeight.SemiBold), color = p.muted)
        val shape = RoundedCornerShape(Radius.md)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = !multiline,
            textStyle = manropeStyle(16f, FontWeight.Medium).copy(color = p.text),
            cursorBrush = SolidColor(p.primary),
            keyboardOptions = KeyboardOptions(
                keyboardType = if (secret) KeyboardType.Password else keyboardType,
                autoCorrectEnabled = !secret && keyboardType == KeyboardType.Text,
                imeAction = if (onDone != null) androidx.compose.ui.text.input.ImeAction.Done else androidx.compose.ui.text.input.ImeAction.Default,
            ),
            keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
            visualTransformation = VisualTransformation.None,
            modifier = Modifier.fillMaxWidth().clip(shape).background(p.cardAlt).border(1.dp, if (error != null) p.danger else p.border, shape)
                .heightIn(min = if (multiline) 80.dp else 0.dp).padding(horizontal = 14.dp, vertical = 12.dp),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty() && placeholder != null) Text(placeholder, style = manropeStyle(16f, FontWeight.Medium), color = p.faint)
                    inner()
                }
            },
        )
        if (error != null) Text(sentence(error), style = Type.caption, color = p.danger) else if (hint != null) Caption(hint)
    }
}

/** A message as a sentence: a capital first letter and a full stop. */
fun sentence(text: String): String {
    val s = text.trim()
    if (s.isEmpty()) return s
    val first = s.replaceFirstChar { it.uppercaseChar() }
    return if (first.last() in ".!?…”\"") first else "$first."
}

enum class Tone { Neutral, Primary, Accent, Warning, Danger, Success }

@Composable
fun toneColors(tone: Tone): Pair<Color, Color> {
    val p = t
    return when (tone) {
        Tone.Neutral -> p.cardAlt to p.muted
        Tone.Primary -> p.primarySoft to p.primary
        Tone.Accent -> p.accentSoft to p.accent
        Tone.Warning -> p.warningSoft to p.warning
        Tone.Danger -> p.dangerSoft to p.danger
        Tone.Success -> p.successSoft to p.success
    }
}

/** A small label. `light`: on the dark header. */
@Composable
fun Pill(label: String, tone: Tone = Tone.Neutral, small: Boolean = false, light: Boolean = false) {
    val p = t
    val (bg, fg) = if (light) (if (tone == Tone.Warning) Color(0x24FBBF24) to Color(0xFFFCD34D) else p.headerRaised to Color(0xFFCBD5E1)) else toneColors(tone)
    Text(
        label,
        style = manropeStyle(if (small) 11f else 12f, FontWeight.Bold),
        color = fg,
        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(bg).padding(horizontal = if (small) 8.dp else 10.dp, vertical = if (small) 2.dp else 3.dp),
    )
}

fun initialsOf(name: String): String =
    name.split(Regex("[$WS·]+")).mapNotNull { part -> part.firstOrNull { it.isLetterOrDigit() } }.take(2).joinToString("").uppercase().ifEmpty { "?" }

private const val WS = app.tingid.chain.WHITE_SPACE

fun parseColor(hex: String): Color = try {
    Color(android.graphics.Color.parseColor(hex))
} catch (e: IllegalArgumentException) {
    Color(0xFF4F46E5)
}

/** A person or user: initials on their colour, or on a soft tint when `soft`. */
@Composable
fun Avatar(name: String, color: String, size: Dp = 40.dp, soft: Boolean = false) {
    val p = t
    Box(Modifier.size(size).clip(CircleShape).background(if (soft) p.primarySoft else parseColor(color)), contentAlignment = Alignment.Center) {
        Text(initialsOf(name), style = manropeStyle(size.value * 0.38f, FontWeight.ExtraBold), color = if (soft) p.primary else Color.White)
    }
}

/**
 * Copies `text` to the clipboard. `sensitive`: the phone shows no preview of
 * it and other apps' clipboard history is told to leave it out.
 */
suspend fun copyText(clipboard: androidx.compose.ui.platform.Clipboard, text: String, sensitive: Boolean = false) {
    val clip = ClipData.newPlainText("Ting ID", text)
    if (sensitive) clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    clipboard.setClipEntry(ClipEntry(clip))
}

/** A 32-byte identifier, shortened, copied on tap. `light`: on the dark header. */
@Composable
fun IdChip(value: String, label: String? = null, full: Boolean = false, light: Boolean = false) {
    val p = t
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    val fg = if (light) p.onHeader else p.text
    val muted = if (light) p.onHeaderMuted else p.muted
    Row(
        Modifier.clip(RoundedCornerShape(999.dp)).background(if (light) p.headerRaised else p.cardAlt)
            .clickable(role = Role.Button) {
                scope.launch {
                    copyText(clipboard, value)
                    copied = true
                }
            }.semantics { contentDescription = "Copy ${label ?: "id"}" }
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (label != null) Text(label, style = Type.caption, color = muted)
        Text(if (full) value else shortId(value, 8, 6), style = Type.mono, color = fg, maxLines = if (full) 3 else 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Icon(if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy, null, tint = if (copied) (if (light) p.onHeader else p.success) else muted, modifier = Modifier.size(13.dp))
    }
}

@Composable
fun ListItem(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    left: @Composable (() -> Unit)? = null,
    right: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    selected: Boolean = false,
    chevron: Boolean = false,
    titleContent: @Composable (() -> Unit)? = null,
    subtitleContent: @Composable (() -> Unit)? = null,
) {
    val p = t
    Row(
        modifier.fillMaxWidth().background(if (selected) p.primarySoft else Color.Transparent)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(vertical = 12.dp, horizontal = Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        left?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (titleContent != null) titleContent() else Text(title, style = manropeStyle(14.5f, FontWeight.Bold), color = p.text)
            if (subtitleContent != null) subtitleContent() else if (subtitle != null) Caption(subtitle)
        }
        right?.invoke()
        if (chevron) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = p.faint, modifier = Modifier.size(16.dp))
    }
}

/** A choice of a few: chips side by side, the chosen one in ink. */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, value: T, onChange: (T) -> Unit) {
    val p = t
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        for ((v, label) in options) {
            val active = v == value
            val shape = RoundedCornerShape(Radius.md)
            Box(
                Modifier.weight(1f).heightIn(min = 38.dp).clip(shape).background(if (active) p.ink else p.card)
                    .then(if (active) Modifier else Modifier.border(1.dp, p.border, shape))
                    .clickable(role = Role.RadioButton) { onChange(v) }.padding(vertical = 6.dp, horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = manropeStyle(13.5f, FontWeight.Bold), color = if (active) p.onInk else p.text, textAlign = TextAlign.Center, maxLines = 2)
            }
        }
    }
}

enum class BannerTone { Info, Warning, Danger, Success }

@Composable
fun Banner(text: String, tone: BannerTone = BannerTone.Info, icon: ImageVector? = null) {
    val p = t
    val (bg, fg, line, default) = when (tone) {
        BannerTone.Info -> Quad(p.primarySoft, p.primary, p.primarySoft, Icons.Outlined.Info)
        BannerTone.Warning -> Quad(p.warningSoft, p.warning, p.warningLine, Icons.Outlined.ErrorOutline)
        BannerTone.Danger -> Quad(p.dangerSoft, p.danger, p.dangerLine, Icons.Outlined.Cancel)
        BannerTone.Success -> Quad(p.successSoft, p.success, p.successSoft, Icons.Outlined.CheckCircle)
    }
    val shape = RoundedCornerShape(Radius.md)
    Row(Modifier.fillMaxWidth().clip(shape).background(bg).border(1.dp, line, shape).padding(Spacing.md), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon ?: default, null, tint = fg, modifier = Modifier.size(18.dp).padding(top = 1.dp))
        Text(text, style = manropeStyle(14f, FontWeight.Medium).copy(lineHeight = 20.sp), color = fg, modifier = Modifier.weight(1f))
    }
}

private data class Quad(val a: Color, val b: Color, val c: Color, val d: ImageVector)

@Composable
fun Empty(title: String, body: String? = null, icon: ImageVector = Icons.Outlined.Spa) {
    val p = t
    Column(Modifier.fillMaxWidth().padding(Spacing.xl), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        IconCircle(icon, p.muted, p.cardAlt, 48.dp)
        Text(title, style = Type.subheading, color = p.text)
        if (body != null) Caption(body, textAlign = TextAlign.Center)
    }
}

@Composable
fun Loading(label: String? = null) {
    Column(Modifier.fillMaxWidth().padding(Spacing.xl), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        CircularProgressIndicator(color = t.muted, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
        if (label != null) Caption(label)
    }
}

/** A bottom sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Sheet(onClose: () -> Unit, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val p = t
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = p.card,
        shape = RoundedCornerShape(topStart = Radius.xl, topEnd = Radius.xl),
    ) {
        Column(
            Modifier.fillMaxWidth().widthIn(max = MAX_WIDTH).padding(horizontal = Spacing.lg).padding(bottom = Spacing.lg).navigationBarsPadding().imePadding()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            if (title != null) H(title)
            content()
        }
    }
}

/** Rows of labels and values in a bordered box, such as a coin's details. */
@Composable
fun DetailCard(content: @Composable ColumnScope.() -> Unit) {
    val p = t
    val shape = RoundedCornerShape(16.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(p.card).border(1.dp, p.border, shape), content = content)
}

/** A label and its value, one row of a DetailCard; the first has no line above it. */
@Composable
fun Detail(label: String, first: Boolean = false, value: @Composable () -> Unit) {
    val p = t
    Column {
        if (!first) Spacer(Modifier.fillMaxWidth().height(1.dp).background(p.border))
        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Text(label, style = manropeStyle(13.5f, FontWeight.SemiBold), color = p.muted)
            Spacer(Modifier.weight(1f))
            value()
        }
    }
}

/** A value in a Detail row, its figures lined up. */
@Composable
fun DetailValue(text: String) = Text(text, style = manropeStyle(13.5f, FontWeight.Bold, tabular = true), color = t.text, textAlign = TextAlign.End)

@Composable
fun Divider() = Spacer(Modifier.fillMaxWidth().padding(vertical = Spacing.xs).height(1.dp).background(t.border))
