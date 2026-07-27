package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.ui.theme.ClaudeCard
import com.example.ui.theme.ClaudeDivider
import com.example.ui.theme.ClaudeText
import com.example.ui.theme.ClaudeTextSecondary

/**
 * §11: EmptyState — переиспользуемое пустое состояние.
 *
 * Состоит из иконки в круге, заголовка, описания и опциональной кнопки
 * действия. Применяется во всех списках/таблицах когда данных нет.
 *
 * Цвета и размеры выверены для узких экранов 320–360dp.
 */
@Composable
fun EmptyState(
    title: String,
    description: String,
    icon: ImageVector = Icons.Default.Inbox,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier.size(72.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = ClaudeTextSecondary,
                    modifier = Modifier.size(48.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = ClaudeText,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = ClaudeTextSecondary,
                textAlign = TextAlign.Center
            )
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = onAction) {
                    Text(actionLabel, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/**
 * §11: InfoBanner — ненавязчивая подсказка/информация сверху списка.
 */
@Composable
fun InfoBanner(
    message: String,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null
) {
    Surface(
        color = ClaudeCard,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, ClaudeDivider)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = ClaudeTextSecondary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = ClaudeText,
                modifier = Modifier.weight(1f)
            )
            if (onDismiss != null) {
                TextButton(onClick = onDismiss) {
                    Text("OK")
                }
            }
        }
    }
}

