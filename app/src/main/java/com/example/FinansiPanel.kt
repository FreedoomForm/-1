package com.example

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.VirtualCard
import com.example.data.isExternal
import com.example.data.isExternalIn
import com.example.data.isExternalOut
import com.example.ui.FinansiViewModel
import com.example.ui.components.FilterColumn
import com.example.ui.components.FilterSidePanel
import com.example.ui.components.UnifiedSearchBar
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
 *   • диагональный градиент-текстура (эффект пластика)
 *   • тонкая светлая полоса-чип (как на реальных картах)
 *   • текстура из тонких диагональных линий для имитации рельефа
 *
 * [selected] подсвечивает карту рамкой (для зоны выбора транзакции).
 * [onClick] / [onLongClick] поддерживают tap-to-edit и long-press multi-select.
 * [onMoveUp] / [onMoveDown] — кнопки перестановки (как виджеты в Otcheti).
 * [showReorderButtons] — показывать ли стрелки ↑/↓ (в зоне транзакции они не нужны).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VirtualCardView(
    card: VirtualCard,
    selected: Boolean = false,
    isSlot: Boolean = false,
    compact: Boolean = false,
    showReorderButtons: Boolean = false,
    canMoveUp: Boolean = true,
    canMoveDown: Boolean = true,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
    onMoveUp: () -> Unit = {},
    onMoveDown: () -> Unit = {}
) {
    val baseColor = parseColorHex(card.colorHex)
    val cardShape = RoundedCornerShape(16.dp)
    val cardHeight = if (compact) 110.dp else 150.dp
    val balanceFont = if (compact) 16.sp else 22.sp
    val nameFont = if (compact) 13.sp else 16.sp
    val chipSize = if (compact) 28.dp else 36.dp
    val chipPaddingTop = if (compact) 36.dp else 56.dp

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(cardHeight)
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
                .padding(start = 12.dp, top = chipPaddingTop)
                .size(width = chipSize, height = if (compact) 20.dp else 26.dp)
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
                .padding(if (compact) 10.dp else 16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Верх: имя карты + метка системной + кнопки перестановки
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = card.name,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = nameFont,
                    modifier = Modifier.weight(1f),
                    maxLines = 1
                )
                if (card.isDefault && !card.isExternal) {
                    Box(
                        modifier = Modifier
                            .background(
                                Color.White.copy(alpha = 0.2f),
                                RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "ASOSIY",
                            color = Color.White,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else if (card.isExternal) {
                    Box(
                        modifier = Modifier
                            .background(
                                Color.White.copy(alpha = 0.25f),
                                RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = if (card.isExternalIn) "TASHQIDAN" else "TASHQIGA",
                            color = Color.White,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Центр: только сумма баланса (∞ для внешних карт)
            Column {
                val balanceText = if (card.isExternal) "∞" else "${formatMoney(card.balance)} so'm"
                Text(
                    text = balanceText,
                    color = Color.White,
                    fontSize = balanceFont,
                    fontWeight = FontWeight.Bold
                )
            }

            // Низ: инфо + кнопки перестановки ↑/↓ (если включены)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = card.info ?: "",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = if (compact) 9.sp else 11.sp,
                    modifier = Modifier.weight(1f),
                    maxLines = 1
                )
                if (showReorderButtons) {
                    // Кнопки перестановки (как в Otcheti — поверх карты, белым)
                    Row {
                        IconButton(
                            onClick = onMoveUp,
                            enabled = canMoveUp,
                            modifier = Modifier.size(if (compact) 22.dp else 28.dp)
                        ) {
                            Icon(
                                Icons.Default.KeyboardArrowUp,
                                contentDescription = "Yuqoriga",
                                tint = if (canMoveUp) Color.White else Color.White.copy(alpha = 0.3f),
                                modifier = Modifier.size(if (compact) 16.dp else 20.dp)
                            )
                        }
                        IconButton(
                            onClick = onMoveDown,
                            enabled = canMoveDown,
                            modifier = Modifier.size(if (compact) 22.dp else 28.dp)
                        ) {
                            Icon(
                                Icons.Default.KeyboardArrowDown,
                                contentDescription = "Pastga",
                                tint = if (canMoveDown) Color.White else Color.White.copy(alpha = 0.3f),
                                modifier = Modifier.size(if (compact) 16.dp else 20.dp)
                            )
                        }
                    }
                } else {
                    Icon(
                        imageVector = if (selected) Icons.Default.Edit else Icons.Default.SwapHoriz,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(if (compact) 14.dp else 18.dp)
                    )
                }
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

    // Внешний перевод — если хотя бы одна из выбранных карт внешняя
    // (Tashqidan / Tashqiga), поле «Izoh» становится обязательным.
    val involvesExternal = (fromCard?.isExternal == true) || (toCard?.isExternal == true)
    val noteLabel = if (involvesExternal) "Izoh (majburiy) *" else "Izoh (ixtiyoriy)"
    val amountParsed = amountText.replace(',', '.').toDoubleOrNull()
    val canTransfer = fromCard != null && toCard != null &&
                      amountParsed != null && amountParsed > 0.0 &&
                      fromCard!!.id != toCard!!.id &&
                      (!involvesExternal || noteText.isNotBlank())

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
                            compact = true,
                            onClick = onPickFrom
                        )
                    } else {
                        EmptyCardSlot(label = "Manba karta", onClick = onPickFrom)
                    }
                }

                // Стрелка посередине + поле ввода под ней
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
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
                }

                // Карта-назначение (или слот)
                Box(modifier = Modifier.weight(1f)) {
                    if (toCard != null) {
                        VirtualCardView(
                            card = toCard,
                            selected = true,
                            compact = true,
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

            // ── Поле примечания (обязательное для внешних переводов) ──
            OutlinedTextField(
                value = noteText,
                onValueChange = { noteText = it },
                label = { Text(noteLabel) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                isError = involvesExternal && noteText.isBlank() && amountText.isNotBlank(),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = ClaudeDivider,
                    focusedBorderColor = ClaudeAccent,
                    unfocusedContainerColor = ClaudeBackground,
                    focusedContainerColor = ClaudeBackground
                )
            )
            if (involvesExternal) {
                Text(
                    text = "Tashqi o'tkazma uchun izoh majburiy — summa nima uchun o'tkazilayotganini yozing.",
                    style = MaterialTheme.typography.labelSmall,
                    color = ClaudeTextSecondary,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

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
                enabled = canTransfer,
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
 * Список карт с зоной транзакции сверху.
 *
 * [header] — необязательный заголовок, который рендерится первым элементом
 * сетки с полным span (2 колонки). Это позволяет заголовку (например,
 * TransactionZone) скроллиться вместе с картами, а не залипать сверху.
 *
 * Долгий тап по карте → выбор для удаления (как в RenterTable/ScooterTable).
 * Обычный тап → редактирование.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CardsGrid(
    cards: List<VirtualCard>,
    selectedIds: Set<Int>,
    onCardClick: (VirtualCard) -> Unit,
    onCardLongClick: (Int, Boolean) -> Unit,
    onMoveUp: (VirtualCard) -> Unit = {},
    onMoveDown: (VirtualCard) -> Unit = {},
    modifier: Modifier = Modifier,
    header: @Composable (() -> Unit)? = null
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        // Раньше тут был weight(1f) — CardsGrid занимал место под
        // зафиксированной TransactionZone. Теперь TransactionZone уехала
        // внутрь как header, поэтому сетка занимает весь экран.
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ── Заголовок (зона транзакции) — скроллится вместе с картами ──
        if (header != null) {
            item(span = { GridItemSpan(2) }) {
                header()
            }
        }

        items(cards, key = { it.id }) { card ->
            val index = cards.indexOf(card)
            VirtualCardView(
                card = card,
                compact = true,
                selected = card.id in selectedIds,
                showReorderButtons = true,
                canMoveUp = index > 0,
                canMoveDown = index < cards.size - 1,
                onClick = {
                    if (selectedIds.isNotEmpty()) {
                        onCardLongClick(card.id, card.id !in selectedIds)
                    } else {
                        onCardClick(card)
                    }
                },
                onLongClick = { onCardLongClick(card.id, card.id !in selectedIds) },
                onMoveUp = { onMoveUp(card) },
                onMoveDown = { onMoveDown(card) }
            )
        }
        // Bottom spacer — чтобы последний ряд не перекрывался FAB
        item(span = { GridItemSpan(2) }) {
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
                        isDefault = isDefaultCard,
                        kind = initial?.kind ?: VirtualCard.KIND_REGULAR
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
                    // Все поля необязательны — сохраняем даже с пустым именем.
                    // Раньше требовалось name.isNotBlank() — убрано.
                    onSave(name.trim(), balance, selectedColor, info.ifBlank { null })
                },
                // Кнопка всегда активна — ничего не блокирует сохранение.
                enabled = true,
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
                                    text = if (card.isExternal) "∞" else "${formatMoney(card.balance)} so'm",
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
 *
 * [externalCreateTrigger] увеличивается MainActivity при нажатии «+» в TopAppBar —
 * открывает диалог создания новой карты.
 * [externalEditTrigger] увеличивается MainActivity при нажатии «✎» в TopAppBar —
 * открывает диалог редактирования выбранной карты.
 * [externalDeleteTrigger] — аналогично для удаления выбранных карт.
 */
@Composable
fun FinansiPanel(
    viewModel: FinansiViewModel,
    externalCreateTrigger: Int = 0,
    externalEditTrigger: Int = 0,
    externalDeleteTrigger: Int = 0,
    selectedCardIds: Set<Int> = emptySet(),
    onSelectedCardIdsChange: (Set<Int>) -> Unit = {},
    onCardClick: (VirtualCard) -> Unit = {}
) {
    val cards by viewModel.cards.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // ── Порядок карт (как в Otcheti) — сохраняется в SharedPreferences ────
    // Хранится как строка id1,id2,id3,... — карты, которых нет в строке,
    // добавляются в конец по возрастанию id.
    val orderPrefs = remember { context.getSharedPreferences("finansi_card_order", android.content.Context.MODE_PRIVATE) }
    var cardOrder: List<Int> by remember {
        mutableStateOf(
            orderPrefs.getString("order", null)
                ?.split(",")
                ?.mapNotNull { it.toIntOrNull() }
                ?: emptyList()
        )
    }

    // Сортируем карты по сохранённому порядку: сначала те, что есть в cardOrder
    // (в этом порядке), потом остальные (по возрастанию id).
    val orderedCards = remember(cards, cardOrder) {
        val inOrder = cardOrder.mapNotNull { id -> cards.find { it.id == id } }
        val rest = cards.filter { it.id !in cardOrder }.sortedBy { it.id }
        inOrder + rest
    }

    // ── Поиск + фильтр колонок ─────────────────────────────────────────────
    // Раньше на вкладке «Finansi» не было ни поиска, ни фильтра — в отличие
    // от остальных вкладок (Ijarachilar / Skuterlar / Kontraktlar / Tranzaksiya).
    // Теперь добавлены UnifiedSearchBar и FilterSidePanel с 4 колонками:
    // имя, баланс, тип (REGULAR/EXTERNAL_IN/EXTERNAL_OUT), инфо.
    var searchQuery by remember { mutableStateOf("") }
    var showFilterPanel by remember { mutableStateOf(false) }
    var filterValues by remember { mutableStateOf(mapOf<String, String>()) }
    val cardFilterColumns = remember {
        listOf(
            FilterColumn("col_name",    "Nomi",     "Karta nomi"),
            FilterColumn("col_balance", "Balans",   "summa"),
            FilterColumn("col_kind",    "Turi",     "REGULAR / EXTERNAL_IN / EXTERNAL_OUT"),
            FilterColumn("col_info",    "Izoh",     "Qo'shimcha ma'lumot")
        )
    }
    val filteredOrderedCards = remember(orderedCards, searchQuery, filterValues) {
        orderedCards.filter { card ->
            val textMatch = searchQuery.isBlank() ||
                card.name.contains(searchQuery, ignoreCase = true) ||
                (card.info?.contains(searchQuery, ignoreCase = true) == true) ||
                card.balance.toLong().toString().contains(searchQuery, ignoreCase = true)
            val filterMatch = filterValues.all { (colId, filterText) ->
                if (filterText.isBlank()) true
                else when (colId) {
                    "col_name"    -> card.name.contains(filterText, ignoreCase = true)
                    "col_balance" -> card.balance.toLong().toString().contains(filterText, ignoreCase = true)
                    "col_kind"    -> card.kind.contains(filterText, ignoreCase = true)
                    "col_info"    -> (card.info ?: "").contains(filterText, ignoreCase = true)
                    else -> true
                }
            }
            textMatch && filterMatch
        }
    }

    fun saveOrder(newOrder: List<Int>) {
        cardOrder = newOrder
        orderPrefs.edit().putString("order", newOrder.joinToString(",")).apply()
    }

    fun moveCardUp(card: VirtualCard) {
        val currentIds = orderedCards.map { it.id }
        val idx = currentIds.indexOf(card.id)
        if (idx > 0) {
            val newList = currentIds.toMutableList()
            val tmp = newList[idx - 1]
            newList[idx - 1] = card.id
            newList[idx] = tmp
            saveOrder(newList)
        }
    }
    fun moveCardDown(card: VirtualCard) {
        val currentIds = orderedCards.map { it.id }
        val idx = currentIds.indexOf(card.id)
        if (idx >= 0 && idx < currentIds.size - 1) {
            val newList = currentIds.toMutableList()
            val tmp = newList[idx + 1]
            newList[idx + 1] = card.id
            newList[idx] = tmp
            saveOrder(newList)
        }
    }

    var fromCard by remember { mutableStateOf<VirtualCard?>(null) }
    var toCard by remember { mutableStateOf<VirtualCard?>(null) }
    var pickingSlot by remember { mutableStateOf<Int?>(null) }   // 1 = from, 2 = to
    var selectedIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var editingCard by remember { mutableStateOf<VirtualCard?>(null) }

    // Запоминаем последнее обработанное значение каждого триггера, чтобы
    // НЕ реагировать на начальное значение при входе на вкладку (compose
    // создаёт composable заново каждый раз при переключении вкладок —
    // LaunchedEffect при этом срабатывает с текущим значением триггера,
    // что раньше приводило к автологу диалога создания).
    var lastCreateTrigger by remember { mutableStateOf(externalCreateTrigger) }
    var lastEditTrigger by remember { mutableStateOf(externalEditTrigger) }
    var lastDeleteTrigger by remember { mutableStateOf(externalDeleteTrigger) }

    // Синхронизируем локальный selectedIds с внешним (из MainActivity)
    LaunchedEffect(selectedCardIds) {
        selectedIds = selectedCardIds
    }
    LaunchedEffect(selectedIds) {
        onSelectedCardIdsChange(selectedIds)
    }

    // Когда карты загрузились — ставим две первые как дефолтный выбор
    LaunchedEffect(orderedCards.size) {
        if (orderedCards.isNotEmpty()) {
            if (fromCard == null && orderedCards.isNotEmpty()) fromCard = orderedCards.first()
            if (toCard == null && orderedCards.size > 1) toCard = orderedCards.getOrNull(1)
        }
    }

    // Обновляем ссылки на выбранные карты (баланс мог измениться после перевода)
    LaunchedEffect(orderedCards) {
        fromCard = fromCard?.let { selected -> orderedCards.find { it.id == selected.id } }
        toCard = toCard?.let { selected -> orderedCards.find { it.id == selected.id } }
    }

    // Реакция на внешний триггер создания (кнопка «+» в TopAppBar).
    // Срабатывает ТОЛЬКО при реальном увеличении значения, а не при входе на вкладку.
    LaunchedEffect(externalCreateTrigger) {
        if (externalCreateTrigger > lastCreateTrigger) {
            showCreateDialog = true
        }
        lastCreateTrigger = externalCreateTrigger
    }

    // Реакция на внешний ✎ — открываем диалог редактирования выбранной карты.
    LaunchedEffect(externalEditTrigger) {
        if (externalEditTrigger > lastEditTrigger && selectedIds.size == 1) {
            editingCard = cards.firstOrNull { it.id == selectedIds.first() }
        }
        lastEditTrigger = externalEditTrigger
    }

    // Реакция на внешний 🗑 — удаляем выбранные карты.
    LaunchedEffect(externalDeleteTrigger) {
        if (externalDeleteTrigger > lastDeleteTrigger && selectedIds.isNotEmpty()) {
            selectedIds.forEach { id ->
                cards.find { it.id == id }?.let { card ->
                    if (!card.isDefault) {
                        viewModel.deleteCard(card)
                    }
                }
            }
            selectedIds = emptySet()
            onSelectedCardIdsChange(emptySet())
        }
        lastDeleteTrigger = externalDeleteTrigger
    }

    // Раньше тут был Column { TransactionZone(); CardsGrid(weight(1f)) },
    // из-за чего TransactionZone была прибита сверху и «следовала» за
    // пользователем при прокрутке карт. Теперь TransactionZone передаётся
    // в CardsGrid как header — первый full-span item в LazyVerticalGrid —
    // и скроллится вместе с картами естественным образом.
    //
    // Сверху над сеткой добавлены UnifiedSearchBar + FilterSidePanel —
    // такие же, как в остальных вкладках. Фильтр применяется к
    // filteredOrderedCards (по имени, балансу, типу, инфо).
    Column(modifier = Modifier.fillMaxSize()) {
        UnifiedSearchBar(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            placeholder = "Karta qidirish",
            onCalendarClick = { /* у карт нет календаря — кнопка скрыта */ },
            calendarActive = false,
            onFilterClick = { showFilterPanel = true },
            filterActive = filterValues.any { it.value.isNotBlank() }
        )
        FilterSidePanel(
            columns = cardFilterColumns,
            filterValues = filterValues,
            onFilterChange = { colId, value ->
                filterValues = filterValues.toMutableMap().apply { put(colId, value) }
            },
            onSearch = { /* applied reactively */ },
            onReset = { filterValues = emptyMap() },
            onDismiss = { showFilterPanel = false },
            visible = showFilterPanel
        )
        CardsGrid(
            cards = filteredOrderedCards,
            selectedIds = selectedIds,
            onCardClick = { card ->
                // Тап по карте → открывает экран истории транзакций этой карты
                // (как тап по арендатору открывает RenterContractHistoryScreen).
                onCardClick(card)
            },
            onCardLongClick = { id, select ->
                val newSet = selectedIds.toMutableSet()
                if (select) newSet.add(id) else newSet.remove(id)
                selectedIds = newSet
                onSelectedCardIdsChange(newSet)

                // ── Авто-заполнение зоны транзакции при выборе ровно 2 карт ──
                // Пользователь долго жмёт на 2 карты → они автоматически
                // подставляются в слоты from/to зоны транзакции.
                if (newSet.size == 2) {
                    val list = newSet.toList()
                    val firstCard = orderedCards.firstOrNull { it.id == list[0] }
                    val secondCard = orderedCards.firstOrNull { it.id == list[1] }
                    if (firstCard != null && secondCard != null) {
                        fromCard = firstCard
                        toCard = secondCard
                    }
                }
            },
            onMoveUp = { card -> moveCardUp(card) },
            onMoveDown = { card -> moveCardDown(card) },
            modifier = Modifier.weight(1f),
            header = {
                TransactionZone(
                    cards = orderedCards,
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
            }
        )
    }

    // Диалог выбора карты в слот
    pickingSlot?.let { slot ->
        CardPickerDialog(
            cards = orderedCards,
            excludeId = if (slot == 1) toCard?.id else fromCard?.id,
            title = if (slot == 1) "Manba kartani tanlang" else "Maqsad kartani tanlang",
            onDismiss = { pickingSlot = null },
            onPick = { card ->
                if (slot == 1) fromCard = card else toCard = card
                pickingSlot = null
            }
        )
    }

    // Диалог создания/редактирования карты
    if (showCreateDialog || editingCard != null) {
        VirtualCardFormDialog(
            initial = editingCard,
            onDismiss = {
                showCreateDialog = false
                editingCard = null
            },
            onSave = { name, balance, colorHex, info ->
                if (editingCard != null) {
                    // Редактирование: сохраняем isDefault, не трогаем баланс для дефолтных
                    val updated = if (editingCard!!.isDefault) {
                        editingCard!!.copy(name = name, colorHex = colorHex, info = info)
                    } else {
                        editingCard!!.copy(name = name, balance = balance, colorHex = colorHex, info = info)
                    }
                    viewModel.updateCard(updated)
                } else {
                    viewModel.addCard(name, balance, colorHex, info)
                }
                showCreateDialog = false
                editingCard = null
            }
        )
    }
}
