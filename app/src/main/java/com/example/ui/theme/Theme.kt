package com.example.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/* ============================================================================
   DRIFTWOOD THEME — light-only, warm editorial
   ============================================================================ */

private val LightColorScheme =
  lightColorScheme(
    primary = ClaudeAccent,            // terracotta
    onPrimary = ClaudeCard,            // near-white on terracotta
    primaryContainer = ClaudeAccentBg, // soft gold tint
    onPrimaryContainer = ClaudeAccentDark,
    secondary = ClaudeGold,
    onSecondary = ClaudeCard,
    tertiary = ClaudeTeal,
    onTertiary = ClaudeCard,
    background = ClaudeBackground,     // cream paper
    onBackground = ClaudeText,         // dark brown ink
    surface = ClaudeCard,              // near-white card
    onSurface = ClaudeText,
    surfaceVariant = ClaudeBackground2,
    onSurfaceVariant = ClaudeTextSecondary,
    surfaceTint = ClaudeAccent,
    outline = ClaudeDivider,
    outlineVariant = ClaudeDivider,
    error = ClaudeAccentDark,
    onError = ClaudeCard
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = false, // Force light — Driftwood is a light, warm theme
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme = LightColorScheme

  val view = LocalView.current
  if (!view.isInEditMode) {
    SideEffect {
      val window = (view.context as Activity).window
      // Cream paper status bar with dark ink icons
      window.statusBarColor = ClaudeBackground.toArgb()
      WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = true
    }
  }

  MaterialTheme(
    colorScheme = colorScheme,
    typography = Typography,
    content = content
  )
}
