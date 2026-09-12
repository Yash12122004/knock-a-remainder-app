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
    val isDark: Boolean
)

val DarkKnock = KnockColors(
    bg = Color(0xFF0B0C14),
    card = Color(0x0BFFFFFF),
    border = Color(0x12FFFFFF),
    text = Color(0xFFEEF0FF),
    secondary = Color(0xFF9A9FBD),
    accent = Color(0xFF8B95FF),
    accentOn = Color(0xFF0B0C14),
    overdue = Color(0xFFFF7A90),
    done = Color(0xFF5FE0B0),
    warn = Color(0xFFFFC24D),
    isDark = true
)

val LightKnock = KnockColors(
    bg = Color(0xFFF4F5F9),
    card = Color(0xFFFFFFFF),
    border = Color(0x14000000),
    text = Color(0xFF12142A),
    secondary = Color(0xFF5E6380),
    accent = Color(0xFF2F5BFF),
    accentOn = Color(0xFFFFFFFF),
    overdue = Color(0xFFD9364F),
    done = Color(0xFF15966A),
    warn = Color(0xFFB77900),
    isDark = false
)

val LocalKnock = staticCompositionLocalOf { DarkKnock }

val Mono = FontFamily.Monospace

@Composable
fun KnockTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    val c = if (dark) DarkKnock else LightKnock
    val scheme = if (dark) darkColorScheme(
        primary = c.accent, onPrimary = c.accentOn, background = c.bg, onBackground = c.text,
        surface = c.bg, onSurface = c.text, surfaceVariant = Color(0xFF181A2B), onSurfaceVariant = c.secondary,
        error = c.overdue, outline = c.border, secondaryContainer = Color(0xFF232647), onSecondaryContainer = c.text,
        surfaceContainer = Color(0xFF14162A), surfaceContainerHigh = Color(0xFF1B1E36), surfaceContainerHighest = Color(0xFF232647),
        surfaceContainerLow = Color(0xFF10121F), surfaceContainerLowest = c.bg
    ) else lightColorScheme(
        primary = c.accent, onPrimary = c.accentOn, background = c.bg, onBackground = c.text,
        surface = c.bg, onSurface = c.text, surfaceVariant = Color(0xFFE6E8F2), onSurfaceVariant = c.secondary,
        error = c.overdue, outline = c.border, secondaryContainer = Color(0xFFDDE3FF), onSecondaryContainer = c.text,
        surfaceContainer = Color(0xFFECEEF6), surfaceContainerHigh = Color(0xFFE4E7F1), surfaceContainerHighest = Color(0xFFDDE0EC),
        surfaceContainerLow = Color(0xFFF7F8FC), surfaceContainerLowest = Color(0xFFFFFFFF)
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
