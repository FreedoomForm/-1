package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// ── Основная палитра (чёрно-белая — grayscale) ──────────────────────────────
val ClaudeBackground = Color(0xFFFAFAFA)        // светлый off-white
val ClaudeCard = Color(0xFFFFFFFF)
val ClaudeText = Color(0xFF000000)              // чёрный
val ClaudeTextSecondary = Color(0xFF6B6B6B)     // средне-серый
val ClaudeAccent = Color(0xFF000000)            // чёрный акцент (заменил янтарный)
val ClaudeAccentMuted = Color(0xFFD4D4D4)       // светло-серый (заменил amber-200)
val ClaudeAccentBg = Color(0xFFF0F0F0)          // очень светло-серый (заменил amber-50)
val ClaudeDivider = Color(0xFFE5E5E5)

// ── Семантические цвета (ТОЛЬКО для статуса в таблице арендаторов/скутеров) ─
// По требованию пользователя: красный/зелёный для линий статуса сохраняются,
// всё остальное приложение — чёрно-белое.
val StatusOk = Color(0xFF16A34A)        // green-600 — активный/оплачено
val StatusOkBg = Color(0xFFDCFCE7)
val StatusOverdue = Color(0xFFDC2626)   // red-600 — долг/просрочка
val StatusOverdueBg = Color(0xFFFEE2E2)
val StatusReturned = Color(0xFF6B6B6B)  // серый — скутер вернули
val StatusReturnedBg = Color(0xFFF4F4F5)
val StatusInfo = Color(0xFF000000)      // чёрный (заменил blue-600)
val StatusInfoBg = Color(0xFFF0F0F0)
