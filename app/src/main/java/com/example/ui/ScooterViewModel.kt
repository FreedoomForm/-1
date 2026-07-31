package com.example.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.Scooter
import com.example.data.ScooterRepository
import com.example.data.TimelineService
import com.example.data.TrashService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScooterViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: ScooterRepository
    private val scooterDao: com.example.data.ScooterDao
    val scootersList: StateFlow<List<Scooter>>

    /**
     * UI feedback channel — emit (success, message) tuples for toast display.
     * Solves the "silent failure" bug: previously addScooter/updateScooter
     * logged duplicate conflicts but the user saw nothing — the dialog closed
     * and the scooter simply wasn't created.
     */
    private val _userMessage = MutableSharedFlow<Pair<Boolean, String>>(extraBufferCapacity = 8)
    val userMessage: SharedFlow<Pair<Boolean, String>> = _userMessage.asSharedFlow()

    init {
        val database = AppDatabase.getDatabase(application)
        scooterDao = database.scooterDao()
        repository = ScooterRepository(scooterDao)
        scootersList = repository.allScooters.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
    }

    fun addScooter(
        name: String,
        documentedNumber: String?,
        vinNumber: String = "",
        engineNumber: String = "",
        scooterSerialNumber: String = "",
        batteryId1: String = "",
        batteryId2: String = "",
        additionalInfo: String = "",
        nextServiceAt: Long? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // ── Validate name ─────────────────────────────────────────────
                val trimmedName = name.trim()
                if (trimmedName.isBlank()) {
                    _userMessage.emit(false to "Skuter nomi bo'sh bo'lishi mumkin emas")
                    return@launch
                }

                // ── Duplicate name check (case-insensitive) ───────────────────
                // Mirrors RenterViewModel.addRenter's check. The form also does
                // this live, but a hard block here is the last line of defense.
                val allScooters = scooterDao.getAllScootersOnce()
                val nameConflict = allScooters.any {
                    it.name.trim().equals(trimmedName, ignoreCase = true)
                }
                if (nameConflict) {
                    _userMessage.emit(false to "Bunday nomdagi skuter allaqachon mavjud: $trimmedName")
                    return@launch
                }

                // ── Duplicate VIN/engine/serial check ─────────────────────────
                val vinConflict = vinNumber.trim().isNotBlank() && allScooters.any {
                    it.vinNumber.trim().equals(vinNumber.trim(), ignoreCase = true)
                }
                val engineConflict = engineNumber.trim().isNotBlank() && allScooters.any {
                    it.engineNumber.trim().equals(engineNumber.trim(), ignoreCase = true)
                }
                val serialConflict = scooterSerialNumber.trim().isNotBlank() && allScooters.any {
                    it.scooterSerialNumber.trim().equals(scooterSerialNumber.trim(), ignoreCase = true)
                }
                if (vinConflict || engineConflict || serialConflict) {
                    val dup = buildString {
                        if (vinConflict) append("VIN ")
                        if (engineConflict) append("dvigatel ")
                        if (serialConflict) append("seriya ")
                    }.trim()
                    _userMessage.emit(false to "Bu $dup boshqa skuterga biriktirilgan")
                    return@launch
                }

                val scooter = Scooter(
                    name = trimmedName,
                    documentedNumber = documentedNumber,
                    vinNumber = vinNumber,
                    engineNumber = engineNumber,
                    scooterSerialNumber = scooterSerialNumber,
                    batteryId1 = batteryId1,
                    batteryId2 = batteryId2,
                    additionalInfo = additionalInfo,
                    nextServiceAt = nextServiceAt
                )
                val newId = repository.insert(scooter)
                if (newId <= 0L) {
                    _userMessage.emit(false to "Skuter saqlanmadi (konflikt)")
                    return@launch
                }
                _userMessage.emit(true to "Skuter yaratildi: $trimmedName")

                // §9.0: таймкод критического действия — SCOOTER_CREATE.
                try {
                    TimelineService(AppDatabase.getDatabase(getApplication()))
                        .recordCriticalAction(
                            actionType = "SCOOTER_CREATE",
                            screen = "SCOOTERS",
                            title = "Yangi skuter: $trimmedName",
                            entityType = "SCOOTER",
                            entityId = newId.toString(),
                            payloadJson = "{\"name\":\"$trimmedName\",\"vin\":\"$vinNumber\"}"
                        )
                } catch (_: Exception) {}
            } catch (e: Exception) {
                Log.e(TAG, "addScooter failed", e)
                _userMessage.emit(false to "Skuter yaratilmadi: ${e.message ?: "noma'lum xato"}")
            }
        }
    }

    fun updateScooter(scooter: Scooter) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val trimmedName = scooter.name.trim()
                if (trimmedName.isBlank()) {
                    _userMessage.emit(false to "Skuter nomi bo'sh bo'lishi mumkin emas")
                    return@launch
                }

                // ── Duplicate checks excluding self ───────────────────────────
                val allScooters = scooterDao.getAllScootersOnce()
                val nameConflict = allScooters.any {
                    it.id != scooter.id && it.name.trim().equals(trimmedName, ignoreCase = true)
                }
                if (nameConflict) {
                    _userMessage.emit(false to "Bunday nomdagi skuter allaqachon mavjud: $trimmedName")
                    return@launch
                }
                val vinConflict = scooter.vinNumber.trim().isNotBlank() && allScooters.any {
                    it.id != scooter.id && it.vinNumber.trim().equals(scooter.vinNumber.trim(), ignoreCase = true)
                }
                val engineConflict = scooter.engineNumber.trim().isNotBlank() && allScooters.any {
                    it.id != scooter.id && it.engineNumber.trim().equals(scooter.engineNumber.trim(), ignoreCase = true)
                }
                val serialConflict = scooter.scooterSerialNumber.trim().isNotBlank() && allScooters.any {
                    it.id != scooter.id && it.scooterSerialNumber.trim().equals(scooter.scooterSerialNumber.trim(), ignoreCase = true)
                }
                if (vinConflict || engineConflict || serialConflict) {
                    val dup = buildString {
                        if (vinConflict) append("VIN ")
                        if (engineConflict) append("dvigatel ")
                        if (serialConflict) append("seriya ")
                    }.trim()
                    _userMessage.emit(false to "Bu $dup boshqa skuterga biriktirilgan")
                    return@launch
                }

                repository.update(scooter)
                _userMessage.emit(true to "Skuter yangilandi: $trimmedName")

                // §9.0: таймкод критического действия — SCOOTER_UPDATE.
                try {
                    TimelineService(AppDatabase.getDatabase(getApplication()))
                        .recordCriticalAction(
                            actionType = "SCOOTER_UPDATE",
                            screen = "SCOOTERS",
                            title = "Skuter yangilandi: $trimmedName",
                            entityType = "SCOOTER",
                            entityId = scooter.id.toString(),
                            payloadJson = "{\"name\":\"$trimmedName\",\"status\":\"${scooter.lifecycleStatus}\"}"
                        )
                } catch (_: Exception) {}
            } catch (e: Exception) {
                Log.e(TAG, "updateScooter failed", e)
                _userMessage.emit(false to "Skuter yangilanmadi: ${e.message ?: "noma'lum xato"}")
            }
        }
    }

    fun sendToRepair(
        scooterId: Int,
        reason: String,
        scenario: String = com.example.data.RepairOrder.SCENARIO_RENTER_REPAIR
    ) {
        viewModelScope.launch {
            try { com.example.data.ScooterMaintenanceService(AppDatabase.getDatabase(getApplication()))
                .changeStatus(scooterId, Scooter.STATUS_REPAIR, reason, scenario)
                _userMessage.emit(true to "Skuter ta'mirga yuborildi")
            }
            catch (e: Exception) {
                Log.e(TAG, "Failed to pause rental for repair", e)
                _userMessage.emit(false to "Ta'mirga yuborish amalga oshmadi: ${e.message ?: "noma'lum xato"}")
            }
        }
    }

    fun replaceScooterForRental(oldScooterId: Int, newScooterId: Int, reason: String) {
        viewModelScope.launch {
            try { com.example.data.ScooterMaintenanceService(AppDatabase.getDatabase(getApplication()))
                .replaceScooterForActiveRental(oldScooterId, newScooterId, reason)
                _userMessage.emit(true to "Skuter almashtirildi")
            }
            catch (e: Exception) {
                Log.e(TAG, "Failed to replace scooter for rental", e)
                _userMessage.emit(false to "Skuter almashtirilmadi: ${e.message ?: "noma'lum xato"}")
            }
        }
    }

    fun resumeAfterRepair(scooterId: Int, reason: String) {
        viewModelScope.launch {
            try { com.example.data.ScooterMaintenanceService(AppDatabase.getDatabase(getApplication()))
                .resumeAfterRepair(scooterId, reason)
                _userMessage.emit(true to "Skuter ta'mirdan qaytdi")
            }
            catch (e: Exception) {
                Log.e(TAG, "Failed to resume rental after repair", e)
                _userMessage.emit(false to "Ta'mirdan qaytarish amalga oshmadi: ${e.message ?: "noma'lum xato"}")
            }
        }
    }

    companion object { private const val TAG = "ScooterViewModel" }

    /**
     * Каскадное удаление скутера.
     *
     * Шаги:
     * 1. Snapshot скутера в корзину (TrashService) — чтобы можно было восстановить.
     * 2. Найти всех арендаторов с scooterId = scooter.id.
     *    Для каждого: очистить scooterId и scooterName (разорвать связь),
     *    НЕ удаляя арендатора.
     * 3. Найти все ContractHistoryEntry с scooterName = scooter.name —
     *    snapshot в корзину, затем удалить (они больше не имеют смысла
     *    без скутера).
     * 4. Найти все Transaction с scooterId = scooter.id — snapshot в корзину,
     *    затем удалить.
     * 5. Найти все открытые RepairOrder с scooterId = scooter.id — закрыть их.
     * 6. Записать таймкод критического действия SCOOTER_DELETE.
     * 7. Удалить сам скутер.
     *
     * Раньше deleteScooter просто вызывал repository.delete(scooter) без
     * какого-либо каскада — оставались «осиротевшие» транзакции и контракты
     * со ссылками на несуществующий скутер.
     */
    fun deleteScooter(scooter: Scooter) {
        viewModelScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(getApplication())
            // Batch 14 (was HIGH 6.1): delegate the cascade-delete to
            // DeletionService.deleteScooterCascade. Previously this
            // method duplicated ~85 lines of cascade logic that was
            // also copy-pasted (with variations) in RenterViewModel and
            // ContractHistoryViewModel — each copy had its own bugs.
            // Now all three ViewModels delegate to the same service,
            // which wraps the entire cascade in db.withTransaction and
            // uses field-specific UPDATE queries (Batch 12 pattern).
            val ok = com.example.data.DeletionService(db).deleteScooterCascade(scooter)
            if (ok) {
                // Timeline critical action (kept in the VM because it
                // needs the application context + _userMessage flow).
                try {
                    TimelineService(db).recordCriticalAction(
                        actionType = "SCOOTER_DELETE",
                        screen = "SCOOTERS",
                        title = "Skuter o'chirildi: ${scooter.name}",
                        entityType = "SCOOTER",
                        entityId = scooter.id.toString(),
                        payloadJson = "{\"name\":\"${scooter.name}\"}"
                    )
                } catch (_: Exception) {}
                _userMessage.emit(true to "Skuter o'chirildi: ${scooter.name}")
            } else {
                _userMessage.emit(false to "Skuterni o'chirish amalga oshmadi")
                // Fallback: still try plain delete (no cascade) so the
                // scooter row is at least removed from the list — the
                // dependent rows will be cleaned up by OrphanSweeper
                // on the next DB open.
                try { repository.delete(scooter) } catch (_: Exception) {}
            }
            // Update widgets regardless of cascade success.
            try { com.example.widget.WidgetUpdater.updateAll(getApplication()) } catch (_: Exception) {}
        }
    }

    /** Bulk delete multiple scooters with the same cascade as [deleteScooter]. */
    fun deleteScooters(scooters: List<Scooter>) {
        scooters.forEach { deleteScooter(it) }
    }
}
