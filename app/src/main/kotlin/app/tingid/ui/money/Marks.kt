// The marks and tones the texts give lines (text/Describe), as the app draws
// them, and the small pieces the money widgets share.
package app.tingid.ui.money

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Hardware
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tingid.text.Icon
import app.tingid.ui.Tone
import app.tingid.ui.Type
import app.tingid.ui.t
import app.tingid.text.Tone as TextTone

/** A line's mark (text.Icon, named after the first app's Ionicons) as a Material icon, the closest there is. */
fun iconOf(icon: Icon): ImageVector = when (icon) {
    Icon.ArrowDown -> Icons.Outlined.ArrowDownward
    Icon.ArrowUp -> Icons.Outlined.ArrowUpward
    Icon.Flame -> Icons.Outlined.LocalFireDepartment
    Icon.Hammer -> Icons.Outlined.Hardware
    Icon.Swap -> Icons.Outlined.SwapHoriz
    Icon.CheckmarkCircle -> Icons.Outlined.CheckCircle
    Icon.CloseCircle -> Icons.Outlined.Cancel
    Icon.Time -> Icons.Outlined.Schedule
    Icon.Play -> Icons.Outlined.PlayArrow
    Icon.Key -> Icons.Outlined.Key
    Icon.Fingerprint -> Icons.Outlined.Fingerprint
    Icon.Hourglass -> Icons.Outlined.HourglassEmpty
    Icon.ShieldCheckmark -> Icons.Outlined.VerifiedUser
    Icon.Shield -> Icons.Outlined.Shield
    Icon.RefreshCircle -> Icons.Outlined.Autorenew
    Icon.LogIn -> Icons.AutoMirrored.Outlined.Login
    Icon.Document -> Icons.AutoMirrored.Outlined.Article
    Icon.Checkmark -> Icons.Outlined.Check
    Icon.Refresh -> Icons.Outlined.Refresh
    Icon.AlertCircle -> Icons.Outlined.ErrorOutline
    Icon.Program -> Icons.Outlined.Extension
}

/** A line's tone as the texts give it (text.Tone), as the components take it. */
fun toneOf(tone: TextTone): Tone = when (tone) {
    TextTone.Neutral -> Tone.Neutral
    TextTone.Primary -> Tone.Primary
    TextTone.Accent -> Tone.Accent
    TextTone.Warning -> Tone.Warning
    TextTone.Danger -> Tone.Danger
    TextTone.Success -> Tone.Success
}

/** The hairline between the rows of a card. */
@Composable
internal fun Hairline(color: Color = t.border) = Spacer(Modifier.fillMaxWidth().height(0.5.dp).background(color))

/** A caption in a color of its own, such as a warning under a fee. */
@Composable
internal fun ToneCaption(text: String, color: Color, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) =
    Text(text, modifier, style = Type.caption, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis)

/**
 * A row that opens something: tinted while pressed, and in the selection's
 * color when `selected`, as the first app's pressable rows are.
 */
@Composable
internal fun PressRow(
    onClick: () -> Unit,
    padding: PaddingValues,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    gap: Dp = 12.dp,
    content: @Composable RowScope.() -> Unit,
) {
    val p = t
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier.fillMaxWidth().background(if (selected) p.primarySoft else if (pressed) p.cardAlt else Color.Transparent)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick).padding(padding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(gap),
        content = content,
    )
}
