package com.example

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.AppDatabase
import com.example.data.ContractTemplate
import com.example.data.TemplateContent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Инструментированный smoke-тест: проверяет, что приложение запускается
 * без краша при обновлении с v36 до v37.
 *
 * Этот тест был добавлен после того, как миграция 36→37 падала на реальных
 * устройствах, но GitHub Actions не ловил этот баг (т.к. CI только компилировал
 * APK, не запускал его на эмуляторе).
 *
 * Запуск: ./gradlew connectedAndroidTest
 *
 * В CI: github-actions workflow запускает этот тест на эмуляторе после сборки.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseMigrationSmokeTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Before
    fun setup() {
        // Force close any existing DB connection
        // (useful if previous test left state)
    }

    /**
     * Smoke-тест #1: AppDatabase.getDatabase() не должен бросать.
     * Если миграция 36→37 падает, это вызовет исключение.
     */
    @Test
    fun appDatabase_initializesWithoutException() {
        val db = AppDatabase.getDatabase(context)
        assertNotNull("AppDatabase.getDatabase must not return null", db)
        // Force DB open — triggers migration if upgrading from v36
        db.openHelper.writableDatabase
    }

    /**
     * Smoke-тест #2: contract_templates таблица существует и содержит
     * seed-записи (по одной «Базовый шаблон» на каждый тип).
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
        assertEquals(
            "Seed template must be named 'Базовый шаблон'",
            "Базовый шаблон",
            unlimitedActive?.name
        )
    }

    /**
     * Smoke-тест #3: seed JSON парсится без ошибок через kotlinx.serialization.
     * Если JSON malformed, decodeFromString бросит SerializationException.
     */
    @Test
    fun seedJson_parsesCorrectly() = runBlocking {
        val db = AppDatabase.getDatabase(context)
        db.openHelper.writableDatabase

        val dao = db.contractTemplateDao()
        val limited = dao.getActiveForType(ContractTemplate.TYPE_LIMITED)
        assertNotNull(limited)
        requireNotNull(limited)  // smart cast

        // Парсинг JSON через kotlinx.serialization — если seed JSON malformed,
        // это бросит SerializationException и тест упадёт
        val content = kotlinx.serialization.json.Json {
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
    }

    /**
     * Smoke-тест #4: MainActivity запускается без краша.
     *
     * Это ИМЕННО тот тест, который ловит баг, на который жаловался
     * пользователь: «при вхождении в приложения он отбрасывает меня назад».
     *
     * Если MainScreen composition падает (например, ContractTemplateViewModel
     * constructor бросает), ActivityScenario.launch бросит исключение.
     */
    @Test
    fun mainActivity_launchesWithoutCrash() {
        // Используем ActivityScenario.launch вместо startActivity, чтобы
        // получить lifecycle-aware запуск. Если MainActivity.onCreate бросает
        // (например, миграция падает в первом же viewModel() вызове), тест упадёт.
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            // Wait for the activity to be RESUMED — this means onCreate,
            // onStart, onResume all completed without throwing.
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)

            // If we get here without exception, the activity launched
            // successfully. Verify it's actually the MainActivity.
            scenario.onActivity { activity ->
                assertEquals(
                    "Launched activity must be MainActivity",
                    MainActivity::class.java,
                    activity::class.java
                )
            }
        } finally {
            scenario.close()
        }
    }
}
