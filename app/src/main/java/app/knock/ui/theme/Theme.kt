package app.knock.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class KnockColors(
    val bg: Color,
    val card: Color,
    val border: Color,
    val text: Color,
    val secondary: Color,
    val accent: Color,
    val accentOn: Color,
    val overdue: Color,
    val done: Color,
    val warn: Color,
    /** Bottom-sheet surface: lifted off the background in dark, plain card in light. */
    val sheet: Color,
    val isDark: Boolean
)

// Black and white for everything structural; colour only where it carries meaning:
// red = overdue / destructive, green = done / OK, amber = caution. Nothing else is coloured.
//
// Tuned for comfortable long reading. Dark mode avoids pure white on pure black (above ~15:1,
// light text blooms and smears, worst with astigmatism) and uses softened status colours,
// since saturated hues vibrate on black. Every text/background pair used is at least 4.5:1.

val DarkKnock = KnockColors(
    bg = Color(0xFF121212),
    card = Color(0x0DFFFFFF),
    border = Color(0x1AFFFFFF),
    text = Color(0xFFE3E3E3),
    secondary = Color(0xFFA8A8A8),
    accent = Color(0xFFE3E3E3),
    accentOn = Color(0xFF121212),
    overdue = Color(0xFFF28B82),
    done = Color(0xFF81C995),
    warn = Color(0xFFFDD663),
    sheet = Color(0xFF1E1E1E),
    isDark = true
)

val LightKnock = KnockColors(
    bg = Color(0xFFF5F5F5),
    card = Color(0xFFFFFFFF),
    border = Color(0x1A000000),
    text = Color(0xFF212121),
    secondary = Color(0xFF525252),
    accent = Color(0xFF212121),
    accentOn = Color(0xFFFFFFFF),
    overdue = Color(0xFFB3261E),
    done = Color(0xFF137333),
    warn = Color(0xFF8F5300),
    sheet = Color(0xFFFFFFFF),
    isDark = false
)

val LocalKnock = staticCompositionLocalOf { DarkKnock }

val Mono = FontFamily.Monospace

@Composable
fun KnockTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    val c = if (dark) DarkKnock else LightKnock
    val scheme = if (dark) darkColorScheme(
        primary = c.accent, onPrimary = c.accentOn, background = c.bg, onBackground = c.text,
        surface = c.bg, onSurface = c.text, surfaceVariant = Color(0xFF242424), onSurfaceVariant = c.secondary,
        error = c.overdue, outline = c.border, secondaryContainer = Color(0xFF2A2A2A), onSecondaryContainer = c.text,
        surfaceContainer = c.sheet, surfaceContainerHigh = Color(0xFF242424), surfaceContainerHighest = Color(0xFF2A2A2A),
        surfaceContainerLow = Color(0xFF181818), surfaceContainerLowest = c.bg
    ) else lightColorScheme(
        primary = c.accent, onPrimary = c.accentOn, background = c.bg, onBackground = c.text,
        surface = c.bg, onSurface = c.text, surfaceVariant = Color(0xFFEBEBEB), onSurfaceVariant = c.secondary,
        error = c.overdue, outline = c.border, secondaryContainer = Color(0xFFE5E5E5), onSecondaryContainer = c.text,
        surfaceContainer = Color(0xFFF0F0F0), surfaceContainerHigh = Color(0xFFEBEBEB), surfaceContainerHighest = Color(0xFFE5E5E5),
        surfaceContainerLow = Color(0xFFFAFAFA), surfaceContainerLowest = Color(0xFFFFFFFF)
    )
    val typography = Typography(
        displayLarge = TextStyle(fontSize = 38.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp, lineHeight = 42.sp),
        headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
        titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = TextStyle(fontSize = 16.sp),
        bodyMedium = TextStyle(fontSize = 14.sp),
        bodySmall = TextStyle(fontSize = 12.sp),
        labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
        labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
        labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)
    )
    CompositionLocalProvider(LocalKnock provides c) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}

/** The spec's card: translucent fill, 1 dp hairline border, 16 dp radius. */
@Composable
fun Modifier.knockCard(radius: Int = 16): Modifier {
    val c = LocalKnock.current
    val shape = RoundedCornerShape(radius.dp)
    return this.background(c.card, shape).border(1.dp, c.border, shape)
}
