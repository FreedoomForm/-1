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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
 * §9.A — In-app launcher styled as a minimal Android home screen.
 *
 * Layout (per user's latest request — clean white background, two rows):
 *
 *  ```
 *  ┌────────────────────────────────────────┐
 *  │  (white background)                    │
 *  │                                        │
 *  │   ┌──┐   ┌──┐   ┌──┐   ┌──┐            │  ← top row (4 secondary icons)
 *  │   │📊│   │🕓│   │🗑│   │⚙│             │     Hisobotlar / Tarix /
 *  │   └──┘   └──┘   └──┘   └──┘            │     Chiqindi / Sozlamalar
 *  │   Hisob  Tarix  Chiq   Sozlam          │
 *  │                                        │
 *  │                                        │
 *  │   ┌──┐   ┌──┐   ┌──┐   ┌──┐            │  ← main row (4 primary icons)
 *  │   │📋│   │🛵│   │📑│   │💰│            │     Mijozlar / Skuterlar /
 *  │   └──┘   └──┘   └──┘   └──┘            │     Kontraktlar / Moliya
 *  │   Mijoz  Skut   Kontr  Moliya          │
 *  └────────────────────────────────────────┘
 *  ```
 *
 * Swipe-down gesture (anywhere on launcher): the entire launcher panel
 * slides DOWN off-screen, revealing the Renters page (or whatever main
 * view is underneath) behind it. This is the new "open the app" gesture —
 * the user swipes the launcher away like an Android home screen curtain.
 *
 * The previous bottom dock (4 translucent tiles) is REMOVED — the user
 * wants only the two rows above, white background, no decorative wallpaper.
 */

/** One launcher page descriptor. */
data class LauncherPage(
    val id: String,
    val title: String,
    val icon: ImageVector,
    /** Squircle background color. */
    val tileColor: Color = ClaudeAccent
)

/**
 * Default page set split into two visual rows:
 *   • primary   — the 4 most-used pages (main row, large tiles)
 *   • secondary — the 4 utility pages (top row, slightly smaller tiles)
 */
val PrimaryLauncherPages: List<LauncherPage> = listOf(
    LauncherPage("renters",   "Mijozlar",    Icons.Default.Person,               tileColor = Color(0xFF2E7D32)),
    LauncherPage("scooters",  "Skuterlar",   Icons.Default.DirectionsBike,       tileColor = Color(0xFF1565C0)),
    LauncherPage("contracts", "Kontraktlar", Icons.Default.Apps,                 tileColor = Color(0xFFE65100)),
    LauncherPage("finansi",   "Moliya",      Icons.Default.AccountBalanceWallet, tileColor = Color(0xFFB8862B))
)

val SecondaryLauncherPages: List<LauncherPage> = listOf(
    LauncherPage("reports",   "Hisobotlar",  Icons.Default.Assessment,           tileColor = Color(0xFF6A1B9A)),
    LauncherPage("history",   "Tarix",       Icons.Default.History,              tileColor = Color(0xFF00838F)),
    LauncherPage("trash",     "Chiqindi",    Icons.Default.Delete,               tileColor = Color(0xFFC62828)),
    LauncherPage("settings",  "Sozlamalar",  Icons.Default.Settings,             tileColor = Color(0xFF455A64))
)

/* ════════════════════════════════════════════════════════════════════
   LAUNCHER — minimal Android-home style, white background.
   ════════════════════════════════════════════════════════════════════ */

/**
 * Full-screen launcher shown after the splash screen.
 *
 * Renders as an overlay ABOVE the main view (which is rendered underneath
 * by MainActivity). When the user swipes down, the launcher slides off the
 * bottom of the screen, revealing the Renters / main view underneath.
 *
 * @param onPageClick called with page id when user taps any icon.
 * @param onCollapsed  called when swipe-down animation completes — host
 *                     (MainActivity) uses it to fully dismiss the launcher.
 */
@Composable
fun LauncherScreen(
    onPageClick: (String) -> Unit,
    onCollapsed: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // Collapse progress: 0f = launcher fully visible, 1f = launcher slid
    // off-screen entirely. Once it reaches 1f we notify the host so it can
    // drop the overlay state.
    val collapseProgress = remember { Animatable(0f) }
    var panelHeightPx by remember { mutableStateOf(1) }
    var dismissed by remember { mutableStateOf(false) }

    fun collapse() {
        if (dismissed) return
        dismissed = true
        scope.launch {
            collapseProgress.animateTo(1f, tween(320))
            onCollapsed()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ClaudeBackground)  // white, matches app background
            .onGloballyPositioned { coords ->
                panelHeightPx = coords.size.height.coerceAtLeast(1)
            }
            .offset {
                // Slide the entire panel down off-screen as collapseProgress → 1.
                val shift = (panelHeightPx * collapseProgress.value).toInt()
                IntOffset(0, shift)
            }
            // Swipe down anywhere → collapse (reveal Renters page underneath).
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        // dragAmount > 0 = finger moving down → collapse
                        if (dragAmount > 8f && !dismissed) {
                            collapse()
                        }
                    }
                )
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 64.dp, start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── Title at top ─────────────────────────────────────────────
            Text(
                "Scooter Rent",
                style = MaterialTheme.typography.headlineMedium,
                color = ClaudeDarkText,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "Boshqaruv tizimi",
                style = MaterialTheme.typography.bodySmall,
                color = ClaudeDarkText.copy(alpha = 0.6f),
                fontWeight = FontWeight.Normal
            )

            Spacer(Modifier.height(36.dp))

            // ── Top row: 4 secondary (utility) pages ────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SecondaryLauncherPages.forEach { page ->
                    LauncherTile(
                        page = page,
                        onClick = { onPageClick(page.id) },
                        size = 56.dp,
                        iconSize = 28.dp,
                        labelSize = 10.sp
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            // ── Main row: 4 primary (most-used) pages ───────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                PrimaryLauncherPages.forEach { page ->
                    LauncherTile(
                        page = page,
                        onClick = { onPageClick(page.id) },
                        size = 64.dp,
                        iconSize = 32.dp,
                        labelSize = 11.sp
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // ── Subtle hint text — swipe down to open ───────────────────
            Text(
                "Pastga suring — Mijozlar sahifasini ochish uchun",
                style = MaterialTheme.typography.labelSmall,
                color = ClaudeDarkText.copy(alpha = 0.45f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Normal,
                textAlign = TextAlign.Center
            )
        }
    }
}

/* ──────────────────────────────────────────────────────────────────────
   Tile composable
   ────────────────────────────────────────────────────────────────────── */

/** Grid tile — squircle background + white icon + label below. */
@Composable
private fun LauncherTile(
    page: LauncherPage,
    onClick: () -> Unit,
    size: androidx.compose.ui.unit.Dp = 64.dp,
    iconSize: androidx.compose.ui.unit.Dp = 32.dp,
    labelSize: androidx.compose.ui.unit.TextUnit = 11.sp
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(76.dp)
            .combinedClickable(onClick = onClick, onLongClick = {})
            .padding(vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(20.dp))  // squircle-ish
                .background(page.tileColor)
                .border(1.dp, Color.Black.copy(alpha = 0.06f), RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = page.icon,
                contentDescription = page.title,
                tint = Color.White,
                modifier = Modifier.size(iconSize)
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            page.title,
            style = MaterialTheme.typography.labelSmall,
            color = ClaudeDarkText,
            fontSize = labelSize,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            textAlign = TextAlign.Center
        )
    }
}

/* ════════════════════════════════════════════════════════════════════
   COMPAT SHIM — old curtain drawer API kept as no-op so MainActivity
   still compiles if it references NavigationDrawerSheet. Removed in
   the latest design — swipe-down replaces the curtain entirely.
   ════════════════════════════════════════════════════════════════════ */

/**
 * @deprecated Replaced by swipe-down behavior in [LauncherScreen].
 * Kept as a no-op for source compatibility.
 */
@Composable
fun NavigationDrawerSheet(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onPageSelect: (String) -> Unit
) {
    // No-op.
}
