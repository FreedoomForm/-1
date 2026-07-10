package com.example

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.VirtualCard
import com.example.ui.FinansiViewModel
import com.example.ui.theme.ClaudeAccent
import com.example.ui.theme.ClaudeBackground
import com.example.ui.theme.ClaudeCard
import com.example.ui.theme.ClaudeDivider
import com.example.ui.theme.ClaudeText
import com.example.ui.theme.ClaudeTextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Парсит hex-строку вида "#FF1565C0" или "#1565C0" в Compose Color.
 */
private fun parseColorHex(hex: String): Color {
    return try {
        val normalized = hex.removePrefix("#")
        val full = if (normalized.length == 6) "FF$normalized" else normalized
        Color(full.toLong(16))
    } catch (_: Exception) {
        Color(0xFF1565C0)
    }
}

/**
 * Форматирует сумму в "1 250 000" или "-350 000".
 */
private fun formatMoney(amount: Double): String {
    val sign = if (amount < 0) "-" else ""
    val absValue = kotlin.math.abs(amount).toLong()
    val formatted = String.format(Locale.US, "%,d", absValue).replace(',', ' ')
    return "$sign$formatted"
}

/**
 * Визуальная виртуальная карта — выглядит как настоящая банковская карта.
 *
 * Поверх цвета фона добавлена:
 *   • диагональная градиентная текстура (эффект пластика)
 *   • тонкая светлая полоса-чип (как на реальных картах)
 *   • текстура из точек/линий для имитации рельефа
 *
 * [selected] подсвечивает карту рамкой (для зоны выбора транзакции).
 * [onClick] / [onLongClick] поддерживают tap-to-edit и long-press multi-select.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VirtualCardView(
    card: VirtualCard,
    selected: Boolean = false,
    isSlot: Boolean = false,           // true если это слот «выберите карту»
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {}
) {
    val baseColor = parseColorHex(card.colorHex)
    val cardShape = RoundedCornerShape(16.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .clip(cardShape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .then(
                if (selected) Modifier.border(3.dp, ClaudeAccent, cardShape)
                else if (isSlot) Modifier.border(2.dp, ClaudeTextSecondary.copy(alpha = 0.5f), cardShape)
                else Modifier
            )
    ) {
        // ── Фон: цвет карты + диагональный градиент-текстура ──
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            baseColor.copy(alpha = 0.85f),
                            baseColor,
                            baseColor.copy(alpha = 0.7f)
                        ),
                        start = Offset(0f, 0f),
                        end = Offset(1000f, 600f)
                    )
                )
        )

        // ── Текстура: тонкие диагональные линии поверх цвета ──
        Canvas(
            modifier = Modifier.fillMaxSize()
        ) {
            val stripeColor = Color.White.copy(alpha = 0.06f)
            var x = -size.height
            while (x < size.width) {
                drawLine(
                    color = stripeColor,
                    start = Offset(x, 0f),
                    end = Offset(x + size.height, size.height),
                    strokeWidth = 1.5f
                )
                x += 18f
            }
        }

        // ── Чип-имитация (жёлтый прямоугольник как на реальных картах) ──
        Box(
            modifier = Modifier
                .padding(start = 16.dp, top = 56.dp)
                .size(width = 36.dp, height = 26.dp)
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color(0xFFFFD54F),
                            Color(0xFFFFA000),
                            Color(0xFFFFD54F)
                        )
                    ),
                    shape = RoundedCornerShape(4.dp)
                )
        )

        // ── Контент ──
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Верх: имя карты + метка системной
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = card.name,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    modifier = Modifier.weight(1f),
                    maxLines = 1
                )
                if (card.isDefault) {
                    Box(
                        modifier = Modifier
                            .background(
                                Color.White.copy(alpha = 0.2f),
                                RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "ASOSIY",
                            color = Color.White,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Центр: баланс
            Column {
                Text(
                    text = "Balans",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 11.sp
                )
                Text(
                    text = "${formatMoney(card.balance)} so'm",
                    color = if (card.balance < 0) Color(0xFFFFD7D7) else Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Низ: инфо + иконка
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = card.info ?: "",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f),
                    maxLines = 1
                )
                Icon(
                    imageVector = if (selected) Icons.Default.Edit else Icons.Default.SwapHoriz,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/**
 * Пустой слот «выберите карту» — используется в зоне транзакции,
 * когда пользователь ещё не перетащил карту в слот.
 */
@Composable
fun EmptyCardSlot(label: String, onClick: () -> Unit = {}) {
    val cardShape = RoundedCornerShape(16.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .clip(cardShape)
            .border(
                2.dp,
                ClaudeTextSecondary.copy(alpha = 0.4f),
                cardShape
            )
            .background(ClaudeCard.copy(alpha = 0.5f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                tint = ClaudeTextSecondary,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                color = ClaudeTextSecondary,
                fontSize = 12.sp
            )
        }
    }
}

/**
 * Зона транзакции: card1 → [сумма + инфо] → card2.
 *
 * Пользователь выбирает две карты (тап по слоту → открывается выбор),
 * вводит сумму и нажимает «O'tkazish» (прямой перевод) либо
 * кнопку-стрелку ← (обратный перевод).
 */
@Composable
fun TransactionZone(
    cards: List<VirtualCard>,
    fromCard: VirtualCard?,
    toCard: VirtualCard?,
    onPickFrom: () -> Unit,
    onPickTo: () -> Unit,
    onTransfer: (amount: Double, note: String?, reversed: Boolean) -> Unit
) {
    var amountText by remember { mutableStateOf("") }
    var noteText by remember { mutableStateOf("") }
    var reversed by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = ClaudeCard),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, ClaudeDivider)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "O'tkazma",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = ClaudeText
            )

            // ── Две карты + стрелка между ними ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Карта-источник (или слот)
                Box(modifier = Modifier.weight(1f)) {
                    if (fromCard != null) {
                        VirtualCardView(
                            card = fromCard,
                            selected = true,
                            onClick = onPickFrom
                        )
                    } else {
                        EmptyCardSlot(label = "Manba karta", onClick = onPickFrom)
                    }
                }

                // Стрелка посередине
                IconButton(
                    onClick = { reversed = !reversed },
                    modifier = Modifier
                        .size(40.dp)
                        .background(ClaudeAccent, CircleShape)
                ) {
                    Icon(
                        imageVector = if (reversed) Icons.Default.ArrowBack
                                      else Icons.Default.ArrowForward,
                        contentDescription = "Yo'nalishni o'zgartirish",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Карта-назначение (или слот)
                Box(modifier = Modifier.weight(1f)) {
                    if (toCard != null) {
                        VirtualCardView(
                            card = toCard,
                            selected = true,
                            onClick = onPickTo
                        )
                    } else {
                        EmptyCardSlot(label = "Maqsad karta", onClick = onPickTo)
                    }
                }
            }

            // ── Поле суммы (обязательное) ──
            OutlinedTextField(
                value = amountText,
                onValueChange = { s -> amountText = s.filter { it.isDigit() || it == '.' } },
                label = { Text("Summa (so'm) *") },
                placeholder = { Text("0") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = ClaudeDivider,
                    focusedBorderColor = ClaudeAccent,
                    unfocusedContainerColor = ClaudeBackground,
                    focusedContainerColor = ClaudeBackground
                )
            )

            // ── Поле примечания (необязательное) ──
            OutlinedTextField(
                value = noteText,
                onValueChange = { noteText = it },
                label = { Text("Izoh (ixtiyoriy)") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = ClaudeDivider,
                    focusedBorderColor = ClaudeAccent,
                    unfocusedContainerColor = ClaudeBackground,
                    focusedContainerColor = ClaudeBackground
                )
            )

            // ── Кнопка перевода ──
            Button(
                onClick = {
                    val amount = amountText.replace(',', '.').toDoubleOrNull()
                    if (amount != null && amount > 0.0 && fromCard != null && toCard != null) {
                        onTransfer(amount, noteText.ifBlank { null }, reversed)
                        amountText = ""
                        noteText = ""
                    }
                },
                enabled = fromCard != null && toCard != null &&
                          amountText.replace(',', '.').toDoubleOrNull()?.let { it > 0 } == true,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ClaudeAccent)
            ) {
                Icon(
                    imageVector = Icons.Default.SwapHoriz,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "O'tkazish",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * Сетка карт снизу зоны транзакции.
 * Долгий тап → выбор для удаления (как в RenterTable/ScooterTable).
 * Обычный тап → редактирование.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CardsGrid(
    cards: List<VirtualCard>,
    selectedIds: Set<Int>,
    onCardClick: (VirtualCard) -> Unit,
    onCardLongClick: (Int, Boolean) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(cards, key = { it.id }) { card ->
            VirtualCardView(
                card = card,
                selected = card.id in selectedIds,
                onClick = {
                    if (selectedIds.isNotEmpty()) {
                        onCardLongClick(card.id, card.id !in selectedIds)
                    } else {
                        onCardClick(card)
                    }
                },
                onLongClick = { onCardLongClick(card.id, card.id !in selectedIds) }
            )
        }
        item {
            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

/**
 * Диалог создания/редактирования виртуальной карты.
 * Обязательные поля: имя, баланс, цвет фона.
 * Необязательное: дополнительная информация.
 */
@Composable
fun VirtualCardFormDialog(
    initial: VirtualCard? = null,
    onDismiss: () -> Unit,
    onSave: (name: String, balance: Double, colorHex: String, info: String?) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var balanceText by remember {
        mutableStateOf(initial?.balance?.let { formatMoney(it).replace(" ", "") } ?: "0")
    }
    var info by remember { mutableStateOf(initial?.info ?: "") }
    var selectedColor by remember {
        mutableStateOf(initial?.colorHex ?: VirtualCard.COLOR_PALETTE.first())
    }
    val isEdit = initial != null
    val isDefaultCard = initial?.isDefault == true

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = ClaudeCard,
        title = {
            Text(
                text = if (isEdit) "Kartani tahrirlash" else "Yangi karta",
                fontWeight = FontWeight.Bold,
                color = ClaudeText
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Превью карты
                VirtualCardView(
                    card = VirtualCard(
                        id = initial?.id ?: 0,
                        name = name.ifBlank { "Karta nomi" },
                        balance = balanceText.replace(" ", "").replace(",", ".").toDoubleOrNull() ?: 0.0,
                        colorHex = selectedColor,
                        info = info.ifBlank { null },
                        isDefault = isDefaultCard
                    )
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Karta nomi *") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = ClaudeDivider,
                        focusedBorderColor = ClaudeAccent
                    )
                )

                OutlinedTextField(
                    value = balanceText,
                    onValueChange = { s ->
                        balanceText = s.filter { it.isDigit() || it == '-' || it == '.' || it == ' ' }
                    },
                    label = { Text("Balans (so'm, minus uchun '-' ishlating) *") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    enabled = !isDefaultCard,  // системные карты нельзя редактировать через форму
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = ClaudeDivider,
                        focusedBorderColor = ClaudeAccent,
                        disabledTextColor = ClaudeTextSecondary
                    )
                )

                OutlinedTextField(
                    value = info,
                    onValueChange = { info = it },
                    label = { Text("Qo'shimcha ma'lumot (ixtiyoriy)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = ClaudeDivider,
                        focusedBorderColor = ClaudeAccent
                    )
                )

                Text(
                    text = "Fon rangi *",
                    style = MaterialTheme.typography.labelLarge,
                    color = ClaudeText
                )
                // Палитра цветов
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    VirtualCard.COLOR_PALETTE.forEach { hex ->
                        val color = parseColorHex(hex)
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(color)
                                .then(
                                    if (selectedColor == hex) Modifier.border(3.dp, ClaudeText, CircleShape)
                                    else Modifier.border(1.dp, ClaudeDivider, CircleShape)
                                )
                                .clickable { selectedColor = hex }
                        )
                    }
                }

                if (isDefaultCard) {
                    Text(
                        text = "Asosiy kartalarni o'chirib bo'lmaydi. Balansni o'tkazmalar orqali o'zgartiring.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ClaudeTextSecondary
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val balance = balanceText.replace(" ", "").replace(",", ".").toDoubleOrNull() ?: 0.0
                    if (name.isNotBlank()) {
                        onSave(name.trim(), balance, selectedColor, info.ifBlank { null })
                    }
                },
                enabled = name.isNotBlank(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ClaudeAccent)
            ) {
                Text(if (isEdit) "Saqlash" else "Yaratish", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Bekor qilish", color = ClaudeTextSecondary)
            }
        }
    )
}

/**
 * Диалог выбора карты (для слота зоны транзакции).
 */
@Composable
fun CardPickerDialog(
    cards: List<VirtualCard>,
    excludeId: Int?,
    title: String,
    onDismiss: () -> Unit,
    onPick: (VirtualCard) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = ClaudeCard,
        title = {
            Text(title, fontWeight = FontWeight.Bold, color = ClaudeText)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                cards
                    .filter { it.id != excludeId }
                    .forEach { card ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(ClaudeBackground)
                                .clickable { onPick(card) }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(parseColorHex(card.colorHex))
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = card.name,
                                    fontWeight = FontWeight.Medium,
                                    color = ClaudeText
                                )
                                Text(
                                    text = "${formatMoney(card.balance)} so'm",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = ClaudeTextSecondary
                                )
                            }
                        }
                    }
                if (cards.isEmpty()) {
                    Text(
                        text = "Kartalar mavjud emas. Avval yangi karta yarating.",
                        color = ClaudeTextSecondary
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Yopish", color = ClaudeTextSecondary)
            }
        }
    )
}

/**
 * Главная панель вкладки «Finansi».
 *
 * Содержит:
 *   • Зону транзакции (сверху)
 *   • Список всех карт (снизу) — с поддержкой tap-to-edit и long-press-delete
 */
@Composable
fun FinansiPanel(
    viewModel: FinansiViewModel,
    onEditCard: (VirtualCard) -> Unit
) {
    val cards by viewModel.cards.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var fromCard by remember { mutableStateOf<VirtualCard?>(null) }
    var toCard by remember { mutableStateOf<VirtualCard?>(null) }
    var pickingSlot by remember { mutableStateOf<Int?>(null) }   // 1 = from, 2 = to
    var selectedCardIds by remember { mutableStateOf<Set<Int>>(emptySet()) }

    // Когда карты загрузились — ставим две первые как дефолтный выбор
    LaunchedEffect(cards.size) {
        if (cards.isNotEmpty()) {
            if (fromCard == null && cards.isNotEmpty()) fromCard = cards.first()
            if (toCard == null && cards.size > 1) toCard = cards.getOrNull(1)
        }
    }

    // Обновляем ссылки на выбранные карты (баланс мог измениться после перевода)
    LaunchedEffect(cards) {
        fromCard = fromCard?.let { selected -> cards.find { it.id == selected.id } }
        toCard = toCard?.let { selected -> cards.find { it.id == selected.id } }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Зона транзакции
        TransactionZone(
            cards = cards,
            fromCard = fromCard,
            toCard = toCard,
            onPickFrom = { pickingSlot = 1 },
            onPickTo = { pickingSlot = 2 },
            onTransfer = { amount, note, reversed ->
                val fromId = fromCard?.id ?: return@TransactionZone
                val toId = toCard?.id ?: return@TransactionZone
                viewModel.transfer(fromId, toId, amount, note, reversed)
            }
        )

        // Панель действий (если есть выбранная карта для удаления)
        if (selectedCardIds.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${selectedCardIds.size} ta tanlandi",
                    color = ClaudeText
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            // Снимаем выделение
                            selectedCardIds = emptySet()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ClaudeCard),
                        border = BorderStroke(1.dp, ClaudeDivider),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Bekor", color = ClaudeText)
                    }
                    Button(
                        onClick = {
                            selectedCardIds.forEach { id ->
                                cards.find { it.id == id }?.let { card ->
                                    if (!card.isDefault) {
                                        viewModel.deleteCard(card)
                                    }
                                }
                            }
                            selectedCardIds = emptySet()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE05B44)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("O'chirish", color = Color.White)
                    }
                }
            }
        }

        // Список карт
        CardsGrid(
            cards = cards,
            selectedIds = selectedCardIds,
            onCardClick = { card ->
                if (card.isDefault) {
                    // Системные карты тоже можно редактировать (имя/цвет/инфо), но не удалять
                    onEditCard(card)
                } else {
                    onEditCard(card)
                }
            },
            onCardLongClick = { id, select ->
                val newSet = selectedCardIds.toMutableSet()
                if (select) newSet.add(id) else newSet.remove(id)
                selectedCardIds = newSet
            }
        )
    }

    // Диалог выбора карты в слот
    pickingSlot?.let { slot ->
        CardPickerDialog(
            cards = cards,
            excludeId = if (slot == 1) toCard?.id else fromCard?.id,
            title = if (slot == 1) "Manba kartani tanlang" else "Maqsad kartani tanlang",
            onDismiss = { pickingSlot = null },
            onPick = { card ->
                if (slot == 1) fromCard = card else toCard = card
                pickingSlot = null
            }
        )
    }
}

/**
 * Панель вкладки «Tranzaksiyalar» — лента переводов между картами.
 */
@Composable
fun TransactionsPanel(
    viewModel: FinansiViewModel,
    cards: List<VirtualCard>
) {
    val transactions by viewModel.transactions.collectAsStateWithLifecycle()
    val cardById = remember(cards) { cards.associateBy { it.id } }
    val context = LocalContext.current

    if (transactions.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.SwapHoriz,
                    contentDescription = null,
                    tint = ClaudeTextSecondary,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Tranzaksiyalar yo'q",
                    color = ClaudeTextSecondary,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = "Finansi bo'limida o'tkazma qiling",
                    color = ClaudeTextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(transactions, key = { it.id }) { tx ->
            val from = cardById[tx.fromCardId]
            val to = cardById[tx.toCardId]
            val time = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
                .format(Date(tx.timestamp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = ClaudeCard),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, ClaudeDivider)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Цвет источника
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(
                                if (from != null) parseColorHex(from.colorHex)
                                else ClaudeDivider
                            )
                    )
                    Text(
                        text = from?.name ?: "—",
                        style = MaterialTheme.typography.bodySmall,
                        color = ClaudeText,
                        modifier = Modifier.weight(1f),
                        maxLines = 1
                    )
                    Icon(
                        imageVector = Icons.Default.ArrowForward,
                        contentDescription = null,
                        tint = ClaudeTextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    // Цвет назначения
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(
                                if (to != null) parseColorHex(to.colorHex)
                                else ClaudeDivider
                            )
                    )
                    Text(
                        text = to?.name ?: "—",
                        style = MaterialTheme.typography.bodySmall,
                        color = ClaudeText,
                        modifier = Modifier.weight(1f),
                        maxLines = 1
                    )
                    // Сумма
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "${formatMoney(tx.amount)} so'm",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF34C759)
                        )
                        Text(
                            text = time,
                            style = MaterialTheme.typography.labelSmall,
                            color = ClaudeTextSecondary
                        )
                    }
                }
                if (!tx.note.isNullOrBlank()) {
                    Text(
                        text = tx.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = ClaudeTextSecondary,
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
                    )
                }
            }
        }
        item {
            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}
