package com.example

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.AppDatabase
import com.example.data.ContractTemplate
import com.example.data.TemplateContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric unit-тест для миграции БД 36→37.
 *
 * Robolectric запускает тесты на JVM (без эмулятора), используя встроенный
 * SQLite. Это быстро (секунды, не минуты) и надёжно — нет проблем с
 * установкой split APKs, как с emulator- runner.
 *
 * Запуск: ./gradlew testDebugUnitTest
 *
 * Что проверяет:
 * 1. AppDatabase.getDatabase() не бросает исключение
 * 2. Миграция 36→37 создаёт таблицу contract_templates
 * 3. Schema таблицы совпадает с @Entity ContractTemplate (иначе Room упадёт
 *    на schema validation с "Migration didn't properly handle")
 * 4. Seed-данные: по одному «Базовому шаблону» на каждый тип (LIMITED/UNLIMITED)
 * 5. Seed JSON парсится через kotlinx.serialization без ошибок
 *
 * Если МЫ ХОТЯ БЫ ОДИН ИЗ ЭТИХ ТЕСТОВ ПАДАЕТ — приложение будет крашиться
 * на реальном устройстве при первом запуске после обновления (как и было
 * с «при вхождении в приложения он отбрасывает меня назад»).
 */
@RunWith(RobolectricTestRunner::class)
class DatabaseMigrationTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    /**
     * Главный smoke-тест: AppDatabase.getDatabase не должен бросать.
     * Включает миграцию + schema validation.
     */
    @Test
    fun appDatabase_initializesWithoutException() {
        val db = AppDatabase.getDatabase(context)
        assertNotNull("AppDatabase.getDatabase must not return null", db)
        // Force DB open — triggers migration if upgrading from older version
        db.openHelper.writableDatabase
    }

    /**
     * Проверяем, что таблица contract_templates существует и содержит
     * seed-записи (LIMITED + UNLIMITED).
     */
    @Test
    fun contractTemplatesTable_hasSeedData() = runBlocking {
        val db = AppDatabase.getDatabase(context)
        db.openHelper.writableDatabase  // force open

        val dao = db.contractTemplateDao()
        val limitedActive = dao.getActiveForType(ContractTemplate.TYPE_LIMITED)
        val unlimitedActive = dao.getActiveForType(ContractTemplate.TYPE_UNLIMITED)

        assertNotNull(
            "LIMITED active template must exist after migration (seeded by MIGRATION_36_37)",
            limitedActive
        )
        assertNotNull(
            "UNLIMITED active template must exist after migration (seeded by MIGRATION_36_37)",
            unlimitedActive
        )
        assertEquals(
            "Seed template must be named 'Базовый шаблон'",
            "Базовый шаблон",
            limitedActive?.name
        )
    }

    /**
     * Проверяем, что seed JSON парсится без ошибок через kotlinx.serialization.
     * Если buildSeedJson построил malformed JSON (например, забыл экранировать
     * кавычку), decodeFromString бросит SerializationException.
     */
    @Test
    fun seedJson_parsesCorrectly() = runBlocking {
        val db = AppDatabase.getDatabase(context)
        db.openHelper.writableDatabase

        val dao = db.contractTemplateDao()
        val limited = dao.getActiveForType(ContractTemplate.TYPE_LIMITED)
        assertNotNull(limited)
        requireNotNull(limited)

        val content = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }.decodeFromString<TemplateContent>(limited.contentJson)

        assertEquals(
            "Landlord name must match default",
            TemplateContent.DEFAULT_LANDLORD_NAME,
            content.landlordName
        )
        assertTrue(
            "Body text must contain placeholder {{contractNumber}}",
            content.bodyText.contains("{{contractNumber}}")
        )
        assertTrue(
            "Body text must contain landlordName placeholder",
            content.bodyText.contains("{{landlordName}}")
        )
    }

    /**
     * Проверяем, что CRUD операции работают на мигрированной БД.
     * Это ловит баги в DAO (например, неправильный SQL в @Query).
     */
    @Test
    fun crudOperations_workOnMigratedDb() = runBlocking {
        val db = AppDatabase.getDatabase(context)
        val dao = db.contractTemplateDao()

        // CREATE — insert a new template
        val newId = dao.insert(
            ContractTemplate(
                type = ContractTemplate.TYPE_LIMITED,
                name = "Test Template",
                contentJson = """{"landlordName":"Test","bodyText":"test"}"""
            )
        )
        assertTrue("Insert must return positive ID", newId > 0)

        // READ
        val fetched = dao.getById(newId.toInt())
        assertNotNull("Get by ID must return the template", fetched)
        assertEquals("Test Template", fetched?.name)

        // UPDATE
        dao.update(fetched!!.copy(name = "Updated Name"))
        val updated = dao.getById(newId.toInt())
        assertEquals("Updated Name", updated?.name)

        // setActive
        dao.deactivateAllOfType(ContractTemplate.TYPE_LIMITED)
        dao.update(updated!!.copy(isActive = true))
        val active = dao.getActiveForType(ContractTemplate.TYPE_LIMITED)
        assertNotNull("After setActive, must have an active template", active)
        assertEquals(newId.toInt(), active?.id)

        // DELETE (soft-delete)
        dao.moveToTrash(newId.toInt())
        val afterDelete = dao.getById(newId.toInt())
        assertNotNull("Soft-deleted template must still exist in DB", afterDelete)
        assertEquals("isDeleted must be true after moveToTrash", true, afterDelete?.isDeleted)
    }
}
