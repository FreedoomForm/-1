package com.example.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/* ============================================================================
   DRIFTWOOD TYPOGRAPHY
   ----------------------------------------------------------------------------
   Headings = Serif (system serif → falls back to Noto Serif on Android)
   Body / UI = Sans (system sans → Roboto / Inter)
   Letter-spacing is tightened on display sizes for editorial feel.
   ============================================================================ */

val DriftwoodSerif: FontFamily = FontFamily.Serif
val DriftwoodSans: FontFamily = FontFamily.SansSerif

val Typography =
  Typography(
    // Display — large hero numbers / prices
    displayLarge = TextStyle(
      fontFamily = DriftwoodSerif,
      fontWeight = FontWeight.SemiBold,
      fontSize = 48.sp,
      lineHeight = 54.sp,
      letterSpacing = (-0.5).sp
    ),
    displayMedium = TextStyle(
      fontFamily = DriftwoodSerif,
      fontWeight = FontWeight.SemiBold,
      fontSize = 36.sp,
      lineHeight = 42.sp,
      letterSpacing = (-0.4).sp
    ),
    displaySmall = TextStyle(
      fontFamily = DriftwoodSerif,
      fontWeight = FontWeight.SemiBold,
      fontSize = 28.sp,
      lineHeight = 34.sp,
      letterSpacing = (-0.3).sp
    ),

    // Headlines — section titles like "Kontraktlar", "Tranzaksiyalar"
    headlineLarge = TextStyle(
      fontFamily = DriftwoodSerif,
      fontWeight = FontWeight.SemiBold,
      fontSize = 28.sp,
      lineHeight = 34.sp,
      letterSpacing = (-0.3).sp
    ),
    headlineMedium = TextStyle(
      fontFamily = DriftwoodSerif,
      fontWeight = FontWeight.SemiBold,
      fontSize = 24.sp,
      lineHeight = 30.sp,
      letterSpacing = (-0.2).sp
    ),

    // Titles — dialog titles, card headers
    titleLarge = TextStyle(
      fontFamily = DriftwoodSerif,
      fontWeight = FontWeight.SemiBold,
      fontSize = 22.sp,
      lineHeight = 28.sp,
      letterSpacing = (-0.2).sp
    ),
    titleMedium = TextStyle(
      fontFamily = DriftwoodSerif,
      fontWeight = FontWeight.SemiBold,
      fontSize = 18.sp,
      lineHeight = 24.sp,
      letterSpacing = (-0.1).sp
    ),
    titleSmall = TextStyle(
      fontFamily = DriftwoodSans,
      fontWeight = FontWeight.SemiBold,
      fontSize = 14.sp,
      lineHeight = 20.sp,
      letterSpacing = 0.1.sp
    ),

    // Body — long-form text, dialog content, descriptions
    bodyLarge = TextStyle(
      fontFamily = DriftwoodSans,
      fontWeight = FontWeight.Normal,
      fontSize = 16.sp,
      lineHeight = 24.sp,
      letterSpacing = 0.15.sp
    ),
    bodyMedium = TextStyle(
      fontFamily = DriftwoodSans,
      fontWeight = FontWeight.Normal,
      fontSize = 14.sp,
      lineHeight = 20.sp,
      letterSpacing = 0.2.sp
    ),
    bodySmall = TextStyle(
      fontFamily = DriftwoodSans,
      fontWeight = FontWeight.Normal,
      fontSize = 12.sp,
      lineHeight = 16.sp,
      letterSpacing = 0.25.sp
    ),

    // Labels — buttons, table headers, eyebrows
    labelLarge = TextStyle(
      fontFamily = DriftwoodSans,
      fontWeight = FontWeight.SemiBold,
      fontSize = 14.sp,
      lineHeight = 20.sp,
      letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
      fontFamily = DriftwoodSans,
      fontWeight = FontWeight.SemiBold,
      fontSize = 12.sp,
      lineHeight = 16.sp,
      letterSpacing = 0.3.sp
    ),
    labelSmall = TextStyle(
      fontFamily = DriftwoodSans,
      fontWeight = FontWeight.SemiBold,
      fontSize = 11.sp,
      lineHeight = 16.sp,
      letterSpacing = 0.5.sp
    )
  )
