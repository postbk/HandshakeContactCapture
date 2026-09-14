package com.example.handshakecontactcapture.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Color(0xFF175C50), onPrimary = Color.White,
    primaryContainer = Color(0xFFD9ECE0), onPrimaryContainer = Color(0xFF123E32),
    secondary = Color(0xFF52695F), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE4ECE3), onSecondaryContainer = Color(0xFF20332E),
    tertiary = Color(0xFF815E28), tertiaryContainer = Color(0xFFF4E7C9), onTertiaryContainer = Color(0xFF4C3510),
    background = Color(0xFFF7F8F3), onBackground = Color(0xFF20332E), surface = Color(0xFFFCFDF9), onSurface = Color(0xFF20332E),
    surfaceVariant = Color(0xFFE9EEE6), onSurfaceVariant = Color(0xFF53635A),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF0F3ED),
    surfaceContainer = Color(0xFFEBEFE7), surfaceContainerHigh = Color(0xFFE5EBE2),
    surfaceContainerHighest = Color(0xFFDEE5DB), outline = Color(0xFF75847B), outlineVariant = Color(0xFFD4DDD2)
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFA4D8BD), onPrimary = Color(0xFF06382C),
    primaryContainer = Color(0xFF234D3F), onPrimaryContainer = Color(0xFFD5EFDD),
    secondary = Color(0xFFB8CDC0), secondaryContainer = Color(0xFF334B3E), onSecondaryContainer = Color(0xFFDCEBDE),
    tertiary = Color(0xFFE4C38B), tertiaryContainer = Color(0xFF564224), onTertiaryContainer = Color(0xFFFFE5B8),
    background = Color(0xFF121B17), onBackground = Color(0xFFE0E9E0),
    surface = Color(0xFF17201B), onSurface = Color(0xFFE0E9E0),
    surfaceVariant = Color(0xFF35453B), onSurfaceVariant = Color(0xFFB7C6BA),
    surfaceContainerLowest = Color(0xFF0E1611), surfaceContainerLow = Color(0xFF1B2620),
    surfaceContainer = Color(0xFF202C25), surfaceContainerHigh = Color(0xFF29352D),
    surfaceContainerHighest = Color(0xFF344037), outline = Color(0xFF89998D), outlineVariant = Color(0xFF3F5044)
)

@Composable
fun HandshakeContactCaptureTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography,
        shapes = Shapes(extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp)),
        content = content)
}