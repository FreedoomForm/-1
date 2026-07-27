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

    // Collapse progress: 0f = fully expanded (grid visible), 1f = collapsed (only dock).
    val collapseProgress = remember { Animatable(0f) }
    var gridHeightPx by remember { mutableStateOf(1) }

    fun collapse() {
        scope.launch {
            collapseProgress.animateTo(1f, tween(280))
            // The former implementation only hid the grid inside the full
            // launcher surface, leaving its wallpaper on top of MainScreen.
            // After the curtain animation, reveal the actual working page.
            onCollapseToMain()
        }
    }
    fun expand() {
        scope.launch {
            collapseProgress.animateTo(0f, tween(280))
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Same neutral background as working pages. Its alpha follows
            // curtain collapse so the already-composed renters page appears
            // progressively behind the moving launcher icons.
            .background(ClaudeBackground.copy(alpha = 1f - collapseProgress.value))
    ) {

        // ── Upper region: title + grid + page dots ───────────────────────
        // This entire region slides DOWN off-screen as collapseProgress → 1.
        // translate Y by gridHeightPx * collapseProgress + a bit extra so it
        // fully disappears under the dock.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.82f)  // leave 18% for dock at bottom
                .onGloballyPositioned { coords ->
                    gridHeightPx = coords.size.height.coerceAtLeast(1)
                }
                .offset {
                    val shift = (gridHeightPx * collapseProgress.value).toInt() +
                        (with(density) { 80.dp.toPx() }).toInt()
                    IntOffset(0, shift)
                }
                // Swipe down anywhere on the grid → collapse.
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            // dragAmount > 0 = finger moving down → collapse
                            if (dragAmount > 6f && collapseProgress.value < 0.5f) {
                                collapse()
                            } else if (dragAmount < -6f && collapseProgress.value > 0.5f) {
                                expand()
                            }
                        }
                    )
                }
                .padding(top = 56.dp, start = 16.dp, end = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── Title at top (like MIUI clock + date) ─────────────────────
            Text(
                "Scooter Rent",
                style = MaterialTheme.typography.headlineMedium,
                color = ClaudeDarkText,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "Boshqaruv tizimi",
                style = MaterialTheme.typography.bodySmall,
                color = ClaudeDarkText.copy(alpha = 0.72f),
                fontWeight = FontWeight.Normal
            )

            Spacer(Modifier.height(40.dp))

            // ── Grid of all pages (4 columns × 2 rows = 8 items) ─────────
            // Using Column{Row,Row} for a stable 4×2 layout (pages.count = 8).
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

            // ── Page indicator dots (always 1 active = 4 dots, page 1 of 1) ──
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
                                if (i == 0) Color.White
                                else Color.White.copy(alpha = 0.45f)
                            )
                    )
                }
            }
        }

        // ── Bottom dock (4 main pages, always visible) ───────────────────
        // Same background as the launcher grid; only a subtle divider makes
        // it a dock. This avoids a visually unrelated dark lower panel.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(106.dp)
                .background(ClaudeBackground.copy(alpha = 1f - collapseProgress.value * 0.15f))
                .pointerInput(collapseProgress.value) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            // dragAmount < 0 = finger moving up → expand
                            if (dragAmount < -6f && collapseProgress.value > 0.5f) {
                                expand()
                            } else if (dragAmount > 6f && collapseProgress.value < 0.5f) {
                                collapse()
                            }
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
            color = ClaudeDarkText,
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
            color = ClaudeDarkText,
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
