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

/**
 * §10: Scrubbing utilities for tokens / API keys / secrets.
 *
 * Используются перед записью в лог, external AI-снимок, резервную копию или
 * обмен данными с сервером. Цель: гарантировать, что чувствительные данные
 * никогда не покидают устройство в открытом виде.
 */
object SecretScrubber {

    /** Паттерны, которые считаем секретами. */
    private val SECRET_PATTERNS = listOf(
        Regex("(?i)(api[_-]?key)\\s*[:=]\\s*[\"']?[A-Za-z0-9_\\-]{16,}[\"']?"),
        Regex("(?i)(token)\\s*[:=]\\s*[\"']?[A-Za-z0-9_\\-]{16,}[\"']?"),
        Regex("(?i)(secret)\\s*[:=]\\s*[\"']?[A-Za-z0-9_\\-]{16,}[\"']?"),
        Regex("(?i)(bearer)\\s+[A-Za-z0-9_\\-]{16,}"),
        Regex("(?i)(password)\\s*[:=]\\s*[\"']?[^\"'\\s]{4,}[\"']?")
    )

    /** Маскирует все секреты в строке, заменяя их на `***REDACTED***`. */
    fun scrub(input: String): String {
        var result = input
        SECRET_PATTERNS.forEach { pattern ->
            result = pattern.replace(result) { mr ->
                val key = mr.groupValues.getOrNull(1) ?: "secret"
                "$key=***REDACTED***"
            }
        }
        return result
    }

    /** Возвращает true если в строке обнаружен потенциальный секрет. */
    fun containsSecret(input: String): Boolean =
        SECRET_PATTERNS.any { it.containsMatchIn(input) }

    /**
     * Проверяет, что BackupManager не должен сериализовать поле.
     * Имена полей, которые всегда исключаются из резервных копий.
     */
    val BACKUP_EXCLUDED_FIELDS = setOf(
        "apiKey", "api_key", "API_KEY",
        "token", "authToken", "access_token", "refresh_token",
        "secret", "clientSecret", "JWT_SECRET",
        "password", "passwordHash",
        "Bearer"
    )
}
