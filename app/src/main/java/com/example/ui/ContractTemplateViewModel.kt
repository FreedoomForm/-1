package com.example.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.ContractHistoryEntry
import com.example.data.ContractTemplate
import com.example.data.ContractTemplateRepository
import com.example.data.Renter
import com.example.data.RenterRepository
import com.example.data.Scooter
import com.example.data.SettingsRepository
import com.example.data.TemplateContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ViewModel для страницы «Документооборот».
 *
 * Управляет версиями шаблонов PDF-договора двух типов:
 *   • [ContractTemplate.TYPE_UNLIMITED] — бесконечная аренда
 *   • [ContractTemplate.TYPE_LIMITED] — конечная аренда (неделя)
 *
 * По образцу [ContractHistoryViewModel] — AndroidViewModel, ручной DI через
 * `AppDatabase.getDatabase(application)`.
 *
 * Реактивный поток:
 *   1. [selectedType] — выбранный пользователем тип (UNLIMITED / LIMITED).
 *   2. [searchQuery] — поисковый запрос (для фильтрации версий по имени).
 *   3. [templates] — список версий для выбранного типа + фильтра.
 *   4. [selectedTemplateId] — выбранная версия (для превью).
 *   5. [selectedRenterId] — выбранный клиент для демо-данных.
 *   6. [previewBitmaps] — список Bitmap'ов страниц PDF (рендерится через
 *      [PdfRenderer] из SDK, без новых библиотек).
 */
class ContractTemplateViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "ContractTemplateVM"
    }

    private val db = AppDatabase.getDatabase(application)
    private val repo = ContractTemplateRepository(db.contractTemplateDao(), db)
    private val renterRepo = RenterRepository(db.renterDao())
    private val settings = SettingsRepository(application)

    // ── UI State: выбор пользователя ───────────────────────────────────────
    private val _selectedType = MutableStateFlow(ContractTemplate.TYPE_UNLIMITED)
    val selectedType: StateFlow<String> = _selectedType.asStateFlow()

    private val _selectedTemplateId = MutableStateFlow<Int?>(null)
    val selectedTemplateId: StateFlow<Int?> = _selectedTemplateId.asStateFlow()

    private val _selectedRenterId = MutableStateFlow<Int?>(null)
    val selectedRenterId: StateFlow<Int?> = _selectedRenterId.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // ── UI State: данные ────────────────────────────────────────────────────

    /**
     * Список версий для выбранного типа + фильтра.
     *
     * combine(_selectedType, _searchQuery) → Flow<Pair<String, String>>
     * flatMapLatest → при изменении типа или фильтра пересоздаёт Flow
     * из репозитория (старый отменяется).
     */
    val templates: StateFlow<List<ContractTemplate>> =
        combine(_selectedType, _searchQuery) { type, query -> type to query }
            .flatMapLatest { (type, query) ->
                if (query.isBlank()) repo.forType(type) else repo.search(type, query)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Все активные арендаторы (не в корзине) — для селектора клиентов. */
    val renters: StateFlow<List<Renter>> = renterRepo.liveRenters
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Активная версия для выбранного типа — для индикации ★ в TopAppBar.
     * Возвращает id активного шаблона (или null, если активной нет).
     */
    val activeTemplateId: StateFlow<Int?> = _selectedType
        .distinctUntilChanged()
        .flatMapLatest { type -> repo.activeForType(type) }
        .map { it?.id }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // ── UI State: превью PDF ────────────────────────────────────────────────
    private val _previewBitmaps = MutableStateFlow<List<Bitmap>>(emptyList())
    val previewBitmaps: StateFlow<List<Bitmap>> = _previewBitmaps.asStateFlow()

    private val _isPreviewLoading = MutableStateFlow(false)
    val isPreviewLoading: StateFlow<Boolean> = _isPreviewLoading.asStateFlow()

    private val _previewError = MutableStateFlow<String?>(null)
    val previewError: StateFlow<String?> = _previewError.asStateFlow()

    // ── Actions: выбор ──────────────────────────────────────────────────────
    fun selectType(type: String) {
        _selectedType.value = type
        _selectedTemplateId.value = null
        regeneratePreview()
    }

    fun selectTemplate(id: Int?) {
        _selectedTemplateId.value = id
        regeneratePreview()
    }

    fun selectRenter(id: Int?) {
        _selectedRenterId.value = id
        regeneratePreview()
    }

    fun setSearchQuery(q: String) {
        _searchQuery.value = q
    }

    // ── Actions: CRUD ───────────────────────────────────────────────────────
    fun createTemplate(name: String, content: TemplateContent, onDone: (Long) -> Unit = {}) =
        viewModelScope.launch {
            val id = repo.create(_selectedType.value, name, content)
            onDone(id)
        }

    fun updateTemplate(id: Int, name: String, content: TemplateContent, onDone: () -> Unit = {}) =
        viewModelScope.launch {
            repo.update(id, name, content)
            onDone()
            if (id == _selectedTemplateId.value) regeneratePreview()
        }

    fun deleteTemplate(id: Int, onDone: () -> Unit = {}) = viewModelScope.launch {
        val wasActive = repo.getById(id)?.isActive == true
        val type = _selectedType.value
        repo.delete(id)
        if (wasActive) {
            // Если удалили активную версию — назначить активной самую свежую
            // оставшуюся не удалённую.
            val remaining = db.contractTemplateDao().getForTypeOnce(type)
                .filterNot { it.isDeleted }
                .sortedByDescending { it.updatedAt ?: it.createdAt }
            remaining.firstOrNull()?.let { repo.setActive(type, it.id) }
        }
        if (_selectedTemplateId.value == id) _selectedTemplateId.value = null
        onDone()
    }

    fun setActive(id: Int) = viewModelScope.launch {
        repo.setActive(_selectedType.value, id)
    }

    suspend fun getById(id: Int): ContractTemplate? = withContext(Dispatchers.IO) {
        repo.getById(id)
    }

    fun parseContent(contentJson: String): TemplateContent = repo.parseContent(contentJson)

    // ── Actions: превью PDF ────────────────────────────────────────────────
    /**
     * Регенерирует превью PDF. Запускает в Dispatchers.IO рендер выбранной
     * версии шаблона + выбранного клиента (или первого из renters, если
     * никто не выбран) → кэширует Bitmap'ы в [_previewBitmaps].
     *
     * Вызывается автоматически при изменении [selectedType] / [selectedTemplateId]
     * / [selectedRenterId], а также после сохранения изменений.
     */
    fun regeneratePreview() = viewModelScope.launch {
        _isPreviewLoading.value = true
        _previewError.value = null
        try {
            withContext(Dispatchers.IO) {
                val type = _selectedType.value
                val template = _selectedTemplateId.value?.let { repo.getById(it) }
                    ?: repo.getActiveForType(type)
                    ?: run {
                        _previewError.value = "Нет активного шаблона"
                        _previewBitmaps.value = emptyList()
                        return@withContext
                    }
                // Если body пустой — fallback на дефолт
                val content = repo.parseContent(template.contentJson).let {
                    if (it.bodyText.isBlank()) {
                        it.copy(
                            bodyText = if (type == ContractTemplate.TYPE_UNLIMITED)
                                TemplateContent.DEFAULT_CONTRACT_BODY_UNLIMITED
                            else
                                TemplateContent.DEFAULT_CONTRACT_BODY_LIMITED
                        )
                    } else it
                }

                val renter = _selectedRenterId.value?.let { renterRepo.getById(it) }
                    ?: renters.value.firstOrNull()
                    ?: run {
                        _previewError.value = "Добавьте хотя бы одного клиента для превью"
                        _previewBitmaps.value = emptyList()
                        return@withContext
                    }
                val scooter = renter.scooterId?.let { db.scooterDao().getScooterById(it) }

                // 1. Сгенерировать PDF во cacheDir
                val previewFile = File(
                    getApplication<Application>().cacheDir,
                    "preview_${System.currentTimeMillis()}.pdf"
                )
                val uri = when (type) {
                    ContractTemplate.TYPE_UNLIMITED -> {
                        PdfContractGenerator.generateUnlimitedTo(
                            getApplication(), renter, scooter, content, previewFile
                        )
                    }
                    else -> {
                        val entry = makePreviewEntry(renter, scooter)
                        PdfContractGenerator.generateTo(
                            getApplication(), entry, renter, scooter, content, previewFile
                        )
                    }
                }
                if (uri == null) {
                    _previewError.value = "PDF generation failed"
                    _previewBitmaps.value = emptyList()
                    return@withContext
                }

                // 2. Рендерить страницы через PdfRenderer
                val bitmaps = renderPdfToBitmaps(previewFile)
                _previewBitmaps.value = bitmaps
            }
        } catch (e: Exception) {
            Log.e(TAG, "regeneratePreview failed", e)
            _previewError.value = e.message ?: "Preview error"
            _previewBitmaps.value = emptyList()
        } finally {
            _isPreviewLoading.value = false
        }
    }

    /**
     * Скачивает PDF выбранной версии шаблона с демо-данными (выбранный клиент)
     * в публичную папку Documents/ScooterContracts/. Возвращает Uri для
     * открытия во внешнем приложении.
     *
     * Используется при долгом нажатии на ★ в TopAppBar.
     */
    suspend fun downloadSelectedWithDemoData(): Uri? = withContext(Dispatchers.IO) {
        try {
            val type = _selectedType.value
            val template = _selectedTemplateId.value?.let { repo.getById(it) }
                ?: repo.getActiveForType(type)
                ?: return@withContext null
            val content = repo.parseContent(template.contentJson).let {
                if (it.bodyText.isBlank()) {
                    it.copy(
                        bodyText = if (type == ContractTemplate.TYPE_UNLIMITED)
                            TemplateContent.DEFAULT_CONTRACT_BODY_UNLIMITED
                        else
                            TemplateContent.DEFAULT_CONTRACT_BODY_LIMITED
                    )
                } else it
            }

            val renter = _selectedRenterId.value?.let { renterRepo.getById(it) }
                ?: renters.value.firstOrNull()
                ?: return@withContext null
            val scooter = renter.scooterId?.let { db.scooterDao().getScooterById(it) }

            // Сохранить в Documents/ScooterContracts/
            val dir = File(
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOCUMENTS
                ),
                "ScooterContracts"
            )
            if (!dir.exists()) dir.mkdirs()
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val baseName = if (type == ContractTemplate.TYPE_UNLIMITED) {
                "rental_contract_unlimited_template${template.id}_${ts}"
            } else {
                "rental_contract_template${template.id}_${ts}"
            }
            val file = File(dir, "${baseName}.pdf")

            when (type) {
                ContractTemplate.TYPE_UNLIMITED -> {
                    PdfContractGenerator.generateUnlimitedTo(
                        getApplication(), renter, scooter, content, file
                    )
                }
                else -> {
                    val entry = makePreviewEntry(renter, scooter)
                    PdfContractGenerator.generateTo(
                        getApplication(), entry, renter, scooter, content, file
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "downloadSelectedWithDemoData failed", e)
            null
        }
    }

    /**
     * Строит фейковый ContractHistoryEntry для превью LIMITED-договора.
     * Использует данные renter + scooter, не сохраняет в БД.
     */
    private fun makePreviewEntry(renter: Renter, scooter: Scooter?): ContractHistoryEntry {
        val now = System.currentTimeMillis()
        return ContractHistoryEntry(
            id = 999, // sentinel — для превью; не сохраняется в БД
            renterId = renter.id,
            timestamp = now,
            type = "CREATED",
            renterName = renter.name,
            renterPhone = renter.phoneNumber,
            scooterName = renter.scooterName ?: scooter?.name,
            weekStart = renter.rentStartDateTimestamp,
            weekEnd = renter.rentStartDateTimestamp + renter.rentDurationDays * 24L * 60 * 60 * 1000,
            weeklyPrice = settings.weeklyPrice.let { if (it > 0) it else SettingsRepository.DEFAULT_WEEKLY_PRICE },
            passportData = renter.passportData,
            address = renter.address,
            pinfl = renter.pinfl,
            vinNumber = scooter?.vinNumber ?: "",
            engineNumber = scooter?.engineNumber ?: "",
            scooterSerialNumber = scooter?.scooterSerialNumber ?: "",
            batteryId1 = scooter?.batteryId1 ?: "",
            batteryId2 = scooter?.batteryId2 ?: "",
            additionalInfo = scooter?.additionalInfo ?: ""
        )
    }

    /**
     * Рендерит PDF в список Bitmap'ов (по одной на страницу) через
     * [PdfRenderer] из Android SDK. Не требует сторонних библиотек.
     */
    private fun renderPdfToBitmaps(file: File): List<Bitmap> {
        if (!file.exists()) return emptyList()
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(pfd)
        try {
            return (0 until renderer.pageCount).map { i ->
                val page = renderer.openPage(i)
                try {
                    // A4 @ 72 DPI = 595x842
                    val bitmap = Bitmap.createBitmap(595, 842, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                } finally {
                    page.close()
                }
            }
        } finally {
            renderer.close()
            pfd.close()
        }
    }
}
