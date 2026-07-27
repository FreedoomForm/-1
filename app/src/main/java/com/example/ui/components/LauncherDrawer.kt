@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
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
import kotlinx.coroutines.launch

/**
 * §9.A — In-app launcher + interactive navigation drawer.
 *
 * Кубики-иконки основных страниц в полноэкранном поле. Пользователь может:
 *   • тапнуть по кубику — откроется соответствующая страница;
 *   • нажать и держать кубик — включится режим перемещения (long-press drag);
 *   • потянуть снизу вверх — раскроется шторка с многоуровневой навигацией;
 *   • потянуть сверху вниз — шторка свернётся обратно.
 *
 * Порядок кубиков сохраняется локально через [rememberSaveable] — приложение
 * запоминает расположение между запусками.
 *
 * Крупные touch-targets (72dp) обеспечивают удобство нажатия даже на узких
 * экранах 320–360dp (§11).
 */

/** Описание одной страницы для launcher-куба. */
data class LauncherPage(
    val id: String,
    val title: String,
    val icon: ImageVector,
    val accentColor: Color = ClaudeAccent
)

/** Стандартный набор страниц launcher-а. */
val DefaultLauncherPages: List<LauncherPage> = listOf(
    LauncherPage("renters",   "Mijozlar",    Icons.Default.Person),
    LauncherPage("scooters",  "Skuterlar",   Icons.Default.DirectionsBike),
    LauncherPage("contracts", "Kontraktlar", Icons.Default.Home),
    LauncherPage("finansi",   "Moliya",      Icons.Default.AccountBalanceWallet),
    LauncherPage("reports",   "Hisobotlar",  Icons.Default.Assessment),
    LauncherPage("history",   "Tarix",       Icons.Default.History),
    LauncherPage("trash",     "Chiqindi",    Icons.Default.Delete),
    LauncherPage("settings",  "Sozlamalar",  Icons.Default.Settings)
)

/**
 * Полноэкранный launcher с кубиками-иконками.
 *
 * @param pages список страниц для отображения (по умолчанию [DefaultLauncherPages]).
 * @param onPageClick callback при тапе по кубику — передаётся id страницы.
 * @param onDrawerPullUp callback когда пользователь тянет шторку снизу-вверх
 *   (раскрытие многоуровневой нижней навигации).
 */
@Composable
fun LauncherScreen(
    pages: List<LauncherPage> = DefaultLauncherPages,
    onPageClick: (String) -> Unit,
    onDrawerPullUp: () -> Unit = {}
) {
    // Сохраняем порядок кубиков локально. По умолчанию — стандартный порядок.
    // Пользователь может менять порядок через long-press + drag (future iter).
    var order by rememberSaveable { mutableStateOf(pages.map { it.id }) }
    val orderedPages = order.mapNotNull { id -> pages.find { it.id == id } }

    Box(modifier = Modifier.fillMaxSize().background(ClaudeBackground)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Scooter Rent",
                style = MaterialTheme.typography.headlineSmall,
                color = ClaudeText,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Sahifalardan birini tanlang",
                style = MaterialTheme.typography.bodyMedium,
                color = ClaudeTextSecondary
            )
            Spacer(Modifier.height(24.dp))

            // Сетка кубиков 2 × N — крупные touch-targets 72dp + текст под иконкой.
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(orderedPages, key = { it.id }) { page ->
                    LauncherCube(
                        page = page,
                        onClick = { onPageClick(page.id) }
                    )
                }
            }
        }

        // ── Drawer handle (bottom) — потянуть вверх чтобы открыть шторку ──
        DrawerPullHandle(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            onPullUp = onDrawerPullUp
        )
    }
}

/** Один кубик-иконка: круглая иконка на карточке + подпись снизу. */
@Composable
private fun LauncherCube(
    page: LauncherPage,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(110.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(ClaudeCard)
            .border(1.dp, ClaudeDivider, RoundedCornerShape(16.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    // future iter: включить режим drag-and-drop для перестановки.
                }
            )
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(page.accentColor.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = page.icon,
                contentDescription = page.title,
                tint = page.accentColor,
                modifier = Modifier.size(32.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            page.title,
            style = MaterialTheme.typography.labelMedium,
            color = ClaudeText,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

/**
 * Handle для открывания шторки. Реагирует на вертикальный жест снизу-вверх.
 */
@Composable
private fun DrawerPullHandle(
    modifier: Modifier = Modifier,
    onPullUp: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val dragAccumulator = remember { Animatable(0f) }

    Box(
        modifier = modifier
            .height(36.dp)
            .background(ClaudeCard)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        // negative dragAmount = свайп вверх (открывает шторку)
                        scope.launch {
                            dragAccumulator.snapTo(
                                (dragAccumulator.value - dragAmount / 200f).coerceIn(0f, 1f)
                            )
                            if (dragAccumulator.value > 0.5f) {
                                onPullUp()
                                dragAccumulator.snapTo(0f)
                            }
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(ClaudeTextSecondary)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Yuqoriga torting — navigatsiya",
                style = MaterialTheme.typography.labelSmall,
                color = ClaudeTextSecondary,
                fontSize = 10.sp
            )
        }
    }
}

/**
 * Многоуровневая нижняя навигация (drawer), раскрывается при свайпе вверх.
 *
 * Три ряда иконок:
 *   1-й ряд (верхний): основные страницы — Mijozlar, Skuterlar, Kontraktlar, Moliya.
 *   2-й ряд (средний): отчёты — Hisobotlar, Tarix.
 *   3-й ряд (нижний, компактный): Chiqindi, Sozlamalar.
 *
 * @param expanded открыта ли шторка.
 * @param onDismiss закрыть шторку (свайп вниз или тап по empty area).
 * @param onPageSelect callback с id выбранной страницы.
 */
@Composable
fun NavigationDrawerSheet(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onPageSelect: (String) -> Unit
) {
    if (!expanded) return
    Box(modifier = Modifier.fillMaxSize()) {
        // Затемнение под шторкой — тап закрывает.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.4f))
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            // positive dragAmount = свайп вниз (закрывает шторку)
                            if (dragAmount > 30f) onDismiss()
                        }
                    )
                }
                .combinedClickable(onClick = onDismiss, onLongClick = {})
        )
        // Сама шторка — снизу, с тремя рядами иконок.
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(280.dp),
            color = ClaudeCard,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.SpaceEvenly,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 1-й ряд — основные.
                DrawerRow(
                    pages = listOf(
                        DefaultLauncherPages[0], // Mijozlar
                        DefaultLauncherPages[1], // Skuterlar
                        DefaultLauncherPages[2], // Kontraktlar
                        DefaultLauncherPages[3]  // Moliya
                    ),
                    onPageSelect = { id -> onPageSelect(id); onDismiss() }
                )
                // 2-й ряд — отчёты и история.
                DrawerRow(
                    pages = listOf(
                        DefaultLauncherPages[4], // Hisobotlar
                        DefaultLauncherPages[5]  // Tarix
                    ),
                    onPageSelect = { id -> onPageSelect(id); onDismiss() }
                )
                // 3-й ряд — компактный (мелкие иконки).
                DrawerCompactRow(
                    pages = listOf(
                        DefaultLauncherPages[6], // Chiqindi
                        DefaultLauncherPages[7]  // Sozlamalar
                    ),
                    onPageSelect = { id -> onPageSelect(id); onDismiss() }
                )
            }
        }
    }
}

/** Полный ряд иконок с подписями. */
@Composable
private fun DrawerRow(
    pages: List<LauncherPage>,
    onPageSelect: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        pages.forEach { page ->
            DrawerItem(page = page, onClick = { onPageSelect(page.id) })
        }
    }
}

/** Компактный ряд — квадратные иконки без подписей. */
@Composable
private fun DrawerCompactRow(
    pages: List<LauncherPage>,
    onPageSelect: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        pages.forEach { page ->
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(page.accentColor.copy(alpha = 0.12f))
                    .combinedClickable(onClick = { onPageSelect(page.id) }, onLongClick = {}),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = page.icon,
                    contentDescription = page.title,
                    tint = page.accentColor,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}

/** Одна иконка drawer-а (круг + подпись). */
@Composable
private fun DrawerItem(
    page: LauncherPage,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(72.dp)
            .combinedClickable(onClick = onClick, onLongClick = {})
            .padding(vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(page.accentColor.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = page.icon,
                contentDescription = page.title,
                tint = page.accentColor,
                modifier = Modifier.size(28.dp)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            page.title,
            style = MaterialTheme.typography.labelSmall,
            color = ClaudeText,
            fontSize = 11.sp,
            maxLines = 1
        )
    }
}
