package com.example.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme =
  lightColorScheme(
    primary = ClaudeAccent,
    secondary = ClaudeAccentMuted,
    tertiary = ClaudeTextSecondary,
    background = ClaudeBackground,
    surface = ClaudeCard,
    onPrimary = ClaudeCard,
    onSecondary = ClaudeText,
    onTertiary = ClaudeCard,
    onBackground = ClaudeText,
    onSurface = ClaudeText,
    surfaceVariant = ClaudeBackground,
    onSurfaceVariant = ClaudeTextSecondary,
    outline = ClaudeDivider
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  // Dynamic color is available on Android 12+
  dynamicColor: Boolean = false, // Disable dynamic colors to enforce Claude theme
  content: @Composable () -> Unit,
) {
  val colorScheme = LightColorScheme

  val view = LocalView.current
  if (!view.isInEditMode) {
    SideEffect {
      val window = (view.context as Activity).window
      window.statusBarColor = colorScheme.background.toArgb()
      WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = true
    }
  }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
