package com.example

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.ContractHistoryEntry
import com.example.data.Renter
import com.example.data.Scooter
import com.example.data.Transaction
import com.example.ui.ContractHistoryViewModel
import com.example.ui.ScooterViewModel
import com.example.ui.TransactionViewModel
import com.example.ui.components.NonSortableHeaderCellFixed
import com.example.ui.components.PrimaryButton
import com.example.ui.components.SecondaryButton
import com.example.ui.components.DangerButton
import com.example.ui.components.TextActionButton
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* ============================================================================
   СЕКЦИЯ «ТРАНЗАКЦИИ АРЕНДАТОРА»
   ============================================================================

   Используется внутри `RenterContractHistoryScreen` как альтернативная
   вкладка к «Контрактам». Показывает все транзакции, привязанные к
   конкретному арендатору (renterId == renter.id).

   Структура повторяет список контрактов:
     • Панель действий (Yaratish / Tahrirlash / O'chir) — всегда видна
     • Заголовок таблицы (Sana, Kontrakt, Tur, Summa)
     • Список транзакций с цветной рамкой:
         зелёная  — приход (PAYMENT/RETURNED)
         красная  — расход (TERMINATED/PENALTY/REPAIR)
         серая    — CUSTOM
     • Создание/редактирование/удаление через диалоги
   ============================================================================ */

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun RenterTransactionListSection(
    renter: Renter,
    allScooters: List<Scooter>,
    transactionViewModel: TransactionViewModel = viewModel(),
    contractHistoryViewModel: ContractHistoryViewModel = viewModel()
) {
    val context = LocalContext.current
    val transactions by transactionViewModel.transactionsForRenter(renter.id)
        .collectAsStateWithLifecycle()
    val allHistory by contractHistoryViewModel.history.collectAsStateWithLifecycle()

    // Только контракты этого арендатора — для выпадающего списка в диалоге
    val renterContracts = remember(allHistory, renter.id) {
        allHistory
            .filter {
                it.renterId == renter.id &&
                    (it.type == ContractHistoryEntry.TYPE_CREATED ||
                        it.type == ContractHistoryEntry.TYPE_AUTO_RENEW)
            }
            .sortedByDescending { it.weekStart ?: it.timestamp }
    }

    var selectedTxs by remember { mutableStateOf(setOf<Int>()) }
    var searchQuery by remember { mutableStateOf("") }
    var editingTx by remember { mutableStateOf<Transaction?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }

    val dateFmt = remember { SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()) }
    val dateTimeFmt = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }

    val filteredTxs = remember(transactions, searchQuery) {
        transactions.filter { t ->
            searchQuery.isBlank() ||
                t.renterName.contains(searchQuery, ignoreCase = true) ||
                t.renterPhone.contains(searchQuery, ignoreCase = true) ||
                t.scooterName.contains(searchQuery, ignoreCase = true) ||
                t.contractLabel.contains(searchQuery, ignoreCase = true) ||
                (t.notes?.contains(searchQuery, ignoreCase = true) == true) ||
                t.id.toString().contains(searchQuery) ||
                TransactionViewModel.typeLabel(t.type).contains(searchQuery, ignoreCase = true)
        }
    }

    // Ширины fixed-колонок (как в TransactionListScreen)
    val hScrollState = rememberScrollState()
    val wId       = 50.dp
    val wDate     = 110.dp
    val wScooter  = 90.dp
    val wContract = 140.dp
    val wType     = 90.dp
    val wAmount   = 85.dp

    Column(modifier = Modifier.fillMaxSize()) {
        // ── Панель действий — всегда видна ─────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PrimaryButton(
                label = "Yaratish",
                icon = Icons.Default.Add,
                onClick = { showCreateDialog = true }
            )
            SecondaryButton(
                label = "Tahrirlash",
                icon = Icons.Default.Edit,
                enabled = selectedTxs.size == 1,
                onClick = {
                    val id = selectedTxs.first()
                    editingTx = transactions.firstOrNull { it.id == id }
                }
            )
            DangerButton(
                label = "O'chir",
                icon = Icons.Default.Delete,
                enabled = selectedTxs.isNotEmpty(),
                onClick = { showDeleteConfirm = true }
            )
            Spacer(Modifier.weight(1f))
            Text(
                "Jami: ${filteredTxs.size}",
                style = MaterialTheme.typography.bodySmall,
                color = ClaudeTextSecondary
            )
        }

        // ── Заголовок таблицы ───────────────────────────────────────────
        Surface(color = ClaudeCard, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .horizontalScroll(hScrollState)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                NonSortableHeaderCellFixed(Icons.Default.Numbers,              wId,       "#")
                NonSortableHeaderCellFixed(Icons.Default.DateRange,             wDate,     "Sana")
                NonSortableHeaderCellFixed(Icons.Default.DirectionsBike,        wScooter,  "Skuter")
                NonSortableHeaderCellFixed(Icons.Default.Description,           wContract, "Kontrakt")
                NonSortableHeaderCellFixed(Icons.Default.Category,              wType,     "Tur")
                NonSortableHeaderCellFixed(Icons.Default.AccountBalanceWallet,  wAmount,   "Summa")
            }
        }
        HorizontalDivider(color = ClaudeDivider)

        // ── Список транзакций ──────────────────────────────────────────
        if (filteredTxs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Tranzaksiyalar yo'q",
                    color = ClaudeTextSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(filteredTxs, key = { it.id }) { tx ->
                    val isSelected = tx.id in selectedTxs
                    val isPositive = TransactionViewModel.typeIsPositive(tx.type)
                    val effectivePositive = isPositive && tx.amount >= 0
                    val statusColor = when (tx.type) {
                        Transaction.TYPE_CUSTOM -> ClaudeTextSecondary
                        else -> if (effectivePositive) StatusOk else StatusOverdue
                    }
                    val typeLabel = TransactionViewModel.typeLabel(tx.type)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .horizontalScroll(hScrollState)
                                .border(
                                    width = if (isSelected) 2.dp else 1.5.dp,
                                    color = statusColor,
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .background(if (isSelected) ClaudeAccentBg else ClaudeCard)
                                .combinedClickable(
                                    onClick = {
                                        if (isSelected) {
                                            selectedTxs = selectedTxs - tx.id
                                        } else {
                                            editingTx = tx
                                        }
                                    },
                                    onLongClick = {
                                        selectedTxs = if (isSelected) selectedTxs - tx.id
                                        else selectedTxs + tx.id
                                    }
                                )
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "#${tx.id}",
                                modifier = Modifier.width(wId).padding(horizontal = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = ClaudeTextSecondary,
                                maxLines = 1
                            )
                            Text(
                                dateTimeFmt.format(Date(tx.timestamp)),
                                modifier = Modifier.width(wDate).padding(horizontal = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = ClaudeText,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                            Text(
                                tx.scooterName.ifBlank { "—" },
                                modifier = Modifier.width(wScooter).padding(horizontal = 4.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = ClaudeText,
                                maxLines = 1
                            )
                            Text(
                                tx.contractLabel.ifBlank { "—" },
                                modifier = Modifier.width(wContract).padding(horizontal = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = ClaudeTextSecondary,
                                maxLines = 1
                            )
                            Row(
                                modifier = Modifier.width(wType).padding(horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(statusColor, CircleShape)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    typeLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = statusColor,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1
                                )
                            }
                            val sign = if (effectivePositive) "+" else "−"
                            val displayAmount = kotlin.math.abs(tx.amount.toLong())
                            Text(
                                "$sign $displayAmount",
                                modifier = Modifier.width(wAmount).padding(horizontal = 4.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = statusColor,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.End,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }

    // ── Диалог редактирования ────────────────────────────────────────────
    editingTx?.let { tx ->
        EditTransactionForRenterDialog(
            tx = tx,
            renterContracts = renterContracts,
            allScooters = allScooters,
            onDismiss = { editingTx = null },
            onSave = { updated ->
                transactionViewModel.updateTransaction(updated)
                editingTx = null
                Toast.makeText(context, "Tranzaksiya yangilandi", Toast.LENGTH_SHORT).show()
            },
            onDelete = {
                transactionViewModel.deleteTransaction(tx.id)
                editingTx = null
                Toast.makeText(context, "Tranzaksiya o'chirildi", Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Tranzaksiyalarni o'chirish") },
            text = { Text("${selectedTxs.size} ta tranzaksiyani o'chirmoqchimisz? Bu amalni qaytarib bo'lmaydi.") },
            confirmButton = {
                DangerButton(
                    label = "O'chir",
                    icon = Icons.Default.Delete,
                    onClick = {
                        transactionViewModel.deleteTransactions(selectedTxs.toList())
                        Toast.makeText(context, "${selectedTxs.size} ta o'chirildi", Toast.LENGTH_SHORT).show()
                        selectedTxs = emptySet()
                        showDeleteConfirm = false
                    }
                )
            },
            dismissButton = {
                TextActionButton(
                    label = "Bekor",
                    icon = Icons.Default.Close,
                    onClick = { showDeleteConfirm = false }
                )
            }
        )
    }

    if (showCreateDialog) {
        CreateTransactionForRenterDialog(
            renter = renter,
            renterContracts = renterContracts,
            allScooters = allScooters,
            onDismiss = { showCreateDialog = false },
            onCreate = { contractId, rId, rName, rPhone, sId, sName, cLabel, type, amount, timestamp, noteText ->
                transactionViewModel.createTransaction(
                    contractId = contractId,
                    renterId = rId,
                    renterName = rName,
                    renterPhone = rPhone,
                    scooterId = sId,
                    scooterName = sName,
                    contractLabel = cLabel,
                    type = type,
                    amount = amount,
                    timestamp = timestamp,
                    notes = noteText
                )
                Toast.makeText(context, "Tranzaksiya yaratildi", Toast.LENGTH_SHORT).show()
                showCreateDialog = false
            }
        )
    }
}

/* ============================================================================
   СЕКЦИЯ «ТРАНЗАКЦИИ СКУТЕРА»
   ============================================================================

   Используется внутри `ScooterContractHistoryScreen` как альтернативная
   вкладка к «Контрактам». Показывает все транзакции, привязанные к
   конкретному скутеру (scooterId == scooter.id).
   ============================================================================ */

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ScooterTransactionListSection(
    scooter: Scooter,
    renters: List<Renter>,
    transactionViewModel: TransactionViewModel = viewModel(),
    contractHistoryViewModel: ContractHistoryViewModel = viewModel()
) {
    val context = LocalContext.current
    val transactions by transactionViewModel.transactionsForScooter(scooter.id)
        .collectAsStateWithLifecycle()
    val allHistory by contractHistoryViewModel.history.collectAsStateWithLifecycle()

    // Только контракты этого скутера — для выпадающего списка в диалоге
    val scooterContracts = remember(allHistory, scooter.name) {
        allHistory
            .filter {
                it.scooterName == scooter.name &&
                    (it.type == ContractHistoryEntry.TYPE_CREATED ||
                        it.type == ContractHistoryEntry.TYPE_AUTO_RENEW)
            }
            .sortedByDescending { it.weekStart ?: it.timestamp }
    }

    var selectedTxs by remember { mutableStateOf(setOf<Int>()) }
    var searchQuery by remember { mutableStateOf("") }
    var editingTx by remember { mutableStateOf<Transaction?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }

    val dateTimeFmt = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }

    val filteredTxs = remember(transactions, searchQuery) {
        transactions.filter { t ->
            searchQuery.isBlank() ||
                t.renterName.contains(searchQuery, ignoreCase = true) ||
                t.renterPhone.contains(searchQuery, ignoreCase = true) ||
                t.scooterName.contains(searchQuery, ignoreCase = true) ||
                t.contractLabel.contains(searchQuery, ignoreCase = true) ||
                (t.notes?.contains(searchQuery, ignoreCase = true) == true) ||
                t.id.toString().contains(searchQuery) ||
                TransactionViewModel.typeLabel(t.type).contains(searchQuery, ignoreCase = true)
        }
    }

    val hScrollState = rememberScrollState()
    val wId       = 50.dp
    val wDate     = 110.dp
    val wRenter   = 110.dp
    val wPhone    = 95.dp
    val wContract = 140.dp
    val wType     = 90.dp
    val wAmount   = 85.dp

    Column(modifier = Modifier.fillMaxSize()) {
        // ── Панель действий ───────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PrimaryButton(
                label = "Yaratish",
                icon = Icons.Default.Add,
                onClick = { showCreateDialog = true }
            )
            SecondaryButton(
                label = "Tahrirlash",
                icon = Icons.Default.Edit,
                enabled = selectedTxs.size == 1,
                onClick = {
                    val id = selectedTxs.first()
                    editingTx = transactions.firstOrNull { it.id == id }
                }
            )
            DangerButton(
                label = "O'chir",
                icon = Icons.Default.Delete,
                enabled = selectedTxs.isNotEmpty(),
                onClick = { showDeleteConfirm = true }
            )
            Spacer(Modifier.weight(1f))
            Text(
                "Jami: ${filteredTxs.size}",
                style = MaterialTheme.typography.bodySmall,
                color = ClaudeTextSecondary
            )
        }

        // ── Заголовок таблицы ───────────────────────────────────────────
        Surface(color = ClaudeCard, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .horizontalScroll(hScrollState)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                NonSortableHeaderCellFixed(Icons.Default.Numbers,              wId,       "#")
                NonSortableHeaderCellFixed(Icons.Default.DateRange,             wDate,     "Sana")
                NonSortableHeaderCellFixed(Icons.Default.Person,                wRenter,   "Mijoz")
                NonSortableHeaderCellFixed(Icons.Default.Phone,                 wPhone,    "Telefon")
                NonSortableHeaderCellFixed(Icons.Default.Description,           wContract, "Kontrakt")
                NonSortableHeaderCellFixed(Icons.Default.Category,              wType,     "Tur")
                NonSortableHeaderCellFixed(Icons.Default.AccountBalanceWallet,  wAmount,   "Summa")
            }
        }
        HorizontalDivider(color = ClaudeDivider)

        // ── Список ──────────────────────────────────────────────────────
        if (filteredTxs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Bu skuter uchun tranzaksiyalar yo'q",
                    color = ClaudeTextSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(filteredTxs, key = { it.id }) { tx ->
                    val isSelected = tx.id in selectedTxs
                    val isPositive = TransactionViewModel.typeIsPositive(tx.type)
                    val effectivePositive = isPositive && tx.amount >= 0
                    val statusColor = when (tx.type) {
                        Transaction.TYPE_CUSTOM -> ClaudeTextSecondary
                        else -> if (effectivePositive) StatusOk else StatusOverdue
                    }
                    val typeLabel = TransactionViewModel.typeLabel(tx.type)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .horizontalScroll(hScrollState)
                                .border(
                                    width = if (isSelected) 2.dp else 1.5.dp,
                                    color = statusColor,
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .background(if (isSelected) ClaudeAccentBg else ClaudeCard)
                                .combinedClickable(
                                    onClick = {
                                        if (isSelected) {
                                            selectedTxs = selectedTxs - tx.id
                                        } else {
                                            editingTx = tx
                                        }
                                    },
                                    onLongClick = {
                                        selectedTxs = if (isSelected) selectedTxs - tx.id
                                        else selectedTxs + tx.id
                                    }
                                )
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "#${tx.id}",
                                modifier = Modifier.width(wId).padding(horizontal = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = ClaudeTextSecondary,
                                maxLines = 1
                            )
                            Text(
                                dateTimeFmt.format(Date(tx.timestamp)),
                                modifier = Modifier.width(wDate).padding(horizontal = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = ClaudeText,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                            Text(
                                tx.renterName.ifBlank { "—" },
                                modifier = Modifier.width(wRenter).padding(horizontal = 4.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = ClaudeText,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                            Text(
                                tx.renterPhone.ifBlank { "—" },
                                modifier = Modifier.width(wPhone).padding(horizontal = 4.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = ClaudeTextSecondary,
                                maxLines = 1
                            )
                            Text(
                                tx.contractLabel.ifBlank { "—" },
                                modifier = Modifier.width(wContract).padding(horizontal = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = ClaudeTextSecondary,
                                maxLines = 1
                            )
                            Row(
                                modifier = Modifier.width(wType).padding(horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(statusColor, CircleShape)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    typeLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = statusColor,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1
                                )
                            }
                            val sign = if (effectivePositive) "+" else "−"
                            val displayAmount = kotlin.math.abs(tx.amount.toLong())
                            Text(
                                "$sign $displayAmount",
                                modifier = Modifier.width(wAmount).padding(horizontal = 4.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = statusColor,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.End,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }

    // ── Диалог редактирования ────────────────────────────────────────────
    editingTx?.let { tx ->
        EditTransactionForScooterDialog(
            tx = tx,
            scooter = scooter,
            scooterContracts = scooterContracts,
            renters = renters,
            onDismiss = { editingTx = null },
            onSave = { updated ->
                transactionViewModel.updateTransaction(updated)
                editingTx = null
                Toast.makeText(context, "Tranzaksiya yangilandi", Toast.LENGTH_SHORT).show()
            },
            onDelete = {
                transactionViewModel.deleteTransaction(tx.id)
                editingTx = null
                Toast.makeText(context, "Tranzaksiya o'chirildi", Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Tranzaksiyalarni o'chirish") },
            text = { Text("${selectedTxs.size} ta tranzaksiyani o'chirmoqchimisz? Bu amalni qaytarib bo'lmaydi.") },
            confirmButton = {
                DangerButton(
                    label = "O'chir",
                    icon = Icons.Default.Delete,
                    onClick = {
                        transactionViewModel.deleteTransactions(selectedTxs.toList())
                        Toast.makeText(context, "${selectedTxs.size} ta o'chirildi", Toast.LENGTH_SHORT).show()
                        selectedTxs = emptySet()
                        showDeleteConfirm = false
                    }
                )
            },
            dismissButton = {
                TextActionButton(
                    label = "Bekor",
                    icon = Icons.Default.Close,
                    onClick = { showDeleteConfirm = false }
                )
            }
        )
    }

    if (showCreateDialog) {
        CreateTransactionForScooterDialog(
            scooter = scooter,
            scooterContracts = scooterContracts,
            renters = renters,
            onDismiss = { showCreateDialog = false },
            onCreate = { contractId, rId, rName, rPhone, sId, sName, cLabel, type, amount, timestamp, noteText ->
                transactionViewModel.createTransaction(
                    contractId = contractId,
                    renterId = rId,
                    renterName = rName,
                    renterPhone = rPhone,
                    scooterId = sId,
                    scooterName = sName,
                    contractLabel = cLabel,
                    type = type,
                    amount = amount,
                    timestamp = timestamp,
                    notes = noteText
                )
                Toast.makeText(context, "Tranzaksiya yaratildi", Toast.LENGTH_SHORT).show()
                showCreateDialog = false
            }
        )
    }
}

/* ============================================================================
   ДИАЛОГ СОЗДАНИЯ ТРАНЗАКЦИИ (привязка к арендатору)
   ============================================================================

   Рентер зафиксирован (отображается read-only). Скутер выбирается из
   списка (по умолчанию — скутер арендатора). Контракт выбирается только
   из контрактов этого арендатора.
   ============================================================================ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateTransactionForRenterDialog(
    renter: Renter,
    renterContracts: List<ContractHistoryEntry>,
    allScooters: List<Scooter>,
    onDismiss: () -> Unit,
    onCreate: (
        contractId: Int?, renterId: Int,
        renterName: String, renterPhone: String,
        scooterId: Int?, scooterName: String, contractLabel: String,
        type: String, amount: Double, timestamp: Long, notes: String?
    ) -> Unit
) {
    val dateTimeFmt = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
    val dayMs = 24L * 60 * 60 * 1000
    val initialTimestamp = (System.currentTimeMillis() / (15 * 60 * 1000)) * (15 * 60 * 1000)

    // Рентер зафиксирован
    val renterId = renter.id
    val renterName = renter.name
    val renterPhone = renter.phoneNumber

    var contractId by remember { mutableStateOf<Int?>(null) }
    var contractLabel by remember { mutableStateOf("") }
    var scooterId by remember { mutableStateOf(renter.scooterId) }
    var scooterName by remember { mutableStateOf(renter.scooterName ?: "") }
    var type by remember { mutableStateOf(Transaction.TYPE_PAYMENT) }
    var amount by remember { mutableStateOf("") }
    var timestamp by remember { mutableStateOf(initialTimestamp) }
    var notes by remember { mutableStateOf("") }

    var expandedContract by remember { mutableStateOf(false) }
    var expandedScooter by remember { mutableStateOf(false) }
    var expandedType by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState(initialSelectedDateMillis = timestamp)

    val scrollState = rememberScrollState()
    val typeOptions = remember {
        listOf(
            Transaction.TYPE_PAYMENT    to "To'lov",
            Transaction.TYPE_TERMINATED to "Tugatildi",
            Transaction.TYPE_RETURNED   to "Qaytarildi",
            Transaction.TYPE_PENALTY    to "Jarima",
            Transaction.TYPE_REPAIR     to "Ta'mir",
            Transaction.TYPE_CUSTOM     to "Boshqa"
        )
    }
    val selectedTypeLabel = typeOptions.find { it.first == type }?.second ?: "To'lov"

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextActionButton(
                    label = "Tanlash", icon = Icons.Default.Check,
                    onClick = {
                        datePickerState.selectedDateMillis?.let {
                            val timeOfDay = timestamp % dayMs
                            timestamp = it + timeOfDay
                        }
                        showDatePicker = false
                    }
                )
            },
            dismissButton = {
                TextActionButton(
                    label = "Bekor", icon = Icons.Default.Close,
                    onClick = { showDatePicker = false }
                )
            }
        ) { DatePicker(state = datePickerState) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Add, contentDescription = null, tint = ClaudeAccent)
                Spacer(Modifier.width(8.dp))
                Text("Yangi tranzaksiya", style = MaterialTheme.typography.titleLarge)
            }
        },
        containerColor = ClaudeCard,
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // ── Mijoz (зафиксирован) ─────────────────────────────────
                Text("Mijoz", style = MaterialTheme.typography.titleSmall,
                    color = ClaudeAccent, fontWeight = FontWeight.SemiBold)

                OutlinedTextField(
                    value = renterName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Mijoz ismi") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )
                OutlinedTextField(
                    value = renterPhone,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Telefon") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )

                HorizontalDivider(color = ClaudeDivider)

                // ── Skuter (выбор из списка) ─────────────────────────────
                Text("Skuter", style = MaterialTheme.typography.titleSmall,
                    color = ClaudeAccent, fontWeight = FontWeight.SemiBold)

                ExposedDropdownMenuBox(
                    expanded = expandedScooter,
                    onExpandedChange = { expandedScooter = !expandedScooter }
                ) {
                    OutlinedTextField(
                        value = scooterName.ifBlank { "Tanlanmagan" },
                        onValueChange = {
                            scooterName = it
                            scooterId = null
                        },
                        label = { Text("Skuter nomi") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedScooter)
                        },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                    ExposedDropdownMenu(
                        expanded = expandedScooter,
                        onDismissRequest = { expandedScooter = false }
                    ) {
                        allScooters.take(50).forEach { s ->
                            DropdownMenuItem(
                                text = { Text(s.name) },
                                onClick = {
                                    scooterId = s.id
                                    scooterName = s.name
                                    expandedScooter = false
                                }
                            )
                        }
                    }
                }

                HorizontalDivider(color = ClaudeDivider)

                // ── Kontrakt (только контракты этого арендатора) ────────
                Text("Kontrakt (ixtiyoriy)", style = MaterialTheme.typography.titleSmall,
                    color = ClaudeAccent, fontWeight = FontWeight.SemiBold)

                ExposedDropdownMenuBox(
                    expanded = expandedContract,
                    onExpandedChange = { expandedContract = !expandedContract }
                ) {
                    OutlinedTextField(
                        value = contractLabel.ifBlank { "Tanlanmagan" },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Kontrakt") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedContract)
                        },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                    ExposedDropdownMenu(
                        expanded = expandedContract,
                        onDismissRequest = { expandedContract = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Tanlanmagan (mustaqil tranzaksiya)") },
                            onClick = {
                                contractId = null
                                contractLabel = ""
                                expandedContract = false
                            }
                        )
                        renterContracts.take(50).forEach { c ->
                            DropdownMenuItem(
                                text = { Text(TransactionViewModel.formatContractLabel(c)) },
                                onClick = {
                                    contractId = c.id
                                    contractLabel = TransactionViewModel.formatContractLabel(c)
                                    // Подтягиваем скутер из контракта, если он задан
                                    if (c.scooterName != null) {
                                        scooterName = c.scooterName
                                        scooterId = allScooters.firstOrNull { it.name == c.scooterName }?.id
                                    }
                                    expandedContract = false
                                }
                            )
                        }
                    }
                }

                HorizontalDivider(color = ClaudeDivider)

                // ── Tranzaksiya ──────────────────────────────────────────
                Text("Tranzaksiya", style = MaterialTheme.typography.titleSmall,
                    color = ClaudeAccent, fontWeight = FontWeight.SemiBold)

                ExposedDropdownMenuBox(
                    expanded = expandedType,
                    onExpandedChange = { expandedType = !expandedType }
                ) {
                    OutlinedTextField(
                        value = selectedTypeLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Tur") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedType)
                        },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                    ExposedDropdownMenu(
                        expanded = expandedType,
                        onDismissRequest = { expandedType = false }
                    ) {
                        typeOptions.forEach { (t, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    type = t
                                    expandedType = false
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = dateTimeFmt.format(Date(timestamp)),
                    onValueChange = {},
                    label = { Text("Sana va vaqt") },
                    readOnly = true,
                    trailingIcon = {
                        IconButton(onClick = { showDatePicker = true }) {
                            Icon(Icons.Default.DateRange, contentDescription = "Sanani tanlash")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )

                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it.filter { ch -> ch.isDigit() || ch == '.' } },
                    label = { Text("Summa (UZS)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Izoh (ixtiyoriy)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )
            }
        },
        confirmButton = {
            PrimaryButton(
                label = "Yaratish",
                icon = Icons.Default.Add,
                // Все поля необязательны — кнопка всегда активна.
                // Раньше требовалось amount.isNotBlank() — убрано.
                enabled = true,
                onClick = {
                    val parsedAmount = amount.toDoubleOrNull() ?: 0.0
                    onCreate(
                        contractId, renterId, renterName, renterPhone,
                        scooterId, scooterName, contractLabel,
                        type, parsedAmount, timestamp,
                        notes.ifBlank { null }
                    )
                }
            )
        },
        dismissButton = {
            TextActionButton(
                label = "Bekor",
                icon = Icons.Default.Close,
                onClick = onDismiss
            )
        }
    )
}

/* ============================================================================
   ДИАЛОГ РЕДАКТИРОВАНИЯ ТРАНЗАКЦИИ (привязка к арендатору)
   ============================================================================ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditTransactionForRenterDialog(
    tx: Transaction,
    renterContracts: List<ContractHistoryEntry>,
    allScooters: List<Scooter>,
    onDismiss: () -> Unit,
    onSave: (Transaction) -> Unit,
    onDelete: () -> Unit
) {
    val dateTimeFmt = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
    val dayMs = 24L * 60 * 60 * 1000

    // Рентер зафиксирован — берём из tx
    val renterId = tx.renterId
    val renterName = tx.renterName
    val renterPhone = tx.renterPhone

    var contractId by remember { mutableStateOf(tx.contractId) }
    var contractLabel by remember { mutableStateOf(tx.contractLabel) }
    var scooterId by remember { mutableStateOf(tx.scooterId) }
    var scooterName by remember { mutableStateOf(tx.scooterName) }
    var type by remember { mutableStateOf(tx.type) }
    var amount by remember { mutableStateOf(tx.amount.toString()) }
    var timestamp by remember { mutableStateOf(tx.timestamp) }
    var notes by remember { mutableStateOf(tx.notes ?: "") }

    var expandedContract by remember { mutableStateOf(false) }
    var expandedScooter by remember { mutableStateOf(false) }
    var expandedType by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState(initialSelectedDateMillis = timestamp)

    val scrollState = rememberScrollState()
    val typeOptions = remember {
        listOf(
            Transaction.TYPE_PAYMENT    to "To'lov",
            Transaction.TYPE_TERMINATED to "Tugatildi",
            Transaction.TYPE_RETURNED   to "Qaytarildi",
            Transaction.TYPE_PENALTY    to "Jarima",
            Transaction.TYPE_REPAIR     to "Ta'mir",
            Transaction.TYPE_CUSTOM     to "Boshqa"
        )
    }
    val selectedTypeLabel = typeOptions.find { it.first == type }?.second ?: "To'lov"

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextActionButton(
                    label = "Tanlash", icon = Icons.Default.Check,
                    onClick = {
                        datePickerState.selectedDateMillis?.let {
                            val timeOfDay = timestamp % dayMs
                            timestamp = it + timeOfDay
                        }
                        showDatePicker = false
                    }
                )
            },
            dismissButton = {
                TextActionButton(
                    label = "Bekor", icon = Icons.Default.Close,
                    onClick = { showDatePicker = false }
                )
            }
        ) { DatePicker(state = datePickerState) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Edit, contentDescription = null, tint = ClaudeAccent)
                Spacer(Modifier.width(8.dp))
                Text("Tranzaksiya #${tx.id}", style = MaterialTheme.typography.titleLarge)
            }
        },
        containerColor = ClaudeCard,
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Mijoz (read-only)
                Text("Mijoz", style = MaterialTheme.typography.titleSmall,
                    color = ClaudeAccent, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = renterName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Mijoz ismi") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )
                OutlinedTextField(
                    value = renterPhone,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Telefon") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )

                HorizontalDivider(color = ClaudeDivider)

                Text("Skuter", style = MaterialTheme.typography.titleSmall,
                    color = ClaudeAccent, fontWeight = FontWeight.SemiBold)
                ExposedDropdownMenuBox(
                    expanded = expandedScooter,
                    onExpandedChange = { expandedScooter = !expandedScooter }
                ) {
                    OutlinedTextField(
                        value = scooterName.ifBlank { "Tanlanmagan" },
                        onValueChange = {
                            scooterName = it
                            scooterId = null
                        },
                        label = { Text("Skuter nomi") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedScooter)
                        },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                    ExposedDropdownMenu(
                        expanded = expandedScooter,
                        onDismissRequest = { expandedScooter = false }
                    ) {
                        allScooters.take(50).forEach { s ->
                            DropdownMenuItem(
                                text = { Text(s.name) },
                                onClick = {
                                    scooterId = s.id
                                    scooterName = s.name
                                    expandedScooter = false
                                }
                            )
                        }
                    }
                }

                HorizontalDivider(color = ClaudeDivider)

                Text("Kontrakt", style = MaterialTheme.typography.titleSmall,
                    color = ClaudeAccent, fontWeight = FontWeight.SemiBold)
                ExposedDropdownMenuBox(
                    expanded = expandedContract,
                    onExpandedChange = { expandedContract = !expandedContract }
                ) {
                    OutlinedTextField(
                        value = contractLabel.ifBlank { "Tanlanmagan" },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Kontrakt") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedContract)
                        },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                    ExposedDropdownMenu(
                        expanded = expandedContract,
                        onDismissRequest = { expandedContract = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Tanlanmagan (mustaqil tranzaksiya)") },
                            onClick = {
                                contractId = null
                                contractLabel = ""
                                expandedContract = false
                            }
                        )
                        renterContracts.take(50).forEach { c ->
                            DropdownMenuItem(
                                text = { Text(TransactionViewModel.formatContractLabel(c)) },
                                onClick = {
                                    contractId = c.id
                                    contractLabel = TransactionViewModel.formatContractLabel(c)
                                    if (c.scooterName != null) {
                                        scooterName = c.scooterName
                                        scooterId = allScooters.firstOrNull { it.name == c.scooterName }?.id
                                    }
                                    expandedContract = false
                                }
                            )
                        }
                    }
                }

                HorizontalDivider(color = ClaudeDivider)

                Text("Tranzaksiya", style = MaterialTheme.typography.titleSmall,
                    color = ClaudeAccent, fontWeight = FontWeight.SemiBold)
                ExposedDropdownMenuBox(
                    expanded = expandedType,
                    onExpandedChange = { expandedType = !expandedType }
                ) {
                    OutlinedTextField(
                        value = selectedTypeLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Tur") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedType)
                        },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                    ExposedDropdownMenu(
                        expanded = expandedType,
                        onDismissRequest = { expandedType = false }
                    ) {
                        typeOptions.forEach { (t, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    type = t
                                    expandedType = false
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = dateTimeFmt.format(Date(timestamp)),
                    onValueChange = {},
                    label = { Text("Sana va vaqt") },
                    readOnly = true,
                    trailingIcon = {
                        IconButton(onClick = { showDatePicker = true }) {
                            Icon(Icons.Default.DateRange, contentDescription = "Sanani tanlash")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )

                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it.filter { ch -> ch.isDigit() || ch == '.' } },
                    label = { Text("Summa (UZS)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Izoh (ixtiyoriy)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )
            }
        },
        confirmButton = {
            PrimaryButton(
                label = "Saqla",
                icon = Icons.Default.Save,
                onClick = {
                    val parsedAmount = amount.toDoubleOrNull() ?: 0.0
                    onSave(tx.copy(
                        contractId = contractId,
                        renterId = renterId,
                        renterName = renterName,
                        renterPhone = renterPhone,
                        scooterId = scooterId,
                        scooterName = scooterName,
                        contractLabel = contractLabel,
                        type = type,
                        amount = parsedAmount,
                        timestamp = timestamp,
                        notes = notes.ifBlank { null }
                    ))
                }
            )
        },
        dismissButton = {
            Row {
                TextActionButton(
                    label = "O'chir",
                    icon = Icons.Default.Delete,
                    onClick = onDelete
                )
                Spacer(Modifier.width(8.dp))
                TextActionButton(
                    label = "Bekor",
                    icon = Icons.Default.Close,
                    onClick = onDismiss
                )
            }
        }
    )
}

/* ============================================================================
   ДИАЛОГ СОЗДАНИЯ ТРАНЗАКЦИИ (привязка к скутеру)
   ============================================================================

   Скутер зафиксирован. Рентер выбирается из списка всех арендаторов
   (или из тех, кто арендует/арендовал этот скутер). Контракт выбирается
   только из контрактов этого скутера.
   ============================================================================ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateTransactionForScooterDialog(
    scooter: Scooter,
    scooterContracts: List<ContractHistoryEntry>,
    renters: List<Renter>,
    onDismiss: () -> Unit,
    onCreate: (
        contractId: Int?, renterId: Int,
        renterName: String, renterPhone: String,
        scooterId: Int?, scooterName: String, contractLabel: String,
        type: String, amount: Double, timestamp: Long, notes: String?
    ) -> Unit
) {
    val dateTimeFmt = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
    val dayMs = 24L * 60 * 60 * 1000
    val initialTimestamp = (System.currentTimeMillis() / (15 * 60 * 1000)) * (15 * 60 * 1000)

    // Скутер зафиксирован
    val scooterId: Int? = scooter.id
    val scooterName = scooter.name

    var contractId by remember { mutableStateOf<Int?>(null) }
    var contractLabel by remember { mutableStateOf("") }
    var renterId by remember { mutableStateOf(0) }
    var renterName by remember { mutableStateOf("") }
    var renterPhone by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(Transaction.TYPE_PAYMENT) }
    var amount by remember { mutableStateOf("") }
    var timestamp by remember { mutableStateOf(initialTimestamp) }
    var notes by remember { mutableStateOf("") }

    var expandedContract by remember { mutableStateOf(false) }
    var expandedRenter by remember { mutableStateOf(false) }
    var expandedType by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState(initialSelectedDateMillis = timestamp)

    val scrollState = rememberScrollState()
    val typeOptions = remember {
        listOf(
            Transaction.TYPE_PAYMENT    to "To'lov",
            Transaction.TYPE_TERMINATED to "Tugatildi",
            Transaction.TYPE_RETURNED   to "Qaytarildi",
            Transaction.TYPE_PENALTY    to "Jarima",
            Transaction.TYPE_REPAIR     to "Ta'mir",
            Transaction.TYPE_CUSTOM     to "Boshqa"
        )
    }
    val selectedTypeLabel = typeOptions.find { it.first == type }?.second ?: "To'lov"

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextActionButton(
                    label = "Tanlash", icon = Icons.Default.Check,
                    onClick = {
                        datePickerState.selectedDateMillis?.let {
                            val timeOfDay = timestamp % dayMs
                            timestamp = it + timeOfDay
                        }
                        showDatePicker = false
                    }
                )
            },
            dismissButton = {
                TextActionButton(
                    label = "Bekor", icon = Icons.Default.Close,
                    onClick = { showDatePicker = false }
                )
            }
        ) { DatePicker(state = datePickerState) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Add, contentDescription = null, tint = ClaudeAccent)
                Spacer(Modifier.width(8.dp))
                Text("Yangi tranzaksiya", style = MaterialTheme.typography.titleLarge)
            }
        },
        containerColor = ClaudeCard,
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // ── Skuter (зафиксирован) ────────────────────────────────
                Text("Skuter", style = MaterialTheme.typography.titleSmall,
                    color = ClaudeAccent, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = scooterName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Skuter nomi") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )

                HorizontalDivider(color = ClaudeDivider)

                // ── Mijoz (выбор из списка) ──────────────────────────────
                Text("Mijoz", style = MaterialTheme.typography.titleSmall,
                    color = ClaudeAccent, fontWeight = FontWeight.SemiBold)

                ExposedDropdownMenuBox(
                    expanded = expandedRenter,
                    onExpandedChange = { expandedRenter = !expandedRenter }
                ) {
                    OutlinedTextField(
                        value = renterName,
                        onValueChange = { renterName = it },
                        label = { Text("Mijoz ismi") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedRenter)
                        },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                    ExposedDropdownMenu(
                        expanded = expandedRenter,
                        onDismissRequest = { expandedRenter = false }
                    ) {
                        renters.take(50).forEach { r ->
                            DropdownMenuItem(
                                text = { Text("${r.name}  •  ${r.phoneNumber}") },
                                onClick = {
                                    renterId = r.id
                                    renterName = r.name
                                    renterPhone = r.phoneNumber
                                    expandedRenter = false
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = renterPhone,
                    onValueChange = { renterPhone = it },
                    label = { Text("Telefon") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )

                HorizontalDivider(color = ClaudeDivider)

                // ── Kontrakt (только контракты этого скутера) ────────────
                Text("Kontrakt (ixtiyoriy)", style = MaterialTheme.typography.titleSmall,
                    color = ClaudeAccent, fontWeight = FontWeight.SemiBold)

                ExposedDropdownMenuBox(
                    expanded = expandedContract,
                    onExpandedChange = { expandedContract = !expandedContract }
                ) {
                    OutlinedTextField(
                        value = contractLabel.ifBlank { "Tanlanmagan" },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Kontrakt") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedContract)
                        },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                    ExposedDropdownMenu(
                        expanded = expandedContract,
                        onDismissRequest = { expandedContract = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Tanlanmagan (mustaqil tranzaksiya)") },
                            onClick = {
                                contractId = null
                                contractLabel = ""
                                expandedContract = false
                            }
                        )
                        scooterContracts.take(50).forEach { c ->
                            DropdownMenuItem(
                                text = { Text(TransactionViewModel.formatContractLabel(c)) },
                                onClick = {
                                    contractId = c.id
                                    contractLabel = TransactionViewModel.formatContractLabel(c)
                                    // Подтягиваем рентера из контракта
                                    renterId = c.renterId
                                    renterName = c.renterName
                                    renterPhone = c.renterPhone
                                    expandedContract = false
                                }
                            )
                        }
                    }
                }

                HorizontalDivider(color = ClaudeDivider)

                // ── Tranzaksiya ──────────────────────────────────────────
                Text("Tranzaksiya", style = MaterialTheme.typography.titleSmall,
                    color = ClaudeAccent, fontWeight = FontWeight.SemiBold)

                ExposedDropdownMenuBox(
                    expanded = expandedType,
                    onExpandedChange = { expandedType = !expandedType }
                ) {
                    OutlinedTextField(
                        value = selectedTypeLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Tur") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedType)
                        },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                    ExposedDropdownMenu(
                        expanded = expandedType,
                        onDismissRequest = { expandedType = false }
                    ) {
                        typeOptions.forEach { (t, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    type = t
                                    expandedType = false
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = dateTimeFmt.format(Date(timestamp)),
                    onValueChange = {},
                    label = { Text("Sana va vaqt") },
                    readOnly = true,
                    trailingIcon = {
                        IconButton(onClick = { showDatePicker = true }) {
                            Icon(Icons.Default.DateRange, contentDescription = "Sanani tanlash")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )

                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it.filter { ch -> ch.isDigit() || ch == '.' } },
                    label = { Text("Summa (UZS)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Izoh (ixtiyoriy)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )
            }
        },
        confirmButton = {
            PrimaryButton(
                label = "Yaratish",
                icon = Icons.Default.Add,
                // Все поля необязательны — кнопка всегда активна.
                // Раньше требовалось renterName.isNotBlank() && amount.isNotBlank() — убрано.
                enabled = true,
                onClick = {
                    val parsedAmount = amount.toDoubleOrNull() ?: 0.0
                    onCreate(
                        contractId, renterId, renterName, renterPhone,
                        scooterId, scooterName, contractLabel,
                        type, parsedAmount, timestamp,
                        notes.ifBlank { null }
                    )
                }
            )
        },
        dismissButton = {
            TextActionButton(
                label = "Bekor",
                icon = Icons.Default.Close,
                onClick = onDismiss
            )
        }
    )
}

/* ============================================================================
   ДИАЛОГ РЕДАКТИРОВАНИЯ ТРАНЗАКЦИИ (привязка к скутеру)
   ============================================================================ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditTransactionForScooterDialog(
    tx: Transaction,
    scooter: Scooter,
    scooterContracts: List<ContractHistoryEntry>,
    renters: List<Renter>,
    onDismiss: () -> Unit,
    onSave: (Transaction) -> Unit,
    onDelete: () -> Unit
) {
    val dateTimeFmt = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
    val dayMs = 24L * 60 * 60 * 1000

    // Скутер зафиксирован
    val scooterId: Int? = scooter.id
    val scooterName = scooter.name

    var contractId by remember { mutableStateOf(tx.contractId) }
    var contractLabel by remember { mutableStateOf(tx.contractLabel) }
    var renterId by remember { mutableStateOf(tx.renterId) }
    var renterName by remember { mutableStateOf(tx.renterName) }
    var renterPhone by remember { mutableStateOf(tx.renterPhone) }
    var type by remember { mutableStateOf(tx.type) }
    var amount by remember { mutableStateOf(tx.amount.toString()) }
    var timestamp by remember { mutableStateOf(tx.timestamp) }
    var notes by remember { mutableStateOf(tx.notes ?: "") }

    var expandedContract by remember { mutableStateOf(false) }
    var expandedRenter by remember { mutableStateOf(false) }
    var expandedType by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState(initialSelectedDateMillis = timestamp)

    val scrollState = rememberScrollState()
    val typeOptions = remember {
        listOf(
            Transaction.TYPE_PAYMENT    to "To'lov",
            Transaction.TYPE_TERMINATED to "Tugatildi",
            Transaction.TYPE_RETURNED   to "Qaytarildi",
            Transaction.TYPE_PENALTY    to "Jarima",
            Transaction.TYPE_REPAIR     to "Ta'mir",
            Transaction.TYPE_CUSTOM     to "Boshqa"
        )
    }
    val selectedTypeLabel = typeOptions.find { it.first == type }?.second ?: "To'lov"

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextActionButton(
                    label = "Tanlash", icon = Icons.Default.Check,
                    onClick = {
                        datePickerState.selectedDateMillis?.let {
                            val timeOfDay = timestamp % dayMs
                            timestamp = it + timeOfDay
                        }
                        showDatePicker = false
                    }
                )
            },
            dismissButton = {
                TextActionButton(
                    label = "Bekor", icon = Icons.Default.Close,
                    onClick = { showDatePicker = false }
                )
            }
        ) { DatePicker(state = datePickerState) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Edit, contentDescription = null, tint = ClaudeAccent)
                Spacer(Modifier.width(8.dp))
                Text("Tranzaksiya #${tx.id}", style = MaterialTheme.typography.titleLarge)
            }
        },
        containerColor = ClaudeCard,
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Skuter", style = MaterialTheme.typography.titleSmall,
                    color = ClaudeAccent, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = scooterName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Skuter nomi") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )

                HorizontalDivider(color = ClaudeDivider)

                Text("Mijoz", style = MaterialTheme.typography.titleSmall,
                    color = ClaudeAccent, fontWeight = FontWeight.SemiBold)
                ExposedDropdownMenuBox(
                    expanded = expandedRenter,
                    onExpandedChange = { expandedRenter = !expandedRenter }
                ) {
                    OutlinedTextField(
                        value = renterName,
                        onValueChange = { renterName = it },
                        label = { Text("Mijoz ismi") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedRenter)
                        },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                    ExposedDropdownMenu(
                        expanded = expandedRenter,
                        onDismissRequest = { expandedRenter = false }
                    ) {
                        renters.take(50).forEach { r ->
                            DropdownMenuItem(
                                text = { Text("${r.name}  •  ${r.phoneNumber}") },
                                onClick = {
                                    renterId = r.id
                                    renterName = r.name
                                    renterPhone = r.phoneNumber
                                    expandedRenter = false
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = renterPhone,
                    onValueChange = { renterPhone = it },
                    label = { Text("Telefon") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )

                HorizontalDivider(color = ClaudeDivider)

                Text("Kontrakt", style = MaterialTheme.typography.titleSmall,
                    color = ClaudeAccent, fontWeight = FontWeight.SemiBold)
                ExposedDropdownMenuBox(
                    expanded = expandedContract,
                    onExpandedChange = { expandedContract = !expandedContract }
                ) {
                    OutlinedTextField(
                        value = contractLabel.ifBlank { "Tanlanmagan" },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Kontrakt") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedContract)
                        },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                    ExposedDropdownMenu(
                        expanded = expandedContract,
                        onDismissRequest = { expandedContract = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Tanlanmagan (mustaqil tranzaksiya)") },
                            onClick = {
                                contractId = null
                                contractLabel = ""
                                expandedContract = false
                            }
                        )
                        scooterContracts.take(50).forEach { c ->
                            DropdownMenuItem(
                                text = { Text(TransactionViewModel.formatContractLabel(c)) },
                                onClick = {
                                    contractId = c.id
                                    contractLabel = TransactionViewModel.formatContractLabel(c)
                                    renterId = c.renterId
                                    renterName = c.renterName
                                    renterPhone = c.renterPhone
                                    expandedContract = false
                                }
                            )
                        }
                    }
                }

                HorizontalDivider(color = ClaudeDivider)

                Text("Tranzaksiya", style = MaterialTheme.typography.titleSmall,
                    color = ClaudeAccent, fontWeight = FontWeight.SemiBold)
                ExposedDropdownMenuBox(
                    expanded = expandedType,
                    onExpandedChange = { expandedType = !expandedType }
                ) {
                    OutlinedTextField(
                        value = selectedTypeLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Tur") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedType)
                        },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                    ExposedDropdownMenu(
                        expanded = expandedType,
                        onDismissRequest = { expandedType = false }
                    ) {
                        typeOptions.forEach { (t, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    type = t
                                    expandedType = false
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = dateTimeFmt.format(Date(timestamp)),
                    onValueChange = {},
                    label = { Text("Sana va vaqt") },
                    readOnly = true,
                    trailingIcon = {
                        IconButton(onClick = { showDatePicker = true }) {
                            Icon(Icons.Default.DateRange, contentDescription = "Sanani tanlash")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )

                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it.filter { ch -> ch.isDigit() || ch == '.' } },
                    label = { Text("Summa (UZS)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Izoh (ixtiyoriy)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )
            }
        },
        confirmButton = {
            PrimaryButton(
                label = "Saqla",
                icon = Icons.Default.Save,
                // Все поля необязательны — кнопка всегда активна.
                // Раньше требовалось renterName.isNotBlank() — убрано.
                enabled = true,
                onClick = {
                    val parsedAmount = amount.toDoubleOrNull() ?: 0.0
                    onSave(tx.copy(
                        contractId = contractId,
                        renterId = renterId,
                        renterName = renterName,
                        renterPhone = renterPhone,
                        scooterId = scooterId,
                        scooterName = scooterName,
                        contractLabel = contractLabel,
                        type = type,
                        amount = parsedAmount,
                        timestamp = timestamp,
                        notes = notes.ifBlank { null }
                    ))
                }
            )
        },
        dismissButton = {
            Row {
                TextActionButton(
                    label = "O'chir",
                    icon = Icons.Default.Delete,
                    onClick = onDelete
                )
                Spacer(Modifier.width(8.dp))
                TextActionButton(
                    label = "Bekor",
                    icon = Icons.Default.Close,
                    onClick = onDismiss
                )
            }
        }
    )
}
