package dev.stan.liftlog.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Dark, high-contrast and large-typed: this gets read at arm's length under gym lighting.
private val scheme = darkColorScheme(
    primary = Color(0xFFF2A33C),
    onPrimary = Color(0xFF241705),
    primaryContainer = Color(0xFF573B10),
    onPrimaryContainer = Color(0xFFFFDDAF),
    secondary = Color(0xFF6FD1B0),
    onSecondary = Color(0xFF06281E),
    background = Color(0xFF0F1115),
    onBackground = Color(0xFFE6E9EF),
    surface = Color(0xFF171B22),
    onSurface = Color(0xFFE6E9EF),
    surfaceVariant = Color(0xFF232935),
    onSurfaceVariant = Color(0xFFAFB7C4),
    outline = Color(0xFF3A4350),
    error = Color(0xFFE0685F),
    onError = Color(0xFF2A0A08)
)

val NumberStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Medium,
    fontSize = 15.sp
)

private val typography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 60.sp
    )
)

@Composable
fun LiftLogTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
