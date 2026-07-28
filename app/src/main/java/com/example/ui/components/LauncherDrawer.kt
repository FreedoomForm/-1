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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.ClaudeAccent
import com.example.ui.theme.ClaudeAccentBg
import com.example.ui.theme.ClaudeBackground
import com.example.ui.theme.ClaudeCard
import com.example.ui.theme.ClaudeDarkBg
import com.example.ui.theme.ClaudeDarkText
import com.example.ui.theme.ClaudeText
import com.example.ui.theme.ClaudeTextSecondary
import com.example.ui.theme.ClaudeGold
import com.example.ui.theme.ClaudeTeal
import com.example.ui.theme.StatusOk
import com.example.ui.theme.StatusOverdue
import com.example.ui.theme.StatusReserved
import com.example.ui.theme.StatusArchived
import kotlinx.coroutines.launch

/**
 * §9.A — In-app launcher styled as Android home screen.
 *
 * Layout (matches the user's reference screenshot — MIUI/Xiaomi home):
 *
 *  ```
 *  ┌────────────────────────────────────────┐
 *  │  [wallpaper gradient + abstract shapes]│  ← full-screen wallpaper
 *  │                                        │
 *  │   ┌──┐   ┌──┐   ┌──┐   ┌──┐            │  ← upper grid (4 columns)
 *  │   │📋│   │🛵│   │📑│   │💰│            │     Mijozlar / Skuterlar /
 *  │   └──┘   └──┘   └──┘   └──┘            │     Kontraktlar / Moliya
 *  │   Mijoz  Skut   Kontr  Moliya          │
 *  │                                        │
 *  │   ┌──┐   ┌──┐   ┌──┐   ┌──┐            │  ← row 2
 *  │   │📊│   │🕓│   │🗑│   │⚙│             │     Hisobotlar / Tarix /
 *  │   └──┘   └──┘   └──┘   └──┘            │     Chiqindi / Sozlamalar
 *  │   Hisob  Tarix  Chiq   Sozlam          │
 *  │                                        │
 *  │              ● ● ● ●                   │  ← page indicator dots
 *  │                                        │
 *  │  ┌─────────────────────────────────┐   │  ← bottom dock (translucent
 *  │  │  [📋]    [🛵]    [📑]    [💰]   │   │     dark "frosted glass")
 *  │  └─────────────────────────────────┘   │
 *  └────────────────────────────────────────┘
 *  ```
 *
 * Swipe-down gesture (anywhere on grid): the entire upper grid + page dots
 * slide DOWN off-screen. Only the bottom dock stays visible. This is the
 * "mini Android home" mode — user can still tap dock icons to navigate.
 *
 * Swipe-up gesture on dock (when collapsed): grid slides back up.
 */

/** One launcher page descriptor. */
data class LauncherPage(
    val id: String,
    val title: String,
    val icon: ImageVector,
    /** Squircle background color. */
    val tileColor: Color = ClaudeAccent,
    /** Whether this page is also pinned to the dock (defaults: 4 main pages). */
    val pinnedToDock: Boolean = false
)

/**
 * Default page set. First 4 are pinned to the dock (most-used); the upper
 * grid shows ALL 8 (dock items appear in both places, exactly like Android).
 */
val DefaultLauncherPages: List<LauncherPage> = listOf(
    LauncherPage("renters",   "Mijozlar",    Icons.Default.Person,               tileColor = Color(0xFF2E7D32), pinnedToDock = true),
    LauncherPage("scooters",  "Skuterlar",   Icons.Default.DirectionsBike,       tileColor = Color(0xFF1565C0), pinnedToDock = true),
    LauncherPage("contracts", "Kontraktlar", Icons.Default.Apps,                 tileColor = Color(0xFFE65100), pinnedToDock = true),
    LauncherPage("finansi",   "Moliya",      Icons.Default.AccountBalanceWallet, tileColor = Color(0xFFB8862B), pinnedToDock = true),
    LauncherPage("reports",   "Hisobotlar",  Icons.Default.Assessment,           tileColor = Color(0xFF6A1B9A)),
    LauncherPage("history",   "Tarix",       Icons.Default.History,              tileColor = Color(0xFF00838F)),
    LauncherPage("trash",     "Chiqindi",    Icons.Default.Delete,               tileColor = Color(0xFFC62828)),
    LauncherPage("settings",  "Sozlamalar",  Icons.Default.Settings,             tileColor = Color(0xFF455A64))
)

/* ════════════════════════════════════════════════════════════════════
   LAUNCHER — Android-home-screen style.
   ════════════════════════════════════════════════════════════════════ */

/**
 * Full-screen launcher shown after the splash screen.
 *
 * @param pages list of pages to show.
 * @param onPageClick called with page id when user taps any icon (grid or dock).
 */
@Composable
fun LauncherScreen(
    pages: List<LauncherPage> = DefaultLauncherPages,
    onPageClick: (String) -> Unit,
    /** Called after the launcher curtain is pulled down, revealing MainScreen. */
    onCollapseToMain: () -> Unit = {},
    onDrawerPullUp: () -> Unit = {}  // kept for source compatibility
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // ── Continuous curtain progress ────────────────────────────────────
    // 0f   = fully expanded (grid + title + dots visible)
    // 0.5f = half-collapsed (grid partially slid down, dock still visible)
    // 1f   = fully collapsed (only dock visible)
    //
    // User can drag anywhere on the upper region to move the curtain. On
    // release, the curtain snaps to the nearest of {0, 0.5, 1}. The dock
    // stays anchored at the bottom — it never moves.
    val collapseProgress = remember { Animatable(0f) }
    var dragProgress by remember { mutableStateOf<Float?>(null) }  // non-null while dragging
    var gridHeightPx by remember { mutableStateOf(1) }

    val effectiveProgress = dragProgress ?: collapseProgress.value

    fun snapTo(target: Float) {
        scope.launch {
            collapseProgress.animateTo(target, tween(280))
            if (target >= 0.95f) onCollapseToMain()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ClaudeBackground.copy(alpha = 1f - effectiveProgress))
    ) {

        // ── Upper region: title + grid + page dots ───────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.82f)
                .onGloballyPositioned { coords ->
                    gridHeightPx = coords.size.height.coerceAtLeast(1)
                }
                .offset {
                    val shift = (gridHeightPx * effectiveProgress).toInt() +
                        (with(density) { 80.dp.toPx() }).toInt()
                    IntOffset(0, shift)
                }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { dragProgress = effectiveProgress },
                        onDragEnd = {
                            val p = dragProgress
                            dragProgress = null
                            if (p != null) {
                                // Snap to nearest of {0, 0.5, 1}
                                val target = when {
                                    p < 0.25f -> 0f
                                    p < 0.75f -> 0.5f
                                    else -> 1f
                                }
                                snapTo(target)
                            }
                        },
                        onDragCancel = {
                            dragProgress = null
                        },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            val delta = dragAmount / gridHeightPx.coerceAtLeast(1)
                            val newProgress = (effectiveProgress + delta).coerceIn(0f, 1f)
                            dragProgress = newProgress
                            // Live-update animatable so non-drag state is in sync
                            scope.launch { collapseProgress.snapTo(newProgress) }
                        }
                    )
                }
                .padding(top = 56.dp, start = 16.dp, end = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Scooter Rent",
                style = MaterialTheme.typography.headlineMedium,
                color = ClaudeText,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "Boshqaruv tizimi",
                style = MaterialTheme.typography.bodySmall,
                color = ClaudeTextSecondary,
                fontWeight = FontWeight.Normal
            )

            Spacer(Modifier.height(40.dp))

            val rows = pages.chunked(4)
            rows.forEach { rowPages ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    rowPages.forEach { page ->
                        LauncherTile(
                            page = page,
                            onClick = { onPageClick(page.id) }
                        )
                    }
                }
                Spacer(Modifier.height(28.dp))
            }

            Spacer(Modifier.weight(1f))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                repeat(4) { i ->
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(if (i == 0) 8.dp else 6.dp)
                            .clip(CircleShape)
                            .background(
                                if (i == 0) ClaudeAccent
                                else ClaudeTextSecondary.copy(alpha = 0.45f)
                            )
                    )
                }
            }
        }

        // ── Bottom dock (4 main pages, always visible) ───────────────────
        // Stays anchored at the bottom regardless of curtain progress.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(106.dp)
                .background(ClaudeCard.copy(alpha = 1f - effectiveProgress * 0.15f))
                .pointerInput(effectiveProgress) {
                    detectVerticalDragGestures(
                        onDragStart = { dragProgress = effectiveProgress },
                        onDragEnd = {
                            val p = dragProgress
                            dragProgress = null
                            if (p != null) {
                                val target = when {
                                    p < 0.25f -> 0f
                                    p < 0.75f -> 0.5f
                                    else -> 1f
                                }
                                snapTo(target)
                            }
                        },
                        onDragCancel = { dragProgress = null },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            val delta = dragAmount / gridHeightPx.coerceAtLeast(1)
                            val newProgress = (effectiveProgress + delta).coerceIn(0f, 1f)
                            dragProgress = newProgress
                            scope.launch { collapseProgress.snapTo(newProgress) }
                        }
                    )
                }
                .padding(horizontal = 24.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                pages.filter { it.pinnedToDock }.forEach { page ->
                    DockTile(
                        page = page,
                        onClick = { onPageClick(page.id) }
                    )
                }
            }
        }
    }
}

/* ──────────────────────────────────────────────────────────────────────
   Tile composables
   ────────────────────────────────────────────────────────────────────── */

/** Grid tile — squircle background + white icon + white label below. */
@Composable
private fun LauncherTile(
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
                .size(64.dp)
                .clip(RoundedCornerShape(20.dp))  // squircle-ish
                .background(page.tileColor)
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = page.icon,
                contentDescription = page.title,
                tint = Color.White,
                modifier = Modifier.size(34.dp)
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            page.title,
            style = MaterialTheme.typography.labelSmall,
            color = ClaudeText,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            textAlign = TextAlign.Center
        )
    }
}

/** Dock tile — same icon and readable label style as launcher grid. */
@Composable
private fun DockTile(
    page: LauncherPage,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(72.dp)
            .combinedClickable(onClick = onClick, onLongClick = {})
            .padding(vertical = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(page.tileColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(page.icon, page.title, tint = Color.White, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(3.dp))
        Text(
            page.title,
            style = MaterialTheme.typography.labelSmall,
            color = ClaudeText,
            fontSize = 10.sp,
            maxLines = 1,
            textAlign = TextAlign.Center
        )
    }
}

/* ════════════════════════════════════════════════════════════════════
   COMPAT SHIM — old curtain drawer API kept as no-op so MainActivity
   still compiles if it references NavigationDrawerSheet. The new
   swipe-down-to-dock behavior replaces the curtain entirely.
   ════════════════════════════════════════════════════════════════════ */

/**
 * @deprecated The curtain drawer is replaced by the swipe-down-to-dock
 * behavior in [LauncherScreen]. Kept as a no-op for source compatibility.
 */
@Composable
fun NavigationDrawerSheet(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onPageSelect: (String) -> Unit
) {
    // No-op — new design uses swipe-down on launcher to reveal dock-only mode.
}
