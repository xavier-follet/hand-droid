package com.follet.jotter

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// WCAG 2.2 AA: text/icons >= 4.5:1 on their background, UI boundaries and state indicators >= 3:1.
// Light: white toolbox (surface) with near-black icons (onSurface 18:1). Dark: near-black toolbox with white icons.
private val Light = lightColorScheme(
    primary = Color(0xFF1B4FD8), onPrimary = Color.White, // 7:1 on white
    primaryContainer = Color(0xFFDCE6FF), onPrimaryContainer = Color(0xFF0A1B5C),
    secondaryContainer = Color(0xFFDCE6FF), onSecondaryContainer = Color(0xFF0A1B5C),
    background = Color(0xFFF6F6F9), onBackground = Color(0xFF111111),
    surface = Color.White, onSurface = Color(0xFF111111),
    surfaceVariant = Color(0xFFECEDF2), onSurfaceVariant = Color(0xFF3F424C),
    outline = Color(0xFF6B6F7A), outlineVariant = Color(0xFFC4C7D0), // outline 5:1 on white
    inverseSurface = Color(0xFF2B2D33), inverseOnSurface = Color(0xFFF1F2F6), inversePrimary = Color(0xFF9DBBFF), // snackbar
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF1F2F6), surfaceContainer = Color(0xFFECEDF2),
    surfaceContainerHigh = Color(0xFFE6E8EF), surfaceContainerHighest = Color(0xFFE0E2EA),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9DBBFF), onPrimary = Color(0xFF0A1B5C), // 9:1 on the toolbox
    primaryContainer = Color(0xFF1F3470), onPrimaryContainer = Color(0xFFDCE6FF),
    secondaryContainer = Color(0xFF1F3470), onSecondaryContainer = Color(0xFFDCE6FF),
    background = Color(0xFF0A0A0C), onBackground = Color.White,
    surface = Color(0xFF111111), onSurface = Color.White,
    surfaceVariant = Color(0xFF26272C), onSurfaceVariant = Color(0xFFD0D3DB),
    outline = Color(0xFF9A9EA8), outlineVariant = Color(0xFF44464E),
    inverseSurface = Color(0xFFE6E8EF), inverseOnSurface = Color(0xFF111111), inversePrimary = Color(0xFF1B4FD8), // snackbar
    surfaceContainerLowest = Color(0xFF0A0A0C), surfaceContainerLow = Color(0xFF141416), surfaceContainer = Color(0xFF17181B),
    surfaceContainerHigh = Color(0xFF1D1E22), surfaceContainerHighest = Color(0xFF26272C),
)

@Composable
fun JotterTheme(dark: Boolean, content: @Composable () -> Unit) =
    MaterialTheme(if (dark) Dark else Light, content = content)
