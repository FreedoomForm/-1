# План v2: страница «Документооборот» с управлением версиями шаблонов

> **Статус:** ЧЕРНОВИК v2 — полностью заменяет план v1 от 2026-09-25 (тот был основан на неверном понимании задачи).
> **Дата:** 2026-09-25 (обновлён)
> **Источник:** дополнительный аудит UI-паттернов приложения (`UnifiedButton`, `MainActivity` TopAppBar, `ContractListScreen`, `RenterDao`, миграции БД 34→35→36).

---

## 1. Краткое резюме (TL;DR)

Добавить **8-ю вкладку «Документооборот»** в нижнюю навигацию `MainActivity.kt`. На странице пользователь управляет **несколькими версиями шаблонов PDF-договора** двух типов: «бесконечная аренда» (`PdfContractGenerator.generateUnlimited`) и «конечная аренда» (`PdfContractGenerator.generate`).

**Ключевые свойства:**
- Для каждого типа договора может существовать **несколько версий** шаблона (entity в новой таблице Room `contract_templates`, миграция 36→37).
- В любой момент времени **только одна** версия каждого типа помечена как «активная» (`isActive = true`). Именно её использует существующая кнопка PDF в карточке арендатора/контракта — поведение не меняется.
- На странице сверху есть универсальные кнопки: создать / редактировать / удалить / искать версии (по образцу `ContractListScreen` с числовыми триггерами `createTrigger++`).
- **При переходе на вкладку 7** универсальная кнопка SMS в `TopAppBar` (MainActivity.kt:1431–1550) скрывается и на её месте появляется кнопка ★ (звезда, по образцу `combinedClickable`):
  - Короткое нажатие → назначить выбранную версию как активную для её типа.
  - Долгое нажатие → скачать PDF выбранной версии с демо-данными (выбранный клиент).
- Пользователь выбирает тип документа → выбирает версию → выбирает клиента из списка (renters) → крутит вниз, где в окне превью видит сгенерированный PDF (рендер через `android.graphics.pdf.PdfRenderer` из SDK, без новых библиотек).

---

## 2. Что было изучено во втором аудите

### 2.1. `UnifiedButton` — переиспользуемые кнопки

**Файл:** `app/src/main/java/com/example/ui/components/UnifiedButton.kt` (292 строки)

API:
```kotlin
enum class UnifiedButtonVariant { PRIMARY, SECONDARY, SUCCESS, DANGER, DANGER_OUTLINED, TEXT }

@Composable
fun UnifiedButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: UnifiedButtonVariant = UnifiedButtonVariant.PRIMARY,
    enabled: Boolean = true,
    loading: Boolean = false,
    height: Int = 44,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
)
```

Алиасы: `PrimaryButton`, `SecondaryButton`, `SuccessButton`, `DangerButton`, `DangerOutlinedButton`, `TextActionButton`. Анимация: scale→0.95, иконка поворачивается на -8°.

**Вывод:** использовать `PrimaryButton` (создать), `SecondaryButton` (редактировать / искать), `DangerButton` (удалить), `SuccessButton` (звезда-«сделать активной» — teal filled, perfect fit).

### 2.2. Существующая кнопка SMS в TopAppBar (MainActivity.kt:1431–1550)

Это **raw `Box + combinedClickable + Icon`** размером 56dp, с фоном `if (smsAutoSend) StatusOk else StatusOverdue`, иконка `Icons.Default.Sms`. Кнопка видна на всех вкладках (`actions { if (!isSearchMode) { … } }`). Поведение onClick зависит от `currentTab`:
- на вкладке 0 → отправляет SMS выбранным арендаторам;
- на остальных → Toast «перейдите на вкладку арендаторов».

Паттерн `combinedClickable(onClick = …, onLongClick = …)` — **прямой образец** для кнопки ★.

### 2.3. CRUD-триггеры из TopAppBar в активный экран

**Файл:** `ContractListScreen.kt:81–181` + `MainActivity.kt:1586–1741`.

Шаблон:
1. В `MainActivity` объявляются числовые триггеры: `var contractCreateTrigger by remember { mutableStateOf(0) }` и т.д.
2. В `TopAppBar` кнопки `+ / ✎ / 🗑` (raw `IconButton`, 56dp, цвета ClaudeAccent / outlined) делают `contractCreateTrigger++` / `contractEditTrigger++` / `contractDeleteTrigger++`.
3. В вызов `ContractListScreen(...)` параметры `createTrigger`, `editTrigger`, `deleteTrigger` передаются.
4. Внутри экрана `LaunchedEffect(createTrigger) { if (createTrigger > lastCreateTrigger) showCreateDialog = true; lastCreateTrigger = createTrigger }` — реакция только на РЕАЛЬНОЕ увеличение значения (не на вход во вкладку).

### 2.4. Существующий паттерн «несколько версий с одной активной»

Ближайшие аналоги:
- `VirtualCard.isDefault: Boolean` — но это «системная неудаляемая», а НЕ «текущая активная».
- `SettingsRepository.selectedSimSubscriptionId: Int` — SharedPreferences-аналог «выбранной одной из нескольких».
- `Renter.autoRenewMode` (`MANUAL`/`AUTO`) — миграция 34→35 через `ALTER TABLE`.

**Решение:** создать **новую таблицу Room** `contract_templates` с миграцией 36→37 (по образцу MIGRATION_34_35 / MIGRATION_35_36). Не SharedPreferences — нужны CRUD нескольких записей.

### 2.5. `RenterDao` для селектора клиентов

`RenterDao.kt`:
```kotlin
@Query("SELECT * FROM renters WHERE isDeleted = 0 ORDER BY isReturned ASC, rentStartDateTimestamp DESC")
fun getLiveRenters(): Flow<List<Renter>>   // активные, не в корзине
```

В `MainActivity` `RenterViewModel` уже инициализирован, его `rentersList` уже прокидывается в `ContractListScreen`. Можно переиспользовать — селектор клиентов на новой странице берёт данные из того же `RenterViewModel`.

### 2.6. FileProvider и пути для PDF

`res/xml/file_paths.xml` уже содержит:
```xml
<cache-path name="cache" path="." />          ← для превью
<external-path name="documents" path="Documents/ScooterContracts/" /> ← для скачивания
```

**Вывод:** превью сохраняем в `cacheDir` (авто-чистится системой), скачивание — в публичную папку `Documents/ScooterContracts/` (как сейчас).

### 2.7. PdfRenderer — встроенный Android SDK вьюер PDF

Android SDK содержит `android.graphics.pdf.PdfRenderer` (с API 21). Можно открыть PDF-файл, получить страницы как `Bitmap`, отобразить в `LazyColumn`. **НЕ нужны** сторонние библиотеки (`barteksc:android-pdf-viewer` и т.п.). Это сэкономит 2–3 MB APK.

### 2.8. Образец диалога создания сущности

`ContractListScreen.kt:1067–1095` — `AlertDialog` с `PrimaryButton` (confirm) и `TextActionButton` (dismiss), тело — `Column { OutlinedTextField(...) }` в `verticalScroll`. Прямой шаблон для диалога «Создать версию шаблона».

---

## 3. Архитектура решения

### 3.1. Новая сущность Room — `ContractTemplate`

**Новый файл:** `app/src/main/java/com/example/data/ContractTemplate.kt`

```kotlin
@Entity(tableName = "contract_templates")
data class ContractTemplate(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    /** Тип договора: "UNLIMITED" (бесконечная) или "LIMITED" (конечная). */
    val type: String,
    /** Имя версии, напр. "Базовая редакция", "Версия 2 — смена банка". */
    val name: String,
    /**
     * Содержимое шаблона в JSON.
     * Структура: { "landlordName": "...", "landlordAddress": "...", ...,
     *              "landlordDirector": "...", "bodyText": "текст с ${placeholders}" }
     */
    val contentJson: String,
    /** true — это АКТИВНАЯ версия для своего type. Только одна на type. */
    val isActive: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long? = null,
    // Soft-delete (по образцу всех сущностей v36)
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null
) {
    companion object {
        const val TYPE_UNLIMITED = "UNLIMITED"   // → PdfContractGenerator.generateUnlimited
        const val TYPE_LIMITED   = "LIMITED"     // → PdfContractGenerator.generate
    }
}

/**
 * Десериализованное содержимое шаблона.
 */
@Serializable
data class TemplateContent(
    val landlordName: String = "ЯТТ «АСИЛБЕКОВ ШЕРЗОД УЛУГБЕКОВИЧ»",
    val landlordAddress: String = "Тошкент Шахри, Юнусобод тумани, Сайилгох кучаси, 17-уй",
    val landlordBank: String = "Тошкент Ш., «КАПИТАЛБАНК» АТ БАНКИНИНГ БОШ ОФИСИ",
    val landlordAccount: String = "20218 000 9 04982540 001",
    val landlordMfo: String = "01088",
    val landlordInn: String = "32607780220041",
    val landlordPhone: String = "+998 77 777 10 00",
    val landlordDirector: String = "Асилбеков Шерзод Улугбекович",
    /** Полный текст договора с ${placeholders}. */
    val bodyText: String = DEFAULT_CONTRACT_TEMPLATE_WEEKLY
) {
    companion object {
        const val DEFAULT_CONTRACT_TEMPLATE_WEEKLY = """[текущий текст из PdfContractGenerator.kt:149-338]"""
        const val DEFAULT_CONTRACT_TEMPLATE_UNLIMITED = """[текущий текст из PdfContractGenerator.kt:444-637]"""
    }
}
```

### 3.2. Новый DAO — `ContractTemplateDao`

**Новый файл:** `app/src/main/java/com/example/data/ContractTemplateDao.kt`

```kotlin
@Dao
interface ContractTemplateDao {
    @Query("SELECT * FROM contract_templates WHERE isDeleted = 0 AND type = :type ORDER BY isActive DESC, updatedAt DESC")
    fun getForType(type: String): Flow<List<ContractTemplate>>

    @Query("SELECT * FROM contract_templates WHERE isDeleted = 0 ORDER BY type ASC, isActive DESC, updatedAt DESC")
    fun getAll(): Flow<List<ContractTemplate>>

    @Query("SELECT * FROM contract_templates WHERE isDeleted = 0 AND type = :type AND name LIKE '%' || :query || '%' ORDER BY isActive DESC, updatedAt DESC")
    fun search(type: String, query: String): Flow<List<ContractTemplate>>

    @Query("SELECT * FROM contract_templates WHERE id = :id LIMIT 1")
    suspend fun getById(id: Int): ContractTemplate?

    @Query("SELECT * FROM contract_templates WHERE isDeleted = 0 AND type = :type AND isActive = 1 LIMIT 1")
    suspend fun getActiveForType(type: String): ContractTemplate?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(template: ContractTemplate): Long

    @Update
    suspend fun update(template: ContractTemplate)

    @Query("UPDATE contract_templates SET isActive = 0, updatedAt = :now WHERE type = :type AND isDeleted = 0")
    suspend fun deactivateAllOfType(type: String, now: Long = System.currentTimeMillis())

    /** Атомарное переключение: снять isActive со всех версий этого типа, поставить на выбранную. */
    @Transaction
    suspend fun setActive(type: String, id: Int) {
        deactivateAllOfType(type)
        // update через @Update — нужен объект; получим, затем обновим
    }

    @Query("UPDATE contract_templates SET isDeleted = 1, deletedAt = :now WHERE id = :id")
    suspend fun moveToTrash(id: Int, now: Long = System.currentTimeMillis())

    @Query("UPDATE contract_templates SET isDeleted = 0, deletedAt = NULL WHERE id = :id")
    suspend fun restoreFromTrash(id: Int)
}
```

**Важно:** `@Transaction` с suspend реализуется через `RoomDatabase.withTransaction { }` в репозитории (т.к. DAO не может комбинировать `suspend fun deactivateAllOfType` + suspend `getById` + `update` атомарно через @Query).

### 3.3. Миграция БД 36→37

**Изменяемый файл:** `app/src/main/java/com/example/data/AppDatabase.kt`

```kotlin
@Database(
    entities = [
        Renter::class,
        Scooter::class,
        NotificationHistoryEntity::class,
        ContractHistoryEntry::class,
        Transaction::class,
        VirtualCard::class,
        CardTransaction::class,
        ContractTemplate::class   // ← НОВАЯ СУЩНОСТЬ
    ],
    version = 37,                 // ← БЫЛО 36
    exportSchema = false          // оставить false (существующая практика)
)
abstract class AppDatabase : RoomDatabase() {
    // ... существующие DAO
    abstract fun contractTemplateDao(): ContractTemplateDao   // ← НОВЫЙ DAO
    // ...
    companion object {
        private val MIGRATION_36_37 = object : Migration(36, 37) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `contract_templates` (
                        `id`         INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        `type`       TEXT NOT NULL,
                        `name`       TEXT NOT NULL,
                        `contentJson` TEXT NOT NULL,
                        `isActive`   INTEGER NOT NULL DEFAULT 0,
                        `createdAt`  INTEGER NOT NULL,
                        `updatedAt`  INTEGER,
                        `isDeleted`  INTEGER NOT NULL DEFAULT 0,
                        `deletedAt`  INTEGER
                    )
                """.trimIndent())
                // ── SEED: одна «базовая» версия на каждый тип, isActive=1 ─────
                // Так существующие кнопки PDF продолжат работать идентично.
                val now = System.currentTimeMillis()
                val weeklyJson = TemplateContent(            // Jackson/Gson/Moshi/kotlinx.serialization
                    bodyText = TemplateContent.DEFAULT_CONTRACT_TEMPLATE_WEEKLY
                ).toJson()
                val unlimitedJson = TemplateContent(
                    bodyText = TemplateContent.DEFAULT_CONTRACT_TEMPLATE_UNLIMITED
                ).toJson()
                val seed = ContentValues().apply {
                    put("type", "LIMITED")
                    put("name", "Базовый шаблон")
                    put("contentJson", weeklyJson)
                    put("isActive", 1)
                    put("createdAt", now)
                    put("isDeleted", 0)
                    putNull("updatedAt")
                    putNull("deletedAt")
                }
                db.insert("contract_templates", SQLiteDatabase.CONFLICT_REPLACE, seed)
                // ... аналогично для UNLIMITED
            }
        }

        fun getDatabase(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "scooter_rent_db"
            )
                .addMigrations(
                    // ... существующие
                    MIGRATION_36_37                                    // ← ДОБАВИТЬ
                )
                .fallbackToDestructiveMigration(true)
                .build().also { INSTANCE = it }
        }
    }
}
```

**Безопасность миграции:** т.к. используется `fallbackToDestructiveMigration(true)`, любая ошибка в `MIGRATION_36_37` приведёт к потере ВСЕХ данных (renters, contracts, transactions). Поэтому:
- SQL-запросы тщательно протестировать в `androidTest` (Robolectric).
- На отладочном устройстве выполнить ручной тест (см. этап 8).

### 3.4. Сериализация JSON

В проекте нет встроенной JSON-библиотеки (Gson/Moshi/kotlinx.serialization не подключены в `build.gradle.kts`). Варианты:
- **(рекомендую)** `kotlinx.serialization` — добавить плагин `org.jetbrains.kotlin.plugin.serialization` (Kotlin 2.2.10 совместим) + зависимость `org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3`. Уже включено в Kotlin 2.x, нужно только добавить плагин в `build.gradle.kts`.
- `org.json.JSONObject` (встроен в Android SDK, без новых зависимостей) — ручной парсинг, менее удобно.
- Сохранить поля как отдельные колонки в таблице (8 TEXT колонок для реквизитов + 1 TEXT для bodyText). Это много колонок, но просто.

**(рекомендую) вариант A (kotlinx.serialization)** — это самый чистый путь и однострочный `Json.encodeToString` / `Json.decodeFromString`. Добавление плагина — 2 строки в `build.gradle.kts`.

### 3.5. Repository — `ContractTemplateRepository`

**Новый файл:** `app/src/main/java/com/example/data/ContractTemplateRepository.kt`

```kotlin
class ContractTemplateRepository(
    private val dao: ContractTemplateDao,
    private val db: RoomDatabase
) {
    fun forType(type: String): Flow<List<ContractTemplate>> = dao.getForType(type)
    fun all(): Flow<List<ContractTemplate>> = dao.getAll()
    fun search(type: String, query: String): Flow<List<ContractTemplate>> = dao.search(type, query)

    suspend fun getById(id: Int): ContractTemplate? = dao.getById(id)
    suspend fun getActiveForType(type: String): ContractTemplate? = dao.getActiveForType(type)

    suspend fun create(type: String, name: String, content: TemplateContent): Long {
        val json = Json.encodeToString(content)
        return dao.insert(ContractTemplate(type = type, name = name, contentJson = json))
    }

    suspend fun update(id: Int, name: String, content: TemplateContent) {
        val existing = dao.getById(id) ?: return
        dao.update(existing.copy(name = name, contentJson = Json.encodeToString(content), updatedAt = System.currentTimeMillis()))
    }

    suspend fun delete(id: Int) = dao.moveToTrash(id)
    suspend fun restore(id: Int) = dao.restoreFromTrash(id)

    /** Атомарно переключить активную версию для типа. */
    suspend fun setActive(type: String, id: Int) = db.withTransaction {
        dao.deactivateAllOfType(type)
        val tpl = dao.getById(id) ?: return@withTransaction
        dao.update(tpl.copy(isActive = true, updatedAt = System.currentTimeMillis()))
    }
}
```

### 3.6. ViewModel — `ContractTemplateViewModel`

**Новый файл:** `app/src/main/java/com/example/ui/ContractTemplateViewModel.kt`

```kotlin
class ContractTemplateViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getDatabase(application)
    private val repo = ContractTemplateRepository(db.contractTemplateDao(), db)
    private val renterRepo = RenterRepository(db.renterDao())
    private val scooterRepo = ScooterRepository(db.scooterDao())

    // ── UI State ────────────────────────────────────────────────────────────
    private val _selectedType = MutableStateFlow(ContractTemplate.TYPE_UNLIMITED)
    val selectedType: StateFlow<String> = _selectedType

    private val _selectedTemplateId = MutableStateFlow<Int?>(null)
    val selectedTemplateId: StateFlow<Int?> = _selectedTemplateId

    private val _selectedRenterId = MutableStateFlow<Int?>(null)
    val selectedRenterId: StateFlow<Int?> = _selectedRenterId

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    val templates: StateFlow<List<ContractTemplate>> =
        _selectedType.flatMapLatest { type ->
            if (_searchQuery.value.isBlank()) repo.forType(type)
            else repo.search(type, _searchQuery.value)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val renters: StateFlow<List<Renter>> = renterRepo.liveRenters
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ── Превью PDF (Bitmap страниц) ─────────────────────────────────────────
    private val _previewBitmaps = MutableStateFlow<List<Bitmap>>(emptyList())
    val previewBitmaps: StateFlow<List<Bitmap>> = _previewBitmaps
    private val _isPreviewLoading = MutableStateFlow(false)
    val isPreviewLoading: StateFlow<Boolean> = _isPreviewLoading

    // ── Триггеры CRUD из TopAppBar ───────────────────────────────────────────
    fun reactToCreateTrigger(trigger: Int, last: Int): Int {
        if (trigger > last) { /* открыть диалог создания (через callback) */ }
        return trigger
    }
    fun reactToEditTrigger(trigger: Int, last: Int, selectedId: Int?): Int { ... }
    fun reactToDeleteTrigger(trigger: Int, last: Int, selectedId: Int?): Int { ... }

    // ── Actions ──────────────────────────────────────────────────────────────
    fun selectType(type: String) { _selectedType.value = type; _selectedTemplateId.value = null }
    fun selectTemplate(id: Int) { _selectedTemplateId.value = id }
    fun selectRenter(id: Int) { _selectedRenterId.value = id }
    fun setSearchQuery(q: String) { _searchQuery.value = q }

    fun createTemplate(name: String, content: TemplateContent) = viewModelScope.launch {
        repo.create(_selectedType.value, name, content)
    }
    fun updateTemplate(id: Int, name: String, content: TemplateContent) = viewModelScope.launch {
        repo.update(id, name, content)
        regeneratePreview()
    }
    fun deleteTemplate(id: Int) = viewModelScope.launch { repo.delete(id) }
    fun setActive(id: Int) = viewModelScope.launch { repo.setActive(_selectedType.value, id) }

    /** Скачать выбранную версию с демо-данными → вернуть Uri файла. */
    suspend fun downloadSelectedWithDemoData(): Uri? = withContext(Dispatchers.IO) {
        val template = _selectedTemplateId.value?.let { repo.getById(it) } ?: return@withContext null
        val content = Json.decodeFromString<TemplateContent>(template.contentJson)
        val renter = _selectedRenterId.value?.let { renterRepo.getById(it) }
            ?: renters.value.firstOrNull()
            ?: return@withContext null
        val scooter = renter.scooterId?.let { scooterRepo.getById(it) }
        val uri = when (_selectedType.value) {
            ContractTemplate.TYPE_UNLIMITED ->
                PdfContractGenerator.generateUnlimited(getApplication(), renter, scooter, content)
            else -> {
                // Создаём фейковый entry на основе renter (для превью)
                val entry = makePreviewEntry(renter, scooter)
                PdfContractGenerator.generate(getApplication(), entry, renter, scooter, content)
            }
        }
        uri
    }

    /** Сгенерировать превью PDF → отрендерить страницы в Bitmap. */
    suspend fun regeneratePreview() = withContext(Dispatchers.IO) {
        _isPreviewLoading.value = true
        try {
            val template = _selectedTemplateId.value?.let { repo.getById(it) }
                ?: repo.getActiveForType(_selectedType.value)
                ?: return@withContext
            val content = Json.decodeFromString<TemplateContent>(template.contentJson)
            val renter = _selectedRenterId.value?.let { renterRepo.getById(it)
                ?: renters.value.firstOrNull() ?: return@withContext
            val scooter = renter.scooterId?.let { scooterRepo.getById(it) }
            val entry = makePreviewEntry(renter, scooter)
            // 1. Сгенерировать PDF во cacheDir
            val pdfFile = File(cacheDir, "preview_${System.currentTimeMillis()}.pdf")
            val uri = when (_selectedType.value) {
                UNLIMITED -> PdfContractGenerator.generateUnlimitedTo(app, renter, scooter, content, pdfFile)
                else -> PdfContractGenerator.generateTo(app, entry, renter, scooter, content, pdfFile)
            }
            // 2. Рендерить страницы в Bitmap через PdfRenderer
            val bitmaps = renderPdfToBitmaps(pdfFile)
            _previewBitmaps.value = bitmaps
        } finally {
            _isPreviewLoading.value = false
        }
    }

    private fun makePreviewEntry(renter: Renter, scooter: Scooter?): ContractHistoryEntry {
        val now = System.currentTimeMillis()
        return ContractHistoryEntry(
            id = 999, renterId = renter.id, timestamp = now, type = "CREATED",
            renterName = renter.name, renterPhone = renter.phoneNumber,
            scooterName = renter.scooterName ?: scooter?.name,
            weekStart = renter.rentStartDateTimestamp,
            weekEnd = renter.rentStartDateTimestamp + renter.rentDurationDays * 24L * 60 * 60 * 1000,
            weeklyPrice = SettingsRepository(getApplication()).weeklyPrice,
            passportData = renter.passportData, address = renter.address, pinfl = renter.pinfl,
            vinNumber = scooter?.vinNumber ?: "", engineNumber = scooter?.engineNumber ?: "",
            scooterSerialNumber = scooter?.scooterSerialNumber ?: "",
            batteryId1 = scooter?.batteryId1 ?: "", batteryId2 = scooter?.batteryId2 ?: "",
            additionalInfo = scooter?.additionalInfo ?: ""
        )
    }

    /** Рендер PDF в Bitmap'ы через android.graphics.pdf.PdfRenderer. */
    private fun renderPdfToBitmaps(file: File): List<Bitmap> {
        val parcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(parcelFileDescriptor)
        try {
            return (0 until renderer.pageCount).map { i ->
                val page = renderer.openPage(i)
                val bitmap = Bitmap.createBitmap(595, 842, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                bitmap
            }
        } finally {
            renderer.close()
            parcelFileDescriptor.close()
        }
    }
}
```

### 3.7. Изменение `PdfContractGenerator` — НЕ ТРОГАТЬ существующие методы

**КРИТИЧНО:** пользователь сказал «текущие кнопки пдф документа не изменялись». Это значит:
- `generate(context, entry, renter, scooter)` — оставить СТАРУЮ сигнатуру, использовать СТАРЫЙ захардкоженный текст (если активная версия = дефолт, результат идентичен).
- `generateUnlimited(context, renter, scooter)` — то же.

**НО:** существующие кнопки в карточке арендатора должны использовать активную версию шаблона. Решение:

1. Оставить `generate(...)` и `generateUnlimited(...)` как есть (старый захардкоженный текст).
2. Внутри них — если в БД есть активная версия для соответствующего типа, переопределить `LANDLORD_*` и тело текста значениями из активной версии.
3. Это прозрачное изменение: на свежеустановленном приложении активная версия = «Базовый шаблон» с дефолтным текстом → результат идентичен текущему.

```kotlin
// Внутри generate() и generateUnlimited() в начале функции:
val settings = SettingsRepository(context)
val db = AppDatabase.getDatabase(context)
val activeTemplate = runBlocking { db.contractTemplateDao().getActiveForType(TYPE_LIMITED) }
val content = activeTemplate?.let { Json.decodeFromString<TemplateContent>(it.contentJson) }

// Если content != null — использовать content.landlordName вместо LANDLORD_NAME и т.д.
// Если null — использовать DEFAULT_*. В обоих случаях результат корректный.
```

**Альтернатива** (предлагаю — чище): добавить **новые** методы `generate(context, entry, renter, scooter, content: TemplateContent)` и `generateUnlimited(context, renter, scooter, content: TemplateContent)`. Старые методы делегируют в новые с `content = TemplateContent()` (дефолт). Это сохраняет старый API для существующих вызовов и даёт новый API для превью с произвольной версией.

**Дополнительно** — методы, сохраняющие PDF в **заданный файл** (не в публичную папку), для превью:
```kotlin
fun generateTo(context, entry, renter, scooter, content, targetFile: File): Uri?
fun generateUnlimitedTo(context, renter, scooter, content, targetFile: File): Uri?
```
Это позволяет превью сохранять в `cacheDir`, а скачивание — в `Documents/ScooterContracts/`.

### 3.8. UI-экран `DocumentManagementScreen`

**Новый файл:** `app/src/main/java/com/example/DocumentManagementScreen.kt`

Структура (всё на одном скроллируемом экране):

```
┌─────────────────────────────────────────┐
│ [TopAppBar от MainActivity — содержит    │
│  кнопки + ✎ 🗑 🔍 и ★ вместо SMS]        │
├─────────────────────────────────────────┤
│  DocumentManagementScreen                │
│  ┌──────────────────────────────────┐  │
│  │ Выбор типа (TabRow или Segmented):│  │
│  │  [ Бесконечная ] [ Конечная ]     │  │
│  └──────────────────────────────────┘  │
│  ┌──────────────────────────────────┐  │
│  │ Список версий (LazyColumn):       │  │
│  │  ┌────────────────────────────┐  │  │
│  │  │ ★ Базовый шаблон (active)  │  │  │  ← золотая рамка + ★
│  │  │ Создан 25.09.2026           │  │  │
│  │  └────────────────────────────┘  │  │
│  │  ┌────────────────────────────┐  │  │
│  │  │   Версия 2                 │  │  │  ← обычная карта
│  │  │   Создан 26.09.2026         │  │  │
│  │  └────────────────────────────┘  │  │
│  └──────────────────────────────────┘  │
│  ┌──────────────────────────────────┐  │
│  │ Демо-клиент (dropdown из renters):│  │
│  │  [▼ Асилбеков Шерзод ...]        │  │
│  └──────────────────────────────────┘  │
│  ┌──────────────────────────────────┐  │
│  │ Превью PDF:                       │  │
│  │  ┌────────────────────────────┐  │  │
│  │  │  [Bitmap page 1]            │  │  │
│  │  │  [Bitmap page 2]            │  │  │  ← LazyColumn с PdfRenderer
│  │  │  ...                        │  │  │
│  │  └────────────────────────────┘  │  │
│  └──────────────────────────────────┘  │
└─────────────────────────────────────────┘
```

Sketch:
```kotlin
@Composable
fun DocumentManagementScreen(
    viewModel: ContractTemplateViewModel = viewModel(),
    renterViewModel: RenterViewModel = viewModel(),
    scooterViewModel: ScooterViewModel = viewModel(),
    createTrigger: Int = 0,
    editTrigger: Int = 0,
    deleteTrigger: Int = 0,
    searchTrigger: Int = 0,
    starTrigger: Int = 0,             // короткое нажатие ★
    starLongTrigger: Int = 0,         // долгое нажатие ★ (если делать через триггер)
    onBack: () -> Unit = {}
) {
    val selectedType by viewModel.selectedType.collectAsStateWithLifecycle()
    val templates by viewModel.templates.collectAsStateWithLifecycle()
    val selectedTemplateId by viewModel.selectedTemplateId.collectAsStateWithLifecycle()
    val renters by renterViewModel.rentersList.collectAsStateWithLifecycle()
    val selectedRenterId by viewModel.selectedRenterId.collectAsStateWithLifecycle()
    val previewBitmaps by viewModel.previewBitmaps.collectAsStateWithLifecycle()
    val isPreviewLoading by viewModel.isPreviewLoading.collectAsStateWithLifecycle()

    // Реакция на триггеры из TopAppBar (по образцу ContractListScreen:131-181)
    var lastCreate by remember { mutableStateOf(createTrigger) }
    var lastEdit by remember { mutableStateOf(editTrigger) }
    var lastDelete by remember { mutableStateOf(deleteTrigger) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showSearchPanel by remember { mutableStateOf(false) }

    LaunchedEffect(createTrigger) { if (createTrigger > lastCreate) showCreateDialog = true; lastCreate = createTrigger }
    LaunchedEffect(editTrigger)   { if (editTrigger > lastEdit && selectedTemplateId != null) showEditDialog = true; lastEdit = editTrigger }
    LaunchedEffect(deleteTrigger) { if (deleteTrigger > lastDelete && selectedTemplateId != null) showDeleteConfirm = true; lastDelete = deleteTrigger }
    LaunchedEffect(searchTrigger) { if (searchTrigger > lastSearch) showSearchPanel = !showSearchPanel; lastSearch = searchTrigger }

    // Регенерировать превью при изменении выбора
    LaunchedEffect(selectedTemplateId, selectedRenterId, selectedType) {
        viewModel.regeneratePreview()
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 1. Выбор типа
        item { TypeSelector(selectedType, onSelect = viewModel::selectType) }

        // 2. Список версий (только для текущего типа)
        item {
            Text("Версии шаблонов (${templates.size})", style = MaterialTheme.typography.titleMedium)
        }
        items(templates, key = { it.id }) { template ->
            VersionCard(
                template = template,
                isSelected = template.id == selectedTemplateId,
                onClick = { viewModel.selectTemplate(template.id) }
            )
        }

        // 3. Выбор клиента
        item {
            RenterSelector(
                renters = renters,
                selectedId = selectedRenterId,
                onSelect = viewModel::selectRenter
            )
        }

        // 4. Превью PDF
        item { Text("Превью PDF", style = MaterialTheme.typography.titleMedium) }
        if (isPreviewLoading) {
            item { CircularProgressIndicator(modifier = Modifier.padding(32.dp)) }
        } else {
            items(previewBitmaps.size) { i ->
                Image(
                    bitmap = previewBitmaps[i].asImageBitmap(),
                    contentDescription = "Page ${i+1}",
                    modifier = Modifier.fillMaxWidth().aspectRatio(595f / 842f)
                )
            }
        }
    }

    // Диалоги
    if (showCreateDialog) CreateTemplateDialog(viewModel = viewModel, onDismiss = { showCreateDialog = false })
    if (showEditDialog) EditTemplateDialog(viewModel = viewModel, templateId = selectedTemplateId!!, onDismiss = { showEditDialog = false })
    if (showDeleteConfirm) DeleteConfirmDialog(templateId = selectedTemplateId!!, onConfirm = { viewModel.deleteTemplate(it); showDeleteConfirm = false }, onDismiss = { showDeleteConfirm = false })
    if (showSearchPanel) SearchPanel(onQuery = viewModel::setSearchQuery)
}

@Composable
private fun VersionCard(template: ContractTemplate, isSelected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .border(
                width = if (template.isActive) 2.dp else 1.dp,
                color = if (template.isActive) ClaudeGold else ClaudeBorder,
                shape = RoundedCornerShape(8.dp)
            ),
        colors = CardDefaults.cardColors(containerColor = if (isSelected) ClaudeAccentBg else ClaudeCard)
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (template.isActive) {
                Icon(Icons.Default.Star, contentDescription = "Активная", tint = ClaudeGold, modifier = Modifier.size(24.dp))
            }
            Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                Text(template.name, style = MaterialTheme.typography.bodyMedium, color = ClaudeText)
                Text("Создан: ${SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(template.createdAt))}", style = MaterialTheme.typography.bodySmall, color = ClaudeTextSecondary)
            }
        }
    }
}
```

### 3.9. Навигация — интеграция в `MainActivity`

**Изменяемый файл:** `app/src/main/java/com/example/MainActivity.kt`

Изменения:

#### 3.9.1. Добавить 8-ю вкладку в нижней навигации (строки 2073)

```kotlin
// Добавить после NavTabButton(... Sozlamalar) на строке 2120:
NavTabButton(
    isSelected = currentTab == 7,
    onClick = { currentTab = 7 },
    accent = ClaudeGold,
    icon = Icons.Default.PictureAsPdf,
    contentDescription = "Dokumentoborot"
)
```

#### 3.9.2. Обернуть SMS-кнопку в `if (currentTab != 7)`

В `Scaffold.topBar.actions { ... }` (строки 1431–1550):
- Существующий `Box { Icon(Icons.Default.Sms, ...) }` обернуть в `if (currentTab != 7) { ... }`.
- Добавить рядом `if (currentTab == 7) { /* StarButton */ }`.

#### 3.9.3. Добавить кнопку ★

```kotlin
if (currentTab == 7) {
    val selectedTemplateId by docTemplateViewModel.selectedTemplateId.collectAsStateWithLifecycle()
    val activeTemplateId by docTemplateViewModel.activeTemplateIdForType.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .padding(end = 6.dp)
            .size(56.dp)
            .background(
                if (selectedTemplateId == activeTemplateId) ClaudeGold else ClaudeAccentBg,
                RoundedCornerShape(8.dp)
            )
            .border(1.dp, ClaudeGold, RoundedCornerShape(8.dp))
            .combinedClickable(
                onClick = {
                    val id = selectedTemplateId
                    if (id != null) {
                        docTemplateViewModel.setActive(id)
                        Toast.makeText(localContext, "Шаблон назначен активным", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(localContext, "Сначала выберите версию", Toast.LENGTH_SHORT).show()
                    }
                },
                onLongClick = {
                    val id = selectedTemplateId
                    if (id != null) {
                        coroutineScope.launch {
                            val uri = docTemplateViewModel.downloadSelectedWithDemoData()
                            if (uri != null) {
                                // Сохранить в Downloads и открыть через Intent.ACTION_VIEW
                                val shareIntent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, "application/pdf")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                localContext.startActivity(Intent.createChooser(shareIntent, "PDFni ko'rish"))
                                Toast.makeText(localContext, "PDF saqlandi: Documents/ScooterContracts/", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(localContext, "PDF yaratib bo'lmadi", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        Toast.makeText(localContext, "Сначала выберите версию и клиента", Toast.LENGTH_SHORT).show()
                    }
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Default.Star,
            contentDescription = "Сделать активным / Скачать PDF",
            tint = if (selectedTemplateId == activeTemplateId) Color.White else ClaudeGold,
            modifier = Modifier.size(28.dp)
        )
    }
}
```

#### 3.9.4. Триггеры для кнопок `+ / ✎ / 🗑` (строки 1586, 1667, 1762)

Добавить `var docCreateTrigger`, `docEditTrigger`, `docDeleteTrigger`, `docSearchTrigger` (по образцу `contractCreateTrigger`). В существующих кейсах `when (currentTab)` добавить `7 -> docCreateTrigger++` и т.д.

#### 3.9.5. Рендеринг вкладки 7 в `when (currentTab)` (строки 2175+)

```kotlin
} else if (currentTab == 7) {
    DocumentManagementScreen(
        createTrigger = docCreateTrigger,
        editTrigger = docEditTrigger,
        deleteTrigger = docDeleteTrigger,
        searchTrigger = docSearchTrigger
        // starTrigger не нужен — кнопка ★ напрямую вызывает методы viewModel
    )
}
```

---

## 4. Пошаговый план реализации

### Этап 1 — Data Layer (без UI, тестируется отдельно)
1. Добавить `kotlinx.serialization` плагин и зависимость в `build.gradle.kts` + `libs.versions.toml`.
2. Создать `data/ContractTemplate.kt` (Entity + `TemplateContent` data class + companion-дефолты).
3. Создать `data/ContractTemplateDao.kt`.
4. В `AppDatabase.kt`: добавить сущность в `@Database(...)`, бампить `version = 37`, добавить `MIGRATION_36_37` (CREATE TABLE + seed двух записей), добавить в `addMigrations(...)` список.
5. Создать `data/ContractTemplateRepository.kt`.

### Этап 2 — PdfContractGenerator extension (низкий риск)
6. Добавить в `ui/PdfContractGenerator.kt` два новых метода:
   - `fun generateTo(context, entry, renter, scooter, content: TemplateContent, targetFile: File): Uri?`
   - `fun generateUnlimitedTo(context, renter, scooter, content: TemplateContent, targetFile: File): Uri?`
7. Старые `generate()` / `generateUnlimited()` переиспользовать: читают активную версию из БД, делегируют в `*To()` методы с `content` или `TemplateContent()` дефолт.
8. Реализовать замену плейсхолдеров в тексте шаблона (простой `.replace("${tenantName}", tenantName)`).

### Этап 3 — ViewModel (низкий риск)
9. Создать `ui/ContractTemplateViewModel.kt` со всеми StateFlow, методами CRUD, `setActive`, `regeneratePreview`, `downloadSelectedWithDemoData`, `renderPdfToBitmaps`.

### Этап 4 — UI экрана (средний риск)
10. Создать `DocumentManagementScreen.kt`.
11. Реализовать подкомпоненты: `TypeSelector`, `VersionCard`, `RenterSelector`, превью-секция.
12. Реализовать 4 диалога: `CreateTemplateDialog`, `EditTemplateDialog`, `DeleteConfirmDialog`, `SearchPanel`.

### Этап 5 — Интеграция в MainActivity (средний риск)
13. Добавить `docCreateTrigger`, `docEditTrigger`, `docDeleteTrigger`, `docSearchTrigger` state vars.
14. В `Scaffold.topBar.actions`: обернуть SMS-блок в `if (currentTab != 7) { … }`.
15. Добавить star-блок `if (currentTab == 7) { … }` с `combinedClickable`.
16. В кейсах `+ / ✎ / 🗑` кнопок в TopAppBar добавить `7 -> docCreateTrigger++` (и т.д.).
17. В нижней навигации добавить 8-й `NavTabButton` (icon = PictureAsPdf, accent = ClaudeGold, contentDescription = "Dokumentoborot").
18. В `when (currentTab)` контента (строка ~2175) добавить `else if (currentTab == 7) { DocumentManagementScreen(...) }`.

### Этап 6 — Тестирование
19. Собрать APK, запустить на эмуляторе/устройстве.
20. Войти на вкладку 8 «Dokumentoborot». Должны быть видны 2 типа, по 1 версии в каждом («Базовый шаблон», isActive=true).
21. Нажать на версию → выбрать клиента → крутим вниз → видим превью PDF.
22. Нажать `+` → создать новую версию с изменённым текстом → сохранить → она появляется в списке.
23. Выбрать новую версию → нажать ★ → должна стать активной (золотая рамка + ★). Существующие PDF-кнопки в карточке арендатора теперь используют этот шаблон.
24. Долгий клик на ★ → открывается PDF во внешнем приложении, файл сохранён в Documents/ScooterContracts/.
25. Нажать 🗑 → версия переезжает в корзину. Вернуть через Trash mode (если он есть для шаблонов — добавить по аналогии с другими сущностями).
26. Открыть карточку реального арендатора → нажать кнопку «бессрочный PDF» → проверить, что PDF сгенерирован с активной версией шаблона.
27. Сбросить через Trash mode обратно → активной должна стать предыдущая (нужно добавить логику авто-переключения на следующую активную, если текущая удалена).

---

## 5. Файлы — итоговый список изменений

| Действие | Файл | Объём |
|---|---|---|
| **СОЗДАТЬ** | `app/src/main/java/com/example/data/ContractTemplate.kt` | ~80 строк (entity + TemplateContent + companion) |
| **СОЗДАТЬ** | `app/src/main/java/com/example/data/ContractTemplateDao.kt` | ~60 строк |
| **СОЗДАТЬ** | `app/src/main/java/com/example/data/ContractTemplateRepository.kt` | ~80 строк |
| **СОЗДАТЬ** | `app/src/main/java/com/example/ui/ContractTemplateViewModel.kt` | ~250 строк |
| **СОЗДАТЬ** | `app/src/main/java/com/example/DocumentManagementScreen.kt` | ~400 строк (экран + подкомпоненты + 4 диалога) |
| **ИЗМЕНИТЬ** | `app/src/main/java/com/example/data/AppDatabase.kt` | +1 сущность в @Database, version 36→37, +MIGRATION_36_37 (~40 строк), +`contractTemplateDao()` abstract |
| **ИЗМЕНИТЬ** | `app/src/main/java/com/example/ui/PdfContractGenerator.kt` | +2 новых метода `*To()`, рефакторинг существующих на чтение из БД (≈+60 строк) |
| **ИЗМЕНИТЬ** | `app/src/main/java/com/example/MainActivity.kt` | 8-я вкладка +4 строки, star-блок в TopAppBar ~50 строк, 4 триггера +4 строки, ветка в when +3 строки |
| **ИЗМЕНИТЬ** | `app/build.gradle.kts` | +1 плагин, +1 зависимость (kotlinx-serialization-json) |
| **ИЗМЕНИТЬ** | `gradle/libs.versions.toml` | +1 версия +1 alias для kotlinx-serialization |

**Не трогаем:**
- `AndroidManifest.xml` — `FileProvider` уже настроен.
- `res/xml/file_paths.xml` — `cache-path` и `external-path` уже есть.
- `strings.xml` — UI-строки останутся в коде (как везде в проекте).
- Существующие вызовы PDF-генератора из `ContractHistoryViewModel` и `ContractHistoryScreens` — они работают как прежде, только внутри генератора появится чтение активной версии из БД.

---

## 6. Риски и компромиссы

### 6.1. Риск: `fallbackToDestructiveMigration(true)` при ошибке миграции 36→37
Любая ошибка в SQL-запросе `MIGRATION_36_37` приведёт к потере ВСЕХ данных приложения (renters, contracts, transactions, scooters, virtual cards). Это критично для работающего приложения аренды.

**Митигация:**
- Тщательно протестировать SQL-запрос на чистой SQLite (через `sqlite3` в терминале).
- Написать `androidTest` с Robolectric, который открывает БД v36, накатывает миграцию, проверяет что данные renters/contracts остались + таблица `contract_templates` создалась с 2 seeded-записями.
- Бэкап перед обновлением: пользователь в `BackupManager` может выгрузить .xlsx — это сохранит данные. Но лучше не полагаться.

### 6.2. Риск: PdfRenderer — медленный рендер больших PDF
A4 PDF может иметь несколько страниц. Рендер каждой страницы в Bitmap ~50–100 ms. Для 1–2 страниц — мгновенно. Если кто-то сделает шаблон на 5 страниц — будет ~500 ms, нужно показать `CircularProgressIndicator`.

**Митигация:** отображать `isPreviewLoading` индикатор. Рендерить в `Dispatchers.IO`. Кешировать результат — пересоздавать только при изменении `selectedTemplateId` / `selectedRenterId` / `selectedType`.

### 6.3. Риск: чтение активного шаблона в `generate()` становится асинхронным
Сейчас `generate()` синхронный (`fun generate(...): Uri?`). Если добавить чтение из БД через `runBlocking { ... }` — заблокирует UI поток на ~5 ms. Это безопасно, но некрасиво.

**Митигация:** использовать `runBlocking { db.contractTemplateDao().getActiveForType(type) }` — Room-запрос с PRIMARY KEY / индексом на type+isActive отрабатывает за <5 ms. Если критично — переписать `generate`/`generateUnlimited` в suspend, а во всех вызовах уже есть `withContext(Dispatchers.IO)` (см. `ContractHistoryViewModel.generateContractPdf`).

### 6.4. Риск: пользователь удаляет активную версию
Если активную версию удаляют в корзину — что станет активной? Ничего. Тогда `generate()` использует дефолтный шаблон (хардкод). Это неожиданно: пользователь ожидает, что есть активная.

**Митигация:** в `repo.delete(id)` проверить, была ли она активной. Если да — назначить активной самую свежую оставшуюся версию этого типа (или seed-версию, если она осталась). Если версий не осталось — восстановить seed «Базовый шаблон».

### 6.5. Компромисс:PdfRenderer vs внешняя библиотека
`android.graphics.pdf.PdfRenderer` — встроенный в Android SDK. Не требует новых зависимостей, +0 KB к APK. Альтернатива — `com.github.barteksc:android-pdf-viewer:3.2.0-beta.1` (+2-3 MB). **Выбираю PdfRenderer** — он удовлетворяет требованию «окно превью» без overhead.

### 6.6. Компромисс: kotlinx.serialization vs org.json
`kotlinx.serialization` — чистый, типобезопасный, автоматически генерирует сериализатор. Но +1 плагин и +1 зависимость (~50 KB). `org.json` встроен в Android, но ручной парсинг — больше кода, проще ошибиться. **Выбираю kotlinx.serialization** — стандарт де-факто для Kotlin проектов, легко тестировать.

### 6.7. Риск: визуальная согласованность 8-й вкладки
На маленьких экранах 8 кнопок могут стать слишком узкими. Текущий `Row` с `Arrangement.SpaceEvenly` уже сжимает 7 кнопок. Проверить на 320dp-эмуляторе — если <40dp на кнопку, обернуть Row в `horizontalScroll(rememberScrollState())`. **Скорее всего обернуть не придётся** — `NavTabButton` использует `weight(1f)`, 8 кнопок вмес­тятся.

---

## 7. Открытые вопросы для подтверждения пользователем

Прошу подтвердить следующие решения (или предложить альтернативы):

### Вопрос 1: Иконка и название 8-й вкладки
- **(рекомендую)** A) Иконка `Icons.Default.PictureAsPdf`, accent = `ClaudeGold`, contentDescription = "Dokumentoborot" (в стиле узбекского, как у других вкладок).
- B) Иконка `Icons.Default.Description`, accent = `ClaudeAccent`.
- C) Иконка `Icons.Default.Folder`, accent = `ClaudeTeal`.

### Вопрос 2: Язык интерфейса вкладки
- A) Узбекский кириллицей (как у других вкладок: «Hujjatlar aylanishi»).
- **(рекомендую)** B) Русский («Документооборот») — пользователь общается по-русски, и в приложении уже есть русские элементы (настройки, отчёты).
- C) Узбекский латиницей (Hujjatlar).

### Вопрос 3: Звезда — цветовая индикация активного
Когда выбранная версия ≠ активная — звезда будет:
- **(рекомендую)** A) Иконка ★ в акцентном цвете (ClaudeGold outline), фон — ClaudeAccentBg (как outline). После клика → фон становится заливным ClaudeGold.
- B) Звезда серая если не активна, золотая если активна.

### Вопрос 4: Что делать при удалении активной версии?
- **(рекомендую)** A) Автоматически назначить активной самую свежую оставшуюся версию этого типа. Если версий не осталось — восстановить «Базовый шаблон» (seed).
- B) Не позволять удалить активную версию (Toast «Сначала сделайте активной другую»).
- C) Активной становится seed «Базовый шаблон», а пользовательские версии все удаляются.

### Вопрос 5: Превью — сколько страниц рендерить?
PDF на A4 в одну страницу влезает обычно (авто-подбор шрифта). Но если пользователь сделал шаблон на 3 страницы — превью должен показать все 3?
- **(рекомендую)** A) Все страницы, в `LazyColumn` с подсказкой «Страница X из Y».
- B) Только первая страница + кнопка «Открыть в PDF-вьюере».

### Вопрос 6: Демо-клиент — обязательный выбор?
- **(рекомендую)** A) По умолчанию выбран первый клиент из `renters`. Если renters пусто — превью не генерируется, показывается сообщение «Добавьте хотя бы одного клиента для превью».
- B) Если клиент не выбран — генерируется с фейковым `Renter(name="Тестов Тест", …)`.

### Вопрос 7: Скачивание PDF — куда сохранять?
- **(рекомендую)** A) В публичную папку `Documents/ScooterContracts/` (как сейчас делает `PdfContractGenerator`). Имя файла `template_preview_<type>_<version>_<timestamp>.pdf`. Файл остаётся после выхода из приложения.
- B) В `cacheDir` (временный, открывается через Intent.ACTION_VIEW, но не сохраняется).

### Вопрос 8: Звезда в TopAppBar или на самой странице?
- **(рекомендую)** A) Звезда в TopAppBar заменяет SMS (как вы и просили). Универсальные `+ / ✎ / 🗑 / 🔍` тоже в TopAppBar (как для других вкладок). На самой странице нет доп. кнопок.
- B) Звезда внизу самой страницы (как обычная кнопка), TopAppBar не меняется. Тогда при входе на вкладку SMS остаётся.
- C) И в TopAppBar, и на странице — для удобства.

### Вопрос 9: Редактирование тела шаблона — одним большим текстом или с подсветкой плейсхолдеров?
- **(рекомендую)** A) Один многострочный `OutlinedTextField`, моноширинный шрифт, подсказка с таблицей доступных `${placeholders}` под полем.
- B) Visual editor — каждое `${placeholder}` кликабельное, открывает селектор сущности.

### Вопрос 10: Нужен ли кнопка «Duplicate» (дублировать версию)?
Часто при создании новой версии удобно скопировать существующую и подправить.
- **(рекомендую)** A) ДА — кнопка «Дублировать» в `EditTemplateDialog` (или через долгое нажатие на версию в списке). Создаёт копию с именем «<имя> (копия)», isActive=false.
- B) НЕТ — пользователь копирует текст руками.

---

## 8. Что я буду делать после вашего одобрения

1. Дождаться ответа на вопросы 1–10 (раздел 7).
2. Внести корректировки в план, сохранить финальную версию в `docs/PLAN_DOCUMENTOBOROT_FINAL.md`.
3. Начать реализацию по этапам 1–6 (раздел 4):
   - Этап 1 (Data Layer) — коммит «feat: add ContractTemplate entity + DAO + migration 36→37».
   - Этап 2 (PdfContractGenerator extension) — коммит «feat: add generateTo/generateUnlimitedTo + read active template from DB».
   - Этап 3 (ViewModel) — коммит «feat: add ContractTemplateViewModel with preview rendering».
   - Этап 4 (UI) — коммит «feat: add DocumentManagementScreen with CRUD + preview».
   - Этап 5 (MainActivity integration) — коммит «feat: add 8th tab Dokumentoborot + replace SMS with Star button».
4. На каждом этапе — тестирование (см. этап 6, шаги 19–27).
5. После завершения — собрать APK через GitHub Actions (см. `docs/GITHUB-ACTIONS-BUILD.md`).
6. Отчитаться о завершении и предложить сделать бэкап перед установкой (т.к. миграция 36→37 — необратимый шаг).

---

**Конец плана v2. Жду вашего одобрения или корректировок.**
