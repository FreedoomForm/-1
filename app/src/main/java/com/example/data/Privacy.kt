package com.example.data

/** Safe display helpers for tables, logs and external snapshots. */
fun maskIdentifier(value: String, visibleTail: Int = 4): String {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return "—"
    return if (trimmed.length <= visibleTail) "••••" else "••••${trimmed.takeLast(visibleTail)}"
}

fun maskAddress(value: String): String = if (value.isBlank()) "—" else "••••"

/**
 * Masks a phone number to show only the last 2 digits.
 * Per §10: 'mask the rest of the lists' — phone numbers in tables should
 * not expose full digits unless explicitly requested by the user.
 *
 * Example: "+998901234567" → "••••67"
 *          "901234567"     → "••••67"
 *          "12"            → "••"
 *          ""              → "—"
 */
fun maskPhone(value: String): String {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return "—"
    return if (trimmed.length <= 2) "••" else "••••${trimmed.takeLast(2)}"
}

/**
 * Returns true if the current operator should see masked values.
 * Default: always mask in tables — user can tap a row to see full info on
 * the detail screen. This matches the §10 principle: 'mask remaining lists'.
 */
object PrivacyPolicy {
    const val MASK_PHONES_IN_TABLES = true
    const val MASK_PASSPORT_IN_TABLES = true
    const val MASK_ADDRESS_IN_TABLES = true
    const val MASK_PINFL_IN_TABLES = true
}
