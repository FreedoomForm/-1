package com.example.ui.theme

import androidx.compose.ui.graphics.Color

/* ============================================================================
   DRIFTWOOD THEME — warm editorial paper + terracotta accents
   ----------------------------------------------------------------------------
   Inspired by the Driftwood Press design language:
     • cream "paper" background (#F7F1E3) instead of cold off-white
     • dark brown ink text (#251E12) instead of pure black
     • terracotta accent (#C14E24) for primary actions
     • gold highlights (#B8862B / #E6C97F) for selected states & prices
     • dark warm brown (#241C10) for high-contrast sections (proof, footer)
   ============================================================================ */

// ── Paper (backgrounds) ─────────────────────────────────────────────────────
val ClaudeBackground = Color(0xFFF7F1E3)   // paper — warm cream
val ClaudeBackground2 = Color(0xFFEFE4CD)  // paper-2 — slightly darker cream
val ClaudeCard = Color(0xFFFFFCF3)         // card — near-white warm

// ── Ink (text) ──────────────────────────────────────────────────────────────
val ClaudeText = Color(0xFF251E12)         // ink — primary text, dark warm brown
val ClaudeTextSecondary = Color(0xFF71624B) // muted — secondary text
val ClaudeTextSoft = Color(0xFF463B27)     // ink-soft — body text on light

// ── Accent (terracotta + gold) ──────────────────────────────────────────────
val ClaudeAccent = Color(0xFFC14E24)       // acc — terracotta, primary CTA
val ClaudeAccentDark = Color(0xFFA03D1A)   // acc-2 — hover/pressed terracotta
val ClaudeAccentMuted = Color(0xFFE6C97F)  // gold-2 — light gold
val ClaudeGold = Color(0xFFB8862B)         // gold
val ClaudeTeal = Color(0xFF255E52)         // teal — alt accent (used rarely)
val ClaudeAccentBg = Color(0xFFF6EDD6)     // soft gold tint background

// ── Borders / dividers ──────────────────────────────────────────────────────
val ClaudeDivider = Color(0x24251E12)      // line: rgba(37,30,18,.14)

// ── Dark section (proof, footer, dark CTA) ──────────────────────────────────
val ClaudeDarkBg = Color(0xFF241C10)       // dk-bg
val ClaudeDarkText = Color(0xFFF4ECDB)     // dk-text
val ClaudeDarkMuted = Color(0xA3F4ECDB)    // dk-muted (rgba .64)

// ── Semantic status colors (for table rows: paid/unpaid) ───────────────────
// Kept saturated enough to read against cream paper.
val StatusOk = Color(0xFF255E52)           // teal = paid/active (matches palette)
val StatusOkBg = Color(0xFFD9E8E2)
val StatusOverdue = Color(0xFFC14E24)      // terracotta = overdue/unpaid (matches palette)
val StatusOverdueBg = Color(0xFFF6DCC9)
val StatusReturned = Color(0xFF71624B)     // muted brown = returned
val StatusReturnedBg = Color(0xFFEFE4CD)
val StatusInfo = Color(0xFFB8862B)         // gold = info
val StatusInfoBg = Color(0xFFF6EDD6)
