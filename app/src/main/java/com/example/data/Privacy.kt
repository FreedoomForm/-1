package com.example.data

/** Safe display helpers for tables, logs and external snapshots. */
fun maskIdentifier(value: String, visibleTail: Int = 4): String {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return "—"
    return if (trimmed.length <= visibleTail) "••••" else "••••${trimmed.takeLast(visibleTail)}"
}

fun maskAddress(value: String): String = if (value.isBlank()) "—" else "••••"
