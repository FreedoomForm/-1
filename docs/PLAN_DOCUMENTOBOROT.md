# План: новая страница «Документооборот» в Android-приложении аренды скутеров

> **Статус:** ЧЕРНОВИК — ожидает одобрения пользователя перед началом реализации.
> **Дата составления:** 2026-09-25
> **Автор исследования:** AI-ассистент (Super Z)
> **Источник данных:** полный аудит репозитория `FreedoomForm/-1` после клонирования

---

## 1. Краткое резюме (TL;DR)

В приложении уже есть рабочий PDF-генератор договора аренды скутера — файл `app/src/main/java/com/example/ui/PdfContractGenerator.kt` (850 строк). Он вызывается из `ContractHistoryViewModel.generateContractPdf()` / `generateUnlimitedContractPdf()`. Сейчас договор генерируется по строго захардкоженному узбекскому тексту (~50 параграфов) с 8-ю константами реквизитов арендодателя. Открытие PDF идёт через `Intent.ACTION_VIEW` во внешнем приложении.

**Задача:** добавить новую страницу «Документооборот», где пользователь сможет:
1. Просматривать текущий шаблон PDF-договора (генерировать превью с тестовыми данными и открывать PDF).
2. Изменять текстовое наполнение шаблона — реквизиты арендодателя (ИП/ЯТТ, адрес, банк, счёт, МФО, ИНН, телефон, директор) и текст самого договора (с плейсхолдерами вида `${tenantName}`, `${weeklyAmount}`).

**Предлагаемое решение (без миграции БД, на SharedPreferences, по образцу существующего `SettingsRepository`):**
- Расширить `SettingsRepository` полями «шаблон договора» и «реквизиты арендодателя».
- Изменить `PdfContractGenerator` так, чтобы он брал текст параграфов из `SettingsRepository`, а не из захардкоженных строк.
- Создать новый Composable-экран `DocumentManagementScreen` с двумя секциями: «Реквизиты арендодателя» (8 полей) и «Текст договора» (многострочный редактор с подсказками по плейсхолдерам).
- Добавить кнопки «Превью PDF» (открывает образец договора) и «Сбросить к заводским настройкам» (возвращает шаблон по умолчанию).
- Зарегистрировать новый экран в `NavigationState` (MainActivity.kt) и добавить кнопку входа в TopAppBar по образцу Scanner.

Подробности — в разделах ниже.

---

## 2. Анализ текущего состояния репозитория

### 2.1. Стек технологий

Приложение использует Jetpack Compose (XML-разметки для экранов нет — только виджеты). Архитектура паттерна — MVVM с ручным DI (без Hilt/Dagger). ViewModel наследуют `AndroidViewModel(application)`. База данных — Room v36, 7 сущностей (Renter, Scooter, ContractHistoryEntry, Transaction, VirtualCard, CardTransaction, NotificationHistoryEntity). Настройки хранятся в `SharedPreferences` через `SettingsRepository` (НЕ DataStore). Сборка — AGP 9.1.1, Kotlin 2.2.10, Compose BOM 2024.09.00, minSdk 24, targetSdk 36.

Навигация реализована через два уровня: нижняя навигация (7 вкладок, `currentTab: Int` 0..6) и полноэкранные под-экраны через `sealed class NavigationState` (`MainView`, `RenterHistory`, `ScooterHistory`, `CardHistory`, `ContractTransactionHistory`, `Settings`, `Scanner`). Навигация Jetpack (`androidx.navigation.compose`) описана в `libs.versions.toml`, но НЕ подключена в `build.gradle.kts` — не используется. Jetpack Navigation Drawer отсутствует.

### 2.2. Существующий PDF-генератор (ядро задачи)

В репозитории есть **ДВА** одноимённых файла `PdfContractGenerator.kt`:

| Путь | Размер | Используется? |
|---|---|---|
| `app/src/main/java/com/example/data/PdfContractGenerator.kt` | 270 строк | ❌ МЁРТВЫЙ ДУБЛИКАТ — старая узбекско-латинская версия, не импортируется нигде. Можно удалить. |
| `app/src/main/java/com/example/ui/PdfContractGenerator.kt` | 850 строк | ✅ АКТИВНЫЙ — вызывается из `ContractHistoryViewModel`. |

Активный генератор объявлен как `object PdfContractGenerator` в пакете `com.example.ui`. Имеет два публичных метода:
- `fun generate(context, entry: ContractHistoryEntry, renter: Renter?, scooter: Scooter? = null): Uri?` — договор на конкретную неделю.
- `fun generateUnlimited(context, renter: Renter, scooter: Scooter? = null): Uri?` — бессрочный договор.

Рендеринг идёт через встроенный `android.graphics.pdf.PdfDocument` (стандартный Android SDK), формат A4 (595×842 pt), поля 40f, шрифт `Typeface.DEFAULT`. Авто-подбор размера шрифта (`pickBaseFontSize`, минимум 7f) — уменьшается на 0.5f пока текст не влезает в одну страницу A4. Сохранение — публичный каталог `Documents/ScooterContracts/`, имя файла `rental_contract_SRC-000014_<timestamp>.pdf`. Возвращает `content://` `Uri` через `androidx.core.content.FileProvider`.

### 2.3. Что захардкожено в `ui/PdfContractGenerator.kt`

**8 констант реквизитов арендодателя** (строки 86-94):
```kotlin
private const val LANDLORD_NAME = "ЯТТ «АСИЛБЕКОВ ШЕРЗОД УЛУГБЕКОВИЧ»"
private const val LANDLORD_ADDRESS = "Тошкент Шахри, Юнусобод тумани, Сайилгох кучаси, 17-уй"
private const val LANDLORD_BANK = "Тошкент Ш., «КАПИТАЛБАНК» АТ БАНКИНИНГ БОШ ОФИСИ"
private const val LANDLORD_ACCOUNT = "20218 000 9 04982540 001"
private const val LANDLORD_MFO = "01088"
private const val LANDLORD_INN = "32607780220041"
private const val LANDLORD_PHONE = "+998 77 777 10 00"
private const val LANDLORD_DIRECTOR = "Асилбеков Шерзод Улугбекович"
```

**Текст договора** — два метода `buildParagraphsForSize(baseSize: Float): List<Paragraph>`:
- строки 149-338 — для недельного договора (`generate`).
- строки 444-637 — для бессрочного договора (`generateUnlimited`).

Каждый метод — это `buildList { add(Paragraph("текст...")) ... }` из ~50 объектов `Paragraph`. В тексте используются Kotlin string templates (`${tenantName}`, `${weeklyAmount}`, `${contractNumber}`, `${LANDLORD_NAME}` и т.д.). Все переменные вычисляются из `entry` / `renter` / `scooter` (строки 104-134).

**Шесть разделов договора** (всё на узбекском кириллицей):
1. Заголовок + № договора + дата/город.
2. Преамбула — стороны (Ижарага берувчи / Ижарага олувчи).
3. Раздел 1 — предмет договора (модель скутера, аккумуляторы).
4. Раздел 2 — платежи (0 сум гарантия; 11 500 000 сум стоимость; dailyAmount; weeklyAmount; даты).
5. Раздел 3 — права/обязанности (13 пунктов + пункт о поломке аккума с `batteryDamagePrice`).
6. Раздел 4 — ответственность/споры.
7. Раздел 5 — общие условия.
8. Раздел 6 — реквизиты обеих сторон + строки подписей.
9. Топшириқ-қабул қилиш далолатномаси — приём-передача скутера.

### 2.4. Вызов из UI (контракт-история)

В `ContractHistoryViewModel.kt` (строки 833-871) есть `suspend fun generateContractPdf(contractId: Int): Uri?` и `generateUnlimitedContractPdf(renterId: Int): Uri?`. Обе запускают рендер через `withContext(Dispatchers.IO)` и возвращают `Uri?`.

В `ContractHistoryScreens.kt` (строки 194-231) есть готовый образец вызова: кнопка `IconButton` с иконкой `Icons.Default.PictureAsPdf`, по нажатию запускает корутину, вызывает `contractHistoryViewModel.generateUnlimitedContractPdf(renter.id)`, далее открывает PDF через `Intent.ACTION_VIEW` + `setDataAndType(uri, "application/pdf")` + `FLAG_GRANT_READ_URI_PERMISSION`. Внешний вьюер PDF (Google Drive, Adobe Reader и т.д.) — НЕ встроен.

### 2.5. Образец для подражания — SMS-шаблон

В `SettingsRepository.kt` уже есть почти идентичный паттерн: переменная `smsTemplate: String`, хранимая в SharedPreferences, с дефолтом `DEFAULT_TEMPLATE` (многострочный текст с плейсхолдерами `{name}`, `{days}`, `{unpaidDays}`, `{unpaidCount}`, `{debt}`, `{payme}`, `{call}`). Этот же подход идеально ложится на «шаблон договора»: вместо строковых констант в коде — `var contractTemplate: String` в `SettingsRepository` с дефолтом из `DEFAULT_CONTRACT_TEMPLATE`, и `var landlordName`, `landlordAddress`, `landlordBank`, `landlordAccount`, `landlordMfo`, `landlordInn`, `landlordPhone`, `landlordDirector` (8 свойств).

### 2.6. Манифест и FileProvider

`AndroidManifest.xml` уже содержит `<provider android:name="androidx.core.content.FileProvider" authority="${applicationId}.fileprovider"` (строки 75-84) с путями из `res/xml/file_paths.xml`. Публичный `Documents/ScooterContracts/` уже открыт для шеринг через `Intent`. **Для нового экрана в манифест добавлять ничего не нужно.**

### 2.7. Подключённые библиотеки

Из `app/build.gradle.kts` и `gradle/libs.versions.toml`:
- ✅ Compose BOM 2024.09.00, Material3, material-icons-extended 1.7.0.
- ✅ Room 2.7.0 (KSP).
- ✅ Coroutines 1.10.2.
- ✅ Lifecycle/Viewmodel-compose 2.8.7.
- ✅ Activity-Compose 1.10.1.
- ✅ DataStore Preferences 1.1.7 (но используется SharedPreferences).
- ✅ WorkManager 2.9.0, CameraX 1.3.4, OkHttp 4.12.0, FastExcel 0.18.4, Coil 2.7.0 (определён, не подключён).

Чего НЕТ:
- ❌ Hilt/Dagger.
- ❌ PDF-библиотеки (iTextPDF, Apache PDFBox, AndroidPdfViewer) — рендеринг идёт через `android.graphics.pdf.PdfDocument` из SDK.
- ❌ Jetpack Navigation Compose (описан в каталоге, но не подключён).

---

## 3. Постановка задачи

Пользователь хочет новую страницу «Документооборот», где:

1. **Просмотр PDF-шаблона договора аренды скутера** — пользователь видит, как выглядит текущий шаблон договора (с реквизитами арендодателя и текстом). Сейчас PDF открывается только из карточки арендатора (бессрочный) или из контракта (недельный) — нет единого места, где можно посмотреть «какой шаблон сейчас активен».

2. **Изменение шаблона PDF-договора** — пользователь может отредактировать:
   - Реквизиты арендодателя (название ЯТТ/ИП, адрес, банк, расчётный счёт, МФО, ИНН, телефон, ФИО директора).
   - Текст договора (с возможностью использовать плейсхолдеры для подстановки данных арендатора/скутера/дат).

Договор подписывается арендатором (арендатор = «Ижарага олувчи» / tenant) и арендодателем («Ижарага берувчи» / landlord). Цель — дать пользователю гибкость менять текст и реквизиты БЕЗ перекомпиляции APK.

---

## 4. Предлагаемая архитектура решения

### 4.1. Хранение шаблона — SharedPreferences (НЕ Room, НЕ DataStore)

**Решение:** расширить существующий `SettingsRepository` новыми полями. Не создавать новую таблицу Room, не добавлять миграцию БД.

**Обоснование:**
- `SettingsRepository` уже использует SharedPreferences с аналогичным паттерном (SMS-шаблон).
- SharedPreferences идеально подходит для редко меняющихся пользовательских настроек.
- Избегаем миграции БД (с `fallbackToDestructiveMigration(true)` любая ошибка в миграции сотрёт ВСЕ данные пользователя — критично для приложения аренды с долгами клиентов).
- Дефолтные значения (DEFAULT_*) сохраняются в companion object — при первом запуске пользователь видит текущий шаблон, как в коде.

**Новые поля в `SettingsRepository` (8 шт. для реквизитов + 2 для текста):**
```kotlin
var landlordName: String           // = "ЯТТ «АСИЛБЕКОВ ШЕРЗОД УЛУГБЕКОВИЧ»"
var landlordAddress: String         // = "Тошкент Шахри, Юнусобод тумани, Сайилгох кучаси, 17-уй"
var landlordBank: String            // = "Тошкент Ш., «КАПИТАЛБАНК» АТ БАНКИНИНГ БОШ ОФИСИ"
var landlordAccount: String         // = "20218 000 9 04982540 001"
var landlordMfo: String             // = "01088"
var landlordInn: String             // = "32607780220041"
var landlordPhone: String           // = "+998 77 777 10 00"
var landlordDirector: String        // = "Асилбеков Шерзод Улугбекович"

/** Полный текст договора для недельной аренды. Содержит ${placeholder}'ы. */
var contractTemplateWeekly: String  // = DEFAULT_CONTRACT_TEMPLATE_WEEKLY

/** Полный текст договора для бессрочной аренды. */
var contractTemplateUnlimited: String  // = DEFAULT_CONTRACT_TEMPLATE_UNLIMITED
```

Дефолты выносятся в `companion object` — это текущие захардкоженные строки из `ui/PdfContractGenerator.kt` (строки 149-338 для недельного, 444-637 для бессрочного). Ничего не сломается: при первом запуске пользователь увидит идентичный текущему PDF.

### 4.2. Плейсхолдеры шаблона

Текст шаблона — это просто строка (многострочный Kotlin raw string `"""..."""`) с плейсхолдерами в стиле `${name}`. Доступные плейсхолдеры (полный список из `ui/PdfContractGenerator.kt:104-134`):

| Плейсхолдер | Описание | Источник |
|---|---|---|
| `${contractNumber}` | № договора, напр. `SRC-000014` | `entry.id` |
| `${contractDate}` | Дата договора (узбекский формат) | `entry.timestamp` |
| `${weekStart}` | Дата начала недели | `entry.weekStart` или `renter.rentStartDateTimestamp` |
| `${weekEnd}` | Дата окончания недели | `entry.weekEnd` |
| `${tenantName}` | ФИО арендатора | `entry.renterName` / `renter.name` |
| `${tenantPhone}` | Телефон арендатора | `entry.renterPhone` / `renter.phoneNumber` |
| `${tenantAddress}` | Адрес арендатора | `entry.address` / `renter.address` |
| `${tenantPassport}` | Паспорт арендатора | `entry.passportData` / `renter.passportData` |
| `${tenantPinfl}` | ПИНФЛ арендатора | `entry.pinfl` / `renter.pinfl` |
| `${scooterName}` | Модель скутера | `entry.scooterName` / `renter.scooterName` |
| `${scooterVin}` | VIN скутера | `entry.vinNumber` / `scooter.vinNumber` |
| `${scooterEngine}` | Номер двигателя | `entry.engineNumber` / `scooter.engineNumber` |
| `${scooterSerial}` | Серийный номер скутера | `entry.scooterSerialNumber` |
| `${batteryId1}` | ID первого аккума | `entry.batteryId1` |
| `${batteryId2}` | ID второго аккума | `entry.batteryId2` |
| `${extraInfo}` | Доп. инфо о скутере | `entry.additionalInfo` |
| `${weeklyAmount}` | Сумма за неделю | `entry.weeklyPrice` |
| `${dailyAmount}` | Сумма за день | `weeklyAmount / 7.0` |
| `${batteryDamagePrice}` | Сумма поломки аккума | `SettingsRepository.batteryDamagePrice` |
| `${landlordName}` ... `${landlordDirector}` | 8 реквизитов арендодателя | из `SettingsRepository` |

**Реализация рендеринга:** в `PdfContractGenerator` оставить только логику рендера `Paragraph` (выравнивание, шрифт, отступы). Текст брать из `SettingsRepository`, заменять плейсхолдеры через простой `replace("${tenantName}", tenantName)`.

Альтернатива — вынести парсинг шаблона в `DocumentTemplateRenderer` объект, который принимает `(templateText, data)` и возвращает `List<Paragraph>`. Это чище, но усложнит код. Предлагаю сначала сделать простую замену через `replace()`, при необходимости — рефакторить позже.

### 4.3. Изменение `PdfContractGenerator.kt`

**Файл:** `app/src/main/java/com/example/ui/PdfContractGenerator.kt`

Изменения:
1. **Удалить** 8 `private const val LANDLORD_*` (строки 86-94) — теперь берутся из `SettingsRepository`.
2. **Удалить** два метода `buildParagraphsForSize` (строки 149-338 для недельного, 444-637 для бессрочного) — теперь берутся из `SettingsRepository.contractTemplateWeekly` / `contractTemplateUnlimited`.
3. **Добавить** `companion object` с дефолтами `DEFAULT_CONTRACT_TEMPLATE_WEEKLY` и `DEFAULT_CONTRACT_TEMPLATE_UNLIMITED` — это текущие строки-константы, перемещённые без изменений. Сохраняют обратную совместимость (первый запуск = идентичный PDF).
4. **В `generate()` / `generateUnlimited()`:** в начале функции читать `SettingsRepository(context)` и получать `landlordName`, `landlordAddress`, ..., `contractTemplateWeekly` / `contractTemplateUnlimited`. Делать замену плейсхолдеров. Результат передавать в существующую логику рендера `Paragraph`-ов.
5. **Не трогать** `pickBaseFontSize`, рендер, сохранение, FileProvider — это рабочая логика.

**Объём изменений:** ~500 строк удаления (захардкоженные тексты) + ~50 строк добавления (чтение из SettingsRepository + замена плейсхолдеров). Файл уменьшится с 850 до ~400 строк.

### 4.4. Новый ViewModel — `DocumentTemplateViewModel`

**Новый файл:** `app/src/main/java/com/example/ui/DocumentTemplateViewModel.kt`

```kotlin
class DocumentTemplateViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = SettingsRepository(application)

    // StateFlow'ы для UI (8 реквизитов + 2 шаблона)
    val landlordName: StateFlow<String>
    val landlordAddress: StateFlow<String>
    // ... 6 ещё
    val contractTemplateWeekly: StateFlow<String>
    val contractTemplateUnlimited: StateFlow<String>

    // Для превью PDF
    private val _previewUri = MutableStateFlow<Uri?>(null)
    val previewUri: StateFlow<Uri?> = _previewUri

    init {
        // Инициализация StateFlow из SettingsRepository
        // (поскольку SharedPreferences не Flow — оборачиваем в MutableStateFlow)
    }

    fun updateLandlordName(value: String) { ... }
    // ... 7 ещё методов update*

    fun updateContractTemplateWeekly(value: String) { ... }
    fun updateContractTemplateUnlimited(value: String) { ... }

    fun resetToDefaults() {
        // Возвращает все 10 полей к DEFAULT_* значениям
    }

    suspend fun generatePreviewPdf(): Uri? = withContext(Dispatchers.IO) {
        // Создаёт тестовый ContractHistoryEntry / Renter / Scooter с демо-данными
        // и вызывает PdfContractGenerator.generate()
    }
}
```

Архитектура аналогична `SettingsViewModel` (тоже `AndroidViewModel`, тоже обёртка над `SettingsRepository`).

### 4.5. Новый Composable-экран — `DocumentManagementScreen`

**Новый файл:** `app/src/main/java/com/example/DocumentManagementScreen.kt`

UI Composition (см. набросок ниже):
1. **TopAppBar** с кнопкой «Назад» и заголовком «Документооборот».
2. **Две вкладки (TabRow):**
   - **Вкладка 1: «Реквизиты арендодателя»** — форма из 8 текстовых полей (`OutlinedTextField`): название ЯТТ/ИП, адрес, банк, расчётный счёт, МФО, ИНН, телефон, ФИО директора. Сохранение — по мере ввода (debounce 500ms) или по кнопке «Сохранить».
   - **Вкладка 2: «Текст договора»** — ещё подвкладка `Weekly` / `Unlimited` (два текста). Многострочный редактор (`OutlinedTextField` с `maxLines = 20`, `singleLine = false`, моноширинный шрифт) + раскрывающийся блок «Доступные плейсхолдеры» (таблица из 20+ переменных с описанием).
3. **Нижняя панель действий:**
   - Кнопка «Превью PDF» — генерирует образец договора (демо-данные) и открывает PDF через `Intent.ACTION_VIEW`.
   - Кнопка «Сбросить к заводским настройкам» — диалог подтверждения, возвращает все 10 полей к DEFAULT_*.
   - Кнопка «Сохранить» (если не auto-save).

Набросок Composable:
```kotlin
@Composable
fun DocumentManagementScreen(
    viewModel: DocumentTemplateViewModel = viewModel(),
    onBack: () -> Unit
) {
    val landlordName by viewModel.landlordName.collectAsStateWithLifecycle()
    // ... 9 ещё state'ов
    val previewUri by viewModel.previewUri.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var generatingPreview by remember { mutableStateOf(false) }
    var activeTab by remember { mutableStateOf(0) }  // 0 = реквизиты, 1 = текст
    var activeContractTab by remember { mutableStateOf(0) }  // 0 = weekly, 1 = unlimited

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Документооборот") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "...") } }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp).fillMaxSize()) {
            TabRow(selectedTabIndex = activeTab) {
                Tab(selected = activeTab == 0, onClick = { activeTab = 0 }, text = { Text("Реквизиты") })
                Tab(selected = activeTab == 1, onClick = { activeTab = 1 }, text = { Text("Текст договора") })
            }
            when (activeTab) {
                0 -> LandlordRequisitesForm(viewModel = viewModel, /* ... */)
                1 -> ContractTemplateEditor(
                    activeContractTab = activeContractTab,
                    onTabChange = { activeContractTab = it },
                    weeklyTemplate = contractTemplateWeekly,
                    unlimitedTemplate = contractTemplateUnlimited,
                    onWeeklyChange = viewModel::updateContractTemplateWeekly,
                    onUnlimitedChange = viewModel::updateContractTemplateUnlimited
                )
            }
            // Нижняя панель с кнопками Preview / Reset
            BottomActionBar(
                onPreview = {
                    generatingPreview = true
                    scope.launch {
                        val uri = viewModel.generatePreviewPdf()
                        generatingPreview = false
                        uri?.let { openPdf(context, it) }
                    }
                },
                onReset = { viewModel.resetToDefaults() },
                generatingPreview = generatingPreview
            )
        }
    }
}
```

### 4.6. Навигация — регистрация в `MainActivity.kt`

**Изменяемый файл:** `app/src/main/java/com/example/MainActivity.kt`

Изменения:

1. **Расширить `NavigationState`** (строка 366):
```kotlin
sealed class NavigationState {
    data object MainView : NavigationState()
    data class RenterHistory(val renter: Renter) : NavigationState()
    data class ScooterHistory(val scooter: Scooter) : NavigationState()
    data class CardHistory(val card: com.example.data.VirtualCard) : NavigationState()
    data class ContractTransactionHistory(val contract: com.example.data.ContractHistoryEntry) : NavigationState()
    data object Settings : NavigationState()
    data object Scanner : NavigationState()
    data object DocumentManagement : NavigationState()   // ← НОВЫЙ ЭКРАН
}
```

2. **Добавить ветку в `when (val st = navState)`** (после `NavigationState.Scanner ->` на строке ~1346):
```kotlin
NavigationState.DocumentManagement -> {
    DocumentManagementScreen(
        onBack = { navState = NavigationState.MainView }
    )
    return
}
```

3. **Добавить кнопку входа в TopAppBar** рядом с кнопкой Scanner (строки 1397-1411). Образец:
```kotlin
IconButton(
    onClick = { navState = NavigationState.DocumentManagement },
    modifier = Modifier.padding(end = 6.dp).size(56.dp)
        .background(ClaudeAccentBg, RoundedCornerShape(8.dp))
        .border(1.dp, ClaudeAccent, RoundedCornerShape(8.dp))
) {
    Icon(
        Icons.Default.Description,  // или Icons.AutoMirrored.Filled.Article
        contentDescription = "Документооборот",
        tint = ClaudeAccent,
        modifier = Modifier.size(28.dp)
    )
}
```

**Альтернатива:** добавить 8-ю вкладку в нижнюю навигацию (строки 2046-2123). Но тогда на маленьких экранах вкладки станут слишком узкими. Рекомендую кнопкой в TopAppBar — это редко используемая функция (пользователь один раз настроит шаблон и забудет).

### 4.7. Демо-данные для превью PDF

В `DocumentTemplateViewModel.generatePreviewPdf()` нужно создать тестовый `ContractHistoryEntry` / `Renter` / `Scooter` (НЕ сохранять в БД — просто объект) и передать в `PdfContractGenerator.generate()`:

```kotlin
val demoRenter = Renter(
    id = 0,
    name = "Тестов Тест Тестович",
    phoneNumber = "+998 90 123 45 67",
    passportData = "AA 1234567, выдан 01.01.2024 IIB Юнусобод",
    address = "г. Ташкент, Юнусабадский район, ул. Сайилгох, д. 1",
    pinfl = "12345678901234",
    scooterName = "Xiaomi M365 Pro 2",
    rentStartDateTimestamp = System.currentTimeMillis(),
    rentDurationDays = 7
)
val demoScooter = Scooter(
    id = 0,
    name = "Xiaomi M365 Pro 2",
    documentedNumber = "DOC-001",
    vinNumber = "VIN1234567890",
    engineNumber = "ENG001",
    scooterSerialNumber = "SN-2024-001",
    batteryId1 = "BAT-001",
    batteryId2 = "BAT-002",
    additionalInfo = "Чёрный, в хорошем состоянии"
)
val demoEntry = ContractHistoryEntry(
    id = 999,
    renterId = 0,
    timestamp = System.currentTimeMillis(),
    type = "CREATED",
    renterName = demoRenter.name,
    renterPhone = demoRenter.phoneNumber,
    scooterName = demoScooter.name,
    weekStart = System.currentTimeMillis(),
    weekEnd = System.currentTimeMillis() + 7L * 24 * 60 * 60 * 1000,
    weeklyPrice = 420_000.0,
    passportData = demoRenter.passportData,
    address = demoRenter.address,
    pinfl = demoRenter.pinfl,
    vinNumber = demoScooter.vinNumber,
    engineNumber = demoScooter.engineNumber,
    scooterSerialNumber = demoScooter.scooterSerialNumber,
    batteryId1 = demoScooter.batteryId1,
    batteryId2 = demoScooter.batteryId2,
    additionalInfo = demoScooter.additionalInfo
)
PdfContractGenerator.generate(getApplication(), demoEntry, demoRenter, demoScooter)
```

Готовый PDF открывается через тот же паттерн что и в `ContractHistoryScreens.kt:200-205`.

---

## 5. Пошаговый план реализации

### Этап 1 — Расширение SettingsRepository (низкий риск)
1. В `app/src/main/java/com/example/data/SettingsRepository.kt` добавить 8 свойств `var landlord*` (по образцу `smsTemplate`).
2. В `companion object` добавить 8 `const val DEFAULT_LANDLORD_*` со значениями из текущих `private const val LANDLORD_*` файла `ui/PdfContractGenerator.kt`.
3. Добавить `var contractTemplateWeekly: String` и `var contractTemplateUnlimited: String` + 2 `DEFAULT_CONTRACT_TEMPLATE_*` (текущие тексты из `buildParagraphsForSize`).

### Этап 2 — Рефакторинг PdfContractGenerator (средний риск)
4. В `ui/PdfContractGenerator.kt` удалить 8 `private const val LANDLORD_*` (строки 86-94).
5. В функциях `generate()` и `generateUnlimited()` в начале читать `SettingsRepository(context)` и получать все 10 значений.
6. Заменить `buildParagraphsForSize` (строки 149-338 и 444-637) на чтение шаблона + замену плейсхолдеров.
7. Оставить логику рендера Paragraph (выравнивание/шрифт/отступы) без изменений.
8. Удалить мёртвый дублькат `data/PdfContractGenerator.kt` (строго опционально — не критично для задачи).

### Этап 3 — ViewModel (низкий риск)
9. Создать файл `app/src/main/java/com/example/ui/DocumentTemplateViewModel.kt`.
10. Реализовать 10 StateFlow (оборачиваем SharedPreferences в MutableStateFlow), 10 методов `update*`, `resetToDefaults()`, `generatePreviewPdf()`.

### Этап 4 — UI экрана (средний риск)
11. Создать файл `app/src/main/java/com/example/DocumentManagementScreen.kt`.
12. Реализовать `LandlordRequisitesForm` (8 `OutlinedTextField`).
13. Реализовать `ContractTemplateEditor` (две подвкладки + многострочный редактор + блок «Доступные плейсхолдеры»).
14. Реализовать `BottomActionBar` (Preview / Reset / Save).
15. Реализовать `openPdf(context, uri)` — копия логики из `ContractHistoryScreens.kt:200-205`.

### Этап 5 — Навигация (низкий риск)
16. В `MainActivity.kt` расширить `NavigationState` (строка 366) — добавить `data object DocumentManagement`.
17. В ветке `when (val st = navState)` (строки 1038-1362) рядом со `Scanner` (строка ~1346) добавить ветку `NavigationState.DocumentManagement ->`.
18. В TopAppBar (строка 1397) рядом с кнопкой Scanner добавить новую кнопку с иконкой `Icons.Default.Description`.

### Этап 6 — Тестирование
19. Запустить приложение, открыть «Документооборот», проверить что форма показывает текущие значения (т.к. дефолты = текущие строки).
20. Нажать «Превью PDF» — должен сгенерироваться PDF с демо-данными и открыться во внешнем вьюере.
21. Изменить одно поле (например, `landlordPhone`) — нажать «Превью PDF» — в новом PDF должен быть новый телефон.
22. Открыть карточку реального арендатора, нажать кнопку «бессрочный PDF» — проверить, что изменения применились.
23. Нажать «Сбросить к заводским настройкам» — форма должна вернуться к исходным значениям.

---

## 6. Файлы — список изменений

| Действие | Файл | Объём изменений |
|---|---|---|
| **ИЗМЕНИТЬ** | `app/src/main/java/com/example/data/SettingsRepository.kt` | +10 свойств, +10 DEFAULT_* констант (≈+100 строк) |
| **ИЗМЕНИТЬ** | `app/src/main/java/com/example/ui/PdfContractGenerator.kt` | удалить ~500 строк захардкоженного текста, добавить ~50 строк чтения из SettingsRepository (итог: 850 → ~400 строк) |
| **СОЗДАТЬ** | `app/src/main/java/com/example/ui/DocumentTemplateViewModel.kt` | новый файл (~200 строк) |
| **СОЗДАТЬ** | `app/src/main/java/com/example/DocumentManagementScreen.kt` | новый файл (~400 строк) |
| **ИЗМЕНИТЬ** | `app/src/main/java/com/example/MainActivity.kt` | +1 строка в NavigationState, +5 строк в when-ветке, +10 строк в TopAppBar (≈+16 строк) |
| **ОПЦИОНАЛЬНО УДАЛИТЬ** | `app/src/main/java/com/example/data/PdfContractGenerator.kt` | мёртвый дубликат, не используется (строго по желанию пользователя) |

**Никаких изменений в:**
- `build.gradle.kts`, `libs.versions.toml` — все нужные библиотеки уже есть.
- `AndroidManifest.xml` — `FileProvider` уже настроен.
- `res/xml/file_paths.xml` — пути для PDF уже открыты.
- `ContractHistoryViewModel.kt` — использует `PdfContractGenerator` как прежде, изменится только его внутреннее поведение.
- База данных — нет миграций, нет новых таблиц.

---

## 7. Риски и компромиссы

### 7.1. Риск: пользователь сломает шаблон синтаксической ошибкой
Если пользователь удалит закрывающую скобку `${tenantName}` или сделает `${badPlaceholder}` — замен не произойдёт, в PDF попадёт текст как есть. Это не сломает приложение (просто будет «уродливый» PDF). Реализация должна:
- Делать замену безопасно (просто `.replace()`, не парсить).
- На кнопке «Сохранить» показывать предупреждение, если встречены неизвестные плейсхолдеры (опционально).
- Кнопка «Сбросить к заводским настройкам» всегда позволяет вернуться к рабочему дефолту.

### 7.2. Риск: PDF перестанет влезать на одну страницу A4
Сейчас работает авто-подбор шрифта (`pickBaseFontSize`, минимум 7f). Если пользователь добавит много текста, размер шрифта уменьшится до 7f — и если всё равно не влезает, текст обрежется. Это известное ограничение текущего PDF-генератора, не новая проблема. В UI стоит предупредить: «Слишком длинный текст может не поместиться на одну страницу A4. Минимальный размер шрифта — 7 pt.»

### 7.3. Компромисс: НЕ встраивать PDF-viewer в приложение
Текущий паттерн — PDF открывается во внешнем приложении (`Intent.ACTION_VIEW` + `application/pdf`). Это работает, не требует новой библиотеки. Альтернатива — встроить `com.github.barteksc:android-pdf-viewer:3.2.0-beta.1` (или подобную) для просмотра внутри приложения. Это +1 зависимость и +2-3 MB APK. Предлагаю НЕ делать — пользователь видит превью во внешнем вьюере, что привычно и удобно.

### 7.4. Компромисс: редактирование одним большим текстом vs. по параграфам
Два варианта:
- **A) Одним большим текстом** (предлагаю) — пользователь редактирует весь договор как один многострочный текст. Просто, но при ошибке придётся искать её по всему тексту.
- **B) По параграфам** — каждый параграф в отдельном поле, можно удалять/перетаскивать. Гибко, но усложняет UI и логику.

Вариант B даёт более «визуальный» редактор, но требует написания отдельного движка рендера шаблонов. Предлагаю начать с варианта A — если будет неудобно, итеративно перейти к B.

### 7.5. Риск: изменения реквизитов арендодателя повлияют на все будущие PDF
Все договоры, генерируемые после изменения, будут использовать новые реквизиты. Это **в ожидаемое поведение** — пользователь меняет шаблон именно для этого. Старые PDF (на диске) остаются без изменений — они уже сохранены как файлы.

### 7.6. Риск: backup .xlsx не включает настройки шаблона
`BackupManager` экспортирует только данные БД (renters, scooters, contracts, transactions). Настройки SharedPreferences, включая `smsTemplate` и новые `contractTemplate*`, в backup НЕ попадают. На текущий момент это справедливо и для SMS-шаблона. Если критично — добавить экспорт SharedPreferences в backup. Но это отдельная задача, не блокирующая основной.

---

## 8. Открытые вопросы для подтверждения пользователем

Прежде чем начать реализацию, прошу подтвердить следующие решения (или предложить альтернативы):

### Вопрос 1: Где разместить кнопку входа в «Документооборот»?
- **(рекомендую)** A) Кнопка с иконкой `Description` в TopAppBar рядом с кнопкой Scanner.
- B) 8-я вкладка в нижней навигации (между «Sozlamalar» и другими).
- C) Внутри экрана «Sozlamalar» (Settings) как новая секция.

### Вопрос 2: Два разных текста (недельный + бессрочный) или один?
Сейчас в коде есть два разных шаблона: недельный (`generate`) и бессрочный (`generateUnlimited`). Различаются только параграфом 2.5. Можно:
- **(рекомендую)** A) Оставить два разных редактируемых текста в двух подвкладках.
- B) Один текст с условным плейсхолдером `${durationClause}` — генератор подставляет нужный текст в зависимости от типа договора.

### Вопрос 3: Автосохранение или по кнопке?
- **(рекомендую)** A) Автосохранение каждого поля при потере фокуса (debounce 500ms).
- B) Сохранение по кнопке «Сохранить» внизу (как форма добавления арендатора).
- C) Гибрид: автосохранение для 8 реквизитов, ручное — для длинного текста (он большой, можно прервать редактирование случайно).

### Вопрос 4: Редактирование одним текстом или по параграфам?
- **(рекомендую)** A) Один большой многострочный текст (как SMS-шаблон).
- B) По параграфам — каждый параграф в отдельном поле с возможностью удаления/перемещения.

### Вопрос 5: Нужна ли встроенная библиотека PDF-вьюера?
- **(рекомендую)** A) НЕТ — оставить открытие PDF во внешнем приложении (`Intent.ACTION_VIEW`). Текущий паттерн работает.
- B) ДА — встроить `com.github.barteksc:android-pdf-viewer:3.2.0-beta.1` для предпросмотра внутри приложения (+2-3 MB APK).

### Вопрос 6: Удалять мёртвый дубликат `data/PdfContractGenerator.kt`?
В репозитории есть старый неиспользуемый файл `app/src/main/java/com/example/data/PdfContractGenerator.kt` (270 строк, узбекско-латинская версия). Он не вызывается из кода.
- **(рекомендую)** A) ДА — удалить для чистоты репозитория.
- B) НЕТ — оставить (возможно, планируете вернуть).

### Вопрос 7: Язык интерфейса страницы «Документооборот»?
Сейчас в приложении преобладает узбекский (Ijarachilar, Skuterlar, Kontraktlar и т.д.) с элементами русского (настройки, отчёты). Сам текст договора — на узбекской кириллицей.
- A) Узбекский (Hamkorlar / Shartnoma matni / Oldindan ko'rish / Standartga qaytarish).
- **(рекомендую)** B) Русский (Реквизиты арендодателя / Текст договора / Превью PDF / Сбросить к заводским настройкам) — пользователь общался со мной по-русски.
- C) Смесь: заголовки на русском, текст подсказок на узбекском.

### Вопрос 8: Нужны ли predefined «шаблоны по умолчанию» как пресеты?
Кроме текущего шаблона (ЯТТ «АСИЛБЕКОВ ШЕРЗОД УЛУГБЕКОВИЧ»), могут быть другие распространённые реквизиты (ИП, ООО, физлицо).
- **(рекомендую)** A) НЕТ — пользователь вводит свои реквизиты один раз, дальше они сохраняются.
- B) ДА — добавить пресеты «ИП», «ООО», «Физлицо» с разными наборами полей (например, для ООО добавить ОГРН).

---

## 9. Документация для пользователя

В дополнение к этому плану, после реализации будет полезно:
- Добавить короткое руководство в `README.md` репозитория (раздел «Документооборот») — как открыть страницу, как редактировать, какие плейсхолдеры доступны.
- В UI на экране «Документооборот» добавить блок «Помощь» (?) с таблицей плейсхолдеров (см. раздел 4.2).

---

## 10. Что я буду делать после вашего одобрения

1. Дождаться ответа на вопросы 1-8 (раздел 8).
2. Внести корректировки в план (если они есть).
3. Сохранить финальный план в `docs/PLAN_DOCUMENTOBOROT_FINAL.md` (с ответами).
4. Начать реализацию по этапам 1-6 (раздел 5).
5. На каждом этапе — коммитить изменения в репозиторий.
6. После завершения — провести ручное тестирование (этап 6, шаги 19-23).
7. Отчитаться о завершении и предложить собрать APK (через GitHub Actions — workflow уже настроен в `docs/GITHUB-ACTIONS-BUILD.md`).

---

**Конец плана. Жду вашего одобрения или корректировок.**
