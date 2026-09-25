package com.example.data

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Репозиторий для работы с шаблонами PDF-договора.
 *
 * По образцу [ContractHistoryRepository]: простой class без DI, конструктор
 * принимает DAO + ссылку на [RoomDatabase] для транзакций.
 *
 * Особенность метода [setActive] — атомарное переключение: в одной транзакции
 * 1) снимаем isActive со всех версий данного типа,
 * 2) ставим isActive=true на выбранной.
 *
 * Используется [RoomDatabase.withTransaction] — это idiomatic Room API,
 * доступно начиная с Room 2.1; в проекте Room 2.7.0.
 */
class ContractTemplateRepository(
    private val dao: ContractTemplateDao,
    private val db: RoomDatabase
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // ── Реактивные подписки ──────────────────────────────────────────────────
    fun forType(type: String): Flow<List<ContractTemplate>> = dao.getForType(type)
    fun all(): Flow<List<ContractTemplate>> = dao.getAll()
    fun trashed(): Flow<List<ContractTemplate>> = dao.getTrashed()
    fun search(type: String, query: String): Flow<List<ContractTemplate>> =
        if (query.isBlank()) dao.getForType(type) else dao.search(type, query)

    fun activeForType(type: String): Flow<ContractTemplate?> = dao.getActiveForTypeFlow(type)

    // ── One-shot ────────────────────────────────────────────────────────────
    suspend fun getById(id: Int): ContractTemplate? = dao.getById(id)
    suspend fun getActiveForType(type: String): ContractTemplate? = dao.getActiveForType(type)

    // ── CRUD ────────────────────────────────────────────────────────────────
    suspend fun create(type: String, name: String, content: TemplateContent, notes: String? = null): Long {
        val json = json.encodeToString(content)
        // Новая версия создаётся неактивной — пользователь явно назначит её
        // активной через setActive() (или кнопкой ★ в TopAppBar).
        val template = ContractTemplate(
            type = type,
            name = name,
            contentJson = json,
            isActive = false,
            createdAt = System.currentTimeMillis(),
            updatedAt = null,
            notes = notes
        )
        return dao.insert(template)
    }

    suspend fun update(id: Int, name: String, content: TemplateContent, notes: String? = null) {
        val existing = dao.getById(id) ?: return
        dao.update(
            existing.copy(
                name = name,
                contentJson = json.encodeToString(content),
                updatedAt = System.currentTimeMillis(),
                notes = notes
            )
        )
    }

    /**
     * Обновляет только список аннотаций для шаблона (без изменения текста).
     * Используется в [com.example.ui.PdfEditorScreen] при сохранении
     * аннотаций пользователя на PDF странице.
     */
    suspend fun updateAnnotations(id: Int, annotations: List<TemplateAnnotation>) {
        val existing = dao.getById(id) ?: return
        dao.update(
            existing.copy(
                annotationsJson = TemplateAnnotation.serializeList(annotations),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    /**
     * Возвращает список аннотаций для шаблона. Пустой список, если аннотаций
     * нет или JSON невалиден.
     */
    suspend fun getAnnotations(id: Int): List<TemplateAnnotation> {
        val existing = dao.getById(id) ?: return emptyList()
        return TemplateAnnotation.parseList(existing.annotationsJson)
    }

    suspend fun delete(id: Int) = dao.moveToTrash(id)
    suspend fun restore(id: Int) = dao.restoreFromTrash(id)
    suspend fun permanentlyDelete(id: Int) = dao.permanentlyDelete(id)
    suspend fun emptyTrash() = dao.emptyTrash()

    /**
     * Атомарное переключение активной версии для типа.
     *
     * 1) В одной транзакции снимаем isActive со всех версий этого типа
     *    (деактивируем текущую активную).
     * 2) Ставим isActive=true на выбранной версии [id].
     *
     * Гарантия: в любой момент времени для каждого типа ровно одна (или ноль,
     * если все удалены) версия isActive=true.
     */
    suspend fun setActive(type: String, id: Int) = db.withTransaction {
        dao.deactivateAllOfType(type)
        val tpl = dao.getById(id)
        if (tpl != null && tpl.type == type && !tpl.isDeleted) {
            dao.update(tpl.copy(isActive = true, updatedAt = System.currentTimeMillis()))
        }
    }

    /**
     * Десериализует contentJson в [TemplateContent].
     * Возвращает дефолт, если JSON пустой или парсинг упал.
     */
    fun parseContent(contentJson: String): TemplateContent {
        if (contentJson.isBlank()) return TemplateContent()
        return try {
            json.decodeFromString<TemplateContent>(contentJson)
        } catch (e: Exception) {
            TemplateContent()
        }
    }

    /**
     * Сериализует [TemplateContent] в JSON-строку для хранения в БД.
     */
    fun serializeContent(content: TemplateContent): String = json.encodeToString(content)

    /**
     * Безопасный seed: если в таблице нет записей для какого-либо типа,
     * вставляем «Базовый шаблон» с дефолтным содержимым и isActive=true.
     *
     * Вызывается из ContractTemplateViewModel.init — гарантирует, что
     * пользователь ВСЕГДА видит хотя бы один шаблон в «Документообороте»,
     * даже если миграция 36→37 не засеяла таблицу (например, из-за
     * try/catch вокруг seed SQL — если JSON-конструкция упала, таблица
     * осталась пустой, но приложение запустилось).
     *
     * Idempotent: если записи уже есть, ничего не делает.
     */
    suspend fun ensureSeedIfEmpty() = db.withTransaction {
        val existingLimited = dao.getActiveForType(ContractTemplate.TYPE_LIMITED)
        val existingUnlimited = dao.getActiveForType(ContractTemplate.TYPE_UNLIMITED)
        val now = System.currentTimeMillis()
        if (existingLimited == null) {
            // Проверим, есть ли хоть какие-то записи LIMITED — если нет, добавим seed
            val anyLimited = dao.getForTypeOnce(ContractTemplate.TYPE_LIMITED)
            if (anyLimited.isEmpty()) {
                dao.insert(
                    ContractTemplate(
                        type = ContractTemplate.TYPE_LIMITED,
                        name = "Базовый шаблон",
                        contentJson = json.encodeToString(TemplateContent.DEFAULT_FOR_LIMITED),
                        isActive = true,
                        createdAt = now,
                        updatedAt = null
                    )
                )
            }
        }
        if (existingUnlimited == null) {
            val anyUnlimited = dao.getForTypeOnce(ContractTemplate.TYPE_UNLIMITED)
            if (anyUnlimited.isEmpty()) {
                dao.insert(
                    ContractTemplate(
                        type = ContractTemplate.TYPE_UNLIMITED,
                        name = "Базовый шаблон",
                        contentJson = json.encodeToString(TemplateContent.DEFAULT_FOR_UNLIMITED),
                        isActive = true,
                        createdAt = now,
                        updatedAt = null
                    )
                )
            }
        }
    }
}
