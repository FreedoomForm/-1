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
 * Два режима аннотаций:
 * 1. TEXT_OVERLAY ([width] = 0 или [height] = 0) — просто добавляет текст
 *    поверх PDF. Используется когда пользователь тапает в пустое место.
 * 2. TEXT_REPLACEMENT ([width] > 0 и [height] > 0) — "Word-like" редактирование:
 *    покрывает существующий текст белой заливкой, затем рисует новый текст.
 *    Используется когда пользователь тапает на существующий текст и редактирует
 *    его. Это и есть подход Word-like редакторов PDF (PDFTron, Foxit и т.д.).
 *
 * @param pageNumber Номер страницы (0-based)
 * @param x Нормализованная X-координата (0.0 = левый край, 1.0 = правый)
 * @param y Нормализованная Y-координата (0.0 = верх, 1.0 = низ)
 * @param text Текст аннотации (может содержать {{placeholders}})
 * @param fontSize Размер шрифта в pt (по умолчанию 10)
 * @param colorHex Цвет текста в формате #RRGGBB (по умолчанию чёрный)
 * @param width Нормализованная ширина области замены (0 = text overlay,
 *              >0 = text replacement — белая заливка поверх старого текста)
 * @param height Нормализованная высота области замены
 * @param isReplacement true если это замена существующего текста (для UI)
 */
@Serializable
data class TemplateAnnotation(
    val pageNumber: Int,
    val x: Float,
    val y: Float,
    val text: String,
    val fontSize: Float = 10f,
    val colorHex: String = "#000000",
    val width: Float = 0f,
    val height: Float = 0f,
    val isReplacement: Boolean = false
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
