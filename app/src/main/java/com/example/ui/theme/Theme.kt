package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AuraDarkColorScheme = darkColorScheme(
    primary = CyberEmerald,
    onPrimary = Color(0xFF002116),
    primaryContainer = CyberEmeraldContainer,
    onPrimaryContainer = CyberEmerald,
    secondary = HolographicSapphire,
    onSecondary = Color(0xFF001E2F),
    secondaryContainer = SapphireContainer,
    onSecondaryContainer = HolographicSapphire,
    tertiary = AmberPulse,
    onTertiary = Color(0xFF281800),
    tertiaryContainer = AmberContainer,
    onTertiaryContainer = AmberPulse,
    error = CrimsonAlert,
    onError = Color(0xFF33000A),
    errorContainer = CrimsonContainer,
    onErrorContainer = CrimsonAlert,
    background = ObsidianBackground,
    onBackground = TextPrimary,
    surface = GraphiteSurface,
    onSurface = TextPrimary,
    surfaceVariant = TacticalSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    outline = SubtleBorder
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = AuraDarkColorScheme,
        typography = Typography,
        content = content
    )
}
