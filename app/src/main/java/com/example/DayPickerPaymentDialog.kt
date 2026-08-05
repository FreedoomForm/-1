package com.example

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.ClaudeAccent
import com.example.ui.theme.ClaudeAccentBg
import com.example.ui.theme.ClaudeBackground
import com.example.ui.theme.ClaudeCard
import com.example.ui.theme.ClaudeDivider
import com.example.ui.theme.ClaudeText
import com.example.ui.theme.ClaudeTextSecondary
import com.example.ui.theme.StatusOk

/**
 * Диалог выбора количества дней для оплаты.
 *
 * Пользователь хочет: при нажатии кнопки «To'lov» (или при тапе на тело
 * уведомления) открывается окно, где он выбирает, за сколько дней хочет
 * оплатить: 7 (неделя), 14 (2 недели), 30 (месяц), 60 (2 месяца) или
 * произвольное число дней через поле ввода.
 *
 * Показывает итоговую сумму: days × dailyPrice.
 *
 * При подтверждении вызывает [onConfirm] с выбранным числом дней.
 *
 * @param renterName имя арендатора (для заголовка диалога).
 * @param dailyPrice дневная ставка (для расчёта суммы).
 * @param onConfirm callback с выбранным числом дней.
 * @param onDismiss callback закрытия диалога.
 */
@Composable
fun DayPickerPaymentDialog(
    renterName: String,
    dailyPrice: Double,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedDays by remember { mutableStateOf(7) }
    var customDaysText by remember { mutableStateOf("") }

    // Если в поле ввода есть валидное число — используем его, иначе selectedDays.
    val customDays = customDaysText.toIntOrNull()
    val effectiveDays = if (customDays != null && customDays > 0) customDays else selectedDays
    val totalAmount = effectiveDays * dailyPrice

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ClaudeCard,
        title = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.CalendarMonth,
                        contentDescription = null,
                        tint = ClaudeAccent,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "To'lov — $renterName",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Necha kun uchun to'lov qilasiz?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClaudeTextSecondary
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // ── Быстрые кнопки: 7, 14, 30, 60 дней ────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(7, 14, 30, 60).forEach { days ->
                        val isSelected = selectedDays == days && customDaysText.isBlank()
                        DayQuickButton(
                            days = days,
                            amount = days * dailyPrice,
                            isSelected = isSelected,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                selectedDays = days
                                customDaysText = ""
                            }
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ── Поле ввода произвольного числа дней ──────────────────
                OutlinedTextField(
                    value = customDaysText,
                    onValueChange = { value ->
                        // Принимаем только цифры, максимум 4 символа (до 9999 дней)
                        val filtered = value.filter { it.isDigit() }.take(4)
                        customDaysText = filtered
                        if (filtered.isNotEmpty()) {
                            filtered.toIntOrNull()?.let { selectedDays = it }
                        }
                    },
                    label = { Text("Yoki o'zingiz kiriting (kun)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    singleLine = true
                )

                Spacer(Modifier.height(12.dp))

                // ── Итоговая сумма ────────────────────────────────────────
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(ClaudeAccentBg)
                        .border(1.dp, ClaudeAccent.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "$effectiveDays kun",
                                style = MaterialTheme.typography.titleSmall,
                                color = ClaudeText,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "${dailyPrice.toLong()} so'm/kun",
                                style = MaterialTheme.typography.labelSmall,
                                color = ClaudeTextSecondary
                            )
                        }
                        Text(
                            text = "${totalAmount.toLong()} so'm",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = StatusOk,
                            textAlign = TextAlign.End
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(effectiveDays) },
                enabled = effectiveDays > 0
            ) {
                Text(
                    "To'lash ($effectiveDays kun)",
                    fontWeight = FontWeight.Bold,
                    color = if (effectiveDays > 0) ClaudeAccent else ClaudeTextSecondary
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Bekor qilish", color = ClaudeTextSecondary)
            }
        }
    )
}

@Composable
private fun DayQuickButton(
    days: Int,
    amount: Double,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .height(72.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (isSelected) ClaudeAccent.copy(alpha = 0.15f) else ClaudeBackground)
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) ClaudeAccent else ClaudeDivider,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable { onClick() }
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "$days",
            fontSize = 18.sp,
            color = if (isSelected) ClaudeAccent else ClaudeText,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "kun",
            fontSize = 10.sp,
            color = ClaudeTextSecondary
        )
        Text(
            text = "${amount.toLong()}",
            fontSize = 10.sp,
            color = if (isSelected) ClaudeAccent else ClaudeTextSecondary,
            fontWeight = FontWeight.SemiBold
        )
    }
}
