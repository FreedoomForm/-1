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
}
