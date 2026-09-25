package com.example.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * Одна текстовая аннотация пользователя на странице PDF.
 *
 * Аннотации создаются в [com.example.ui.PdfEditorScreen] — пользователь
 * тапает по странице и вводит текст. Текст может содержать {{placeholders}}
 * (например, {{tenantName}}), которые заменяются на реальные данные при
 * генерации финального PDF для контракта.
 *
 * Координаты [x] и [y] — нормализованные (0.0..1.0), относительно ширины
 * и высоты страницы. Это позволяет корректно отображать аннотации на
 * разных размерах экрана и плотности пикселей.
 *
 * @param pageNumber Номер страницы (0-based)
 * @param x Нормализованная X-координата (0.0 = левый край, 1.0 = правый)
 * @param y Нормализованная Y-координата (0.0 = верх, 1.0 = низ)
 * @param text Текст аннотации (может содержать {{placeholders}})
 * @param fontSize Размер шрифта в pt (по умолчанию 10)
 * @param colorHex Цвет текста в формате #RRGGBB (по умолчанию чёрный)
 */
@Serializable
data class TemplateAnnotation(
    val pageNumber: Int,
    val x: Float,
    val y: Float,
    val text: String,
    val fontSize: Float = 10f,
    val colorHex: String = "#000000"
) {
    companion object {
        /**
         * Парсит JSON-строку с массивом аннотаций.
         * Возвращает пустой список, если JSON пустой или парсинг упал.
         */
        fun parseList(json: String?): List<TemplateAnnotation> {
            if (json.isNullOrBlank()) return emptyList()
            return try {
                kotlinx.serialization.json.Json {
                    ignoreUnknownKeys = true
                }.decodeFromString<List<TemplateAnnotation>>(json)
            } catch (e: Exception) {
                emptyList()
            }
        }

        /**
         * Сериализует список аннотаций в JSON-строку.
         */
        fun serializeList(list: List<TemplateAnnotation>): String {
            return kotlinx.serialization.json.Json {
                encodeDefaults = true
            }.encodeToString(list)
        }
    }
}
