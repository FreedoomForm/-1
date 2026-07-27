@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
 * §9.A — In-app launcher styled as a draggable Android home panel.
 *
 * Layout (per user's latest spec — split into two parts):
 *
 *  ```
 *  ┌────────────────────────────────────────┐
 *  │         ⌄ (animated down arrow)        │  ← hint: pull down
 *  │                                        │
 *  │   ┌──┐   ┌──┐   ┌──┐   ┌──┐            │  ← secondary row (slides
 *  │   │📊│   │🕓│   │🗑│   │⚙│             │     down with the upper
 *  │   └──┘   └──┘   └──┘   └──┘            │     panel)
 *  │   Hisob  Tarix  Chiq   Sozlam          │
 *  │                                        │
 *  │  ══════════════════════════════════    │  ← divider
 *  │   ┌──┐   ┌──┐   ┌──┐   ┌──┐            │  ← primary row (FIXED —
 *  │   │📋│   │🛵│   │📑│   │💰│            │     stays in place when
 *  │   └──┘   └──┘   └──┘   └──┘            │     upper panel slides)
 *  │   Mijoz  Skut   Kontr  Moliya          │
 *  └────────────────────────────────────────┘
 *  ```
 *
 * Swipe-down on the upper panel → upper panel slides DOWN off-screen.
 * The 4 primary icons at the bottom STAY VISIBLE — user can tap them
 * to navigate, or swipe up to bring the upper panel back.
 *
 * Swipe-up on the bottom fixed row → upper panel slides back UP.
 *
 * Tapping any icon (secondary or primary) → onPageClick → launcher
 * fully dismisses and the host navigates to the tapped page.
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
 *   • primary   — the 4 most-used pages (bottom fixed row)
 *   • secondary — the 4 utility pages (top sliding row)
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
   LAUNCHER — split panel design.
   ════════════════════════════════════════════════════════════════════ */

/**
 * Full-screen launcher shown after the splash screen.
 *
 * Two parts:
 *   1. Upper sliding panel (animated arrow + secondary icons) — slides
 *      down off-screen on swipe-down, slides back up on swipe-up.
 *   2. Bottom fixed row (4 primary icons) — always visible, stays in
 *      place when the upper panel slides down.
 *
 * @param onPageClick called with page id when user taps any icon.
 * @param onCollapsed  called when user dismisses the launcher (not used
 *                     in the new design — dismissal only happens via
 *                     tapping an icon, which calls onPageClick).
 */
@Composable
fun LauncherScreen(
    onPageClick: (String) -> Unit,
    onCollapsed: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // Collapse progress: 0f = upper panel fully visible, 1f = upper panel
    // slid off-screen (only bottom primary row visible).
    val collapseProgress = remember { Animatable(0f) }
    var upperHeightPx by remember { mutableStateOf(1) }

    fun collapse() {
        scope.launch { collapseProgress.animateTo(1f, tween(320)) }
    }
    fun expand() {
        scope.launch { collapseProgress.animateTo(0f, tween(320)) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ClaudeBackground)
    ) {
        // ── Bottom fixed row: 4 primary icons (always visible) ──────────
        // This row STAYS in place — it does NOT slide with the upper panel.
        // Swipe-up on this row → expand the upper panel back.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(ClaudeCard)
                .border(1.dp, Color.Black.copy(alpha = 0.06f))
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            // dragAmount < 0 = finger moving up → expand
                            if (dragAmount < -8f && collapseProgress.value > 0.5f) {
                                expand()
                            }
                        }
                    )
                }
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                PrimaryLauncherPages.forEach { page ->
                    LauncherTile(
                        page = page,
                        onClick = { onPageClick(page.id) },
                        size = 60.dp,
                        iconSize = 30.dp,
                        labelSize = 11.sp
                    )
                }
            }
        }

        // ── Upper sliding panel: arrow + secondary icons ────────────────
        // This panel slides DOWN off-screen as collapseProgress → 1.
        // It covers ~80% of the screen height; the bottom 20% is reserved
        // for the fixed primary row.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.82f)
                .onGloballyPositioned { coords ->
                    upperHeightPx = coords.size.height.coerceAtLeast(1)
                }
                .offset {
                    val shift = (upperHeightPx * collapseProgress.value).toInt()
                    IntOffset(0, shift)
                }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            // dragAmount > 0 = finger moving down → collapse
                            if (dragAmount > 8f && collapseProgress.value < 0.5f) {
                                collapse()
                            } else if (dragAmount < -8f && collapseProgress.value > 0.5f) {
                                expand()
                            }
                        }
                    )
                }
                .padding(top = 24.dp, start = 16.dp, end = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── Animated down arrow (hint: pull down to collapse) ────────
            AnimatedDownArrow()

            Spacer(Modifier.height(36.dp))

            // ── Title ───────────────────────────────────────────────────
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

            Spacer(Modifier.height(40.dp))

            // ── Secondary icons row ─────────────────────────────────────
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

            // ── Subtle hint text at bottom of sliding panel ─────────────
            Text(
                "Pastga suring — yuqori panelni yopish uchun",
                style = MaterialTheme.typography.labelSmall,
                color = ClaudeDarkText.copy(alpha = 0.4f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Normal,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

/* ──────────────────────────────────────────────────────────────────────
   Animated down arrow — bobs up and down to hint "pull down to collapse"
   ────────────────────────────────────────────────────────────────────── */
@Composable
private fun AnimatedDownArrow() {
    // Infinite transition: arrow bobs up and down 0..10dp repeatedly.
    val infiniteTransition = rememberInfiniteTransition(label = "arrowBob")
    val arrowOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 10f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "arrowOffset"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.offset { IntOffset(0, arrowOffset.toInt()) }
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(ClaudeAccent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = "Pastga suring",
                tint = ClaudeAccent,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Pastga suring",
            style = MaterialTheme.typography.labelSmall,
            color = ClaudeAccent,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium
        )
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
   still compiles if it references NavigationDrawerSheet.
   ════════════════════════════════════════════════════════════════════ */

/**
 * @deprecated Replaced by swipe behavior in [LauncherScreen].
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
