package com.example

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.CardTransaction
import com.example.data.VirtualCard
import com.example.ui.FinansiViewModel
import com.example.ui.components.*
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* ============================================================================
   ЭКРАН ИСТОРИИ ТРАНЗАКЦИЙ ВИРТУАЛЬНОЙ КАРТЫ
   ----------------------------------------------------------------------------
   Шаблон — RenterContractHistoryScreen. Структура:
     • TopAppBar с именем карты, балансом, кнопкой «Назад» и «Редактировать»
     • Сводка (цветная полоса + баланс + кол-во транзакций + всего получено/потрачено)
     • Переключатель «Kiruvchi» (входящие) / «Chiquvchi» (исходящие)
     • Поиск
     • Панель действий (Yaratish / Tahrirlash / O'chir)
     • Список транзакций с цветными индикаторами:
         зелёный — приход на эту карту
         красный — расход с этой карты
   ============================================================================ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardTransactionHistoryScreen(
    card: VirtualCard,
    onBack: () -> Unit,
    onEditCard: () -> Unit,
    finansiViewModel: FinansiViewModel = viewModel()
) {
    val context = LocalContext.current
    val allCards by finansiViewModel.cards.collectAsStateWithLifecycle()
    val transactions by finansiViewModel.transactionsForCard(card.id)
        .collectAsStateWithLifecycle(initialValue = emptyList())

    // Свежий объект карты (баланс мог измениться)
    val currentCard = allCards.firstOrNull { it.id == card.id } ?: card

    // ── Toggle: 0 = Kiruvchi (входящие), 1 = Chiquvchi (исходящие) ──────
    var selectedTab by remember { mutableStateOf(0) }
    var selectedTxIds by remember { mutableStateOf(setOf<Int>()) }
    var searchQuery by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val dateFmt = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }

    // Карта id → имя (для отображения контрагента)
    val cardNameById = remember(allCards) {
        allCards.associate { it.id to it.name }
    }

    fun cardName(id: Int): String = when (id) {
        CardTransaction.EXTERNAL_SOURCE_ID -> "Kontrakt"
        currentCard.id -> "Bu karta"
        else -> cardNameById[id] ?: "Karta #$id"
    }

    // Разделяем на входящие (toCardId == currentCard.id) и исходящие (fromCardId == currentCard.id)
    val incoming = remember(transactions) {
        transactions.filter { it.toCardId == currentCard.id }
    }
    val outgoing = remember(transactions) {
        transactions.filter { it.fromCardId == currentCard.id }
    }

    val totalIncoming = incoming.sumOf { it.amount }
    val totalOutgoing = outgoing.sumOf { it.amount }

    val currentList = if (selectedTab == 0) incoming else outgoing

    val filteredTxs = remember(currentList, searchQuery) {
        currentList.filter { t ->
            searchQuery.isBlank() ||
                cardName(t.fromCardId).contains(searchQuery, ignoreCase = true) ||
                cardName(t.toCardId).contains(searchQuery, ignoreCase = true) ||
                (t.note?.contains(searchQuery, ignoreCase = true) == true) ||
                t.amount.toLong().toString().contains(searchQuery) ||
                t.id.toString().contains(searchQuery)
        }
    }

    Scaffold(
        containerColor = ClaudeBackground,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            currentCard.name,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            "Balans: ${formatMoney(currentCard.balance)} so'm",
                            style = MaterialTheme.typography.labelSmall,
                            color = ClaudeTextSecondary
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Orqaga")
                    }
                },
                actions = {
                    IconButton(onClick = onEditCard) {
                        Icon(Icons.Default.Edit, contentDescription = "Kartani tahrirlash")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ClaudeCard,
                    titleContentColor = ClaudeText,
                    navigationIconContentColor = ClaudeText,
                    actionIconContentColor = ClaudeAccent
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            // ── Сводка по карте ────────────────────────────────────────────
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = ClaudeAccentBg),
                border = BorderStroke(1.dp, ClaudeAccentMuted)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    SummaryColumn("Kiruvchi", "${formatMoney(totalIncoming)}", StatusOk)
                    SummaryColumn("Chiquvchi", "${formatMoney(totalOutgoing)}", StatusOverdue)
                    SummaryColumn(
                        "Balans",
                        "${formatMoney(currentCard.balance)}",
                        if (currentCard.balance < 0) StatusOverdue else StatusOk
                    )
                    SummaryColumn("Tranzaksiya", "${incoming.size + outgoing.size}")
                }
            }

            // ── Переключатель «Kiruvchi» / «Chiquvchi» ───────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CardTabButton(
                    label = "Kiruvchi (${incoming.size})",
                    icon = Icons.Default.ArrowDownward,
                    isSelected = selectedTab == 0,
                    onClick = {
                        selectedTab = 0
                        selectedTxIds = emptySet()
                    }
                )
                CardTabButton(
                    label = "Chiquvchi (${outgoing.size})",
                    icon = Icons.Default.ArrowUpward,
                    isSelected = selectedTab == 1,
                    onClick = {
                        selectedTab = 1
                        selectedTxIds = emptySet()
                    }
                )
            }

            // ── Поиск ───────────────────────────────────────────────────
            UnifiedSearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                placeholder = "Tranzaksiya qidirish...",
                onCalendarClick = null,
                onFilterClick = null
            )

            // ── Панель действий ────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SecondaryButton(
                    label = "Tahrirlash",
                    icon = Icons.Default.Edit,
                    enabled = false,
                    onClick = {}
                )
                DangerButton(
                    label = "O'chir",
                    icon = Icons.Default.Delete,
                    enabled = selectedTxIds.isNotEmpty(),
                    onClick = { showDeleteConfirm = true }
                )
                Spacer(Modifier.weight(1f))
            }

            // ── Список транзакций ──────────────────────────────────────
            if (filteredTxs.isEmpty()) {
                EmptyStateBox(
                    text = if (selectedTab == 0)
                        "Kiruvchi tranzaksiyalar yo'q"
                    else
                        "Chiquvchi tranzaksiyalar yo'q"
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredTxs, key = { it.id }) { tx ->
                        CardTxRow(
                            tx = tx,
                            isIncoming = selectedTab == 0,
                            currentCardId = currentCard.id,
                            counterpartyName = if (selectedTab == 0)
                                cardName(tx.fromCardId)
                            else
                                cardName(tx.toCardId),
                            isSelected = tx.id in selectedTxIds,
                            dateFmt = dateFmt,
                            onClick = {
                                selectedTxIds = if (tx.id in selectedTxIds) {
                                    selectedTxIds - tx.id
                                } else {
                                    selectedTxIds + tx.id
                                }
                            }
                        )
                    }
                    item {
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Tranzaksiyalarni o'chirish") },
            text = {
                Text(
                    "${selectedTxIds.size} ta tranzaksiyani o'chirmoqchimisz? " +
                    "Bu amal balanslarga ta'sir qilmaydi — faqat tarix yozuvi o'chiriladi."
                )
            },
            confirmButton = {
                DangerButton(
                    label = "O'chir",
                    icon = Icons.Default.Delete,
                    onClick = {
                        selectedTxIds.forEach { id ->
                            finansiViewModel.deleteTransaction(id)
                        }
                        Toast.makeText(
                            context,
                            "${selectedTxIds.size} ta o'chirildi",
                            Toast.LENGTH_SHORT
                        ).show()
                        selectedTxIds = emptySet()
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
}

/* ── Вспомогательные компоненты ───────────────────────────────────────── */

@Composable
private fun SummaryColumn(label: String, value: String, valueColor: Color = ClaudeText) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = ClaudeTextSecondary
        )
        Spacer(Modifier.height(2.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Composable
private fun RowScope.CardTabButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .weight(1f)
            .height(44.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = if (isSelected) ClaudeAccent else ClaudeCard,
        border = BorderStroke(
            1.dp,
            if (isSelected) ClaudeAccent else ClaudeDivider
        )
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (isSelected) Color.White else ClaudeTextSecondary
            )
            Spacer(Modifier.width(6.dp))
            Text(
                label,
                color = if (isSelected) Color.White else ClaudeText,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = 13.sp,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun CardTxRow(
    tx: CardTransaction,
    isIncoming: Boolean,
    currentCardId: Int,
    counterpartyName: String,
    isSelected: Boolean,
    dateFmt: SimpleDateFormat,
    onClick: () -> Unit
) {
    val accentColor = if (isIncoming) StatusOk else StatusOverdue
    val sign = if (isIncoming) "+" else "−"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) ClaudeAccentBg else ClaudeCard
        ),
        border = if (isSelected) BorderStroke(2.dp, ClaudeAccent)
                 else BorderStroke(1.dp, ClaudeDivider)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Цветной индикатор направления
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(accentColor.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isIncoming) Icons.Default.ArrowDownward
                                  else Icons.Default.ArrowUpward,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(10.dp))

            // Контрагент + дата + заметка
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = counterpartyName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = ClaudeText,
                    maxLines = 1
                )
                Text(
                    text = dateFmt.format(Date(tx.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = ClaudeTextSecondary
                )
                if (!tx.note.isNullOrBlank()) {
                    Text(
                        text = tx.note,
                        style = MaterialTheme.typography.labelSmall,
                        color = ClaudeTextSecondary,
                        maxLines = 2
                    )
                }
            }

            // Сумма
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "$sign ${formatMoney(tx.amount)}",
                    color = accentColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    textAlign = TextAlign.End
                )
                Text(
                    text = "so'm",
                    style = MaterialTheme.typography.labelSmall,
                    color = ClaudeTextSecondary,
                    textAlign = TextAlign.End
                )
            }
        }
    }
}

@Composable
private fun EmptyStateBox(text: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.List,
                contentDescription = null,
                tint = ClaudeTextSecondary.copy(alpha = 0.5f),
                modifier = Modifier.size(48.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = text,
                color = ClaudeTextSecondary,
                fontSize = 14.sp
            )
        }
    }
}

private fun formatMoney(amount: Double): String {
    val sign = if (amount < 0) "-" else ""
    val absValue = kotlin.math.abs(amount).toLong()
    val formatted = String.format(Locale.US, "%,d", absValue).replace(',', ' ')
    return "$sign$formatted"
}
