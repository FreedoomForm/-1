@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
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
import com.example.ui.theme.ClaudeBackground
import com.example.ui.theme.ClaudeCard
import com.example.ui.theme.ClaudeDarkText

/**
 * §9.A — Expandable bottom navigation panel.
 *
 * Replaces both the old full-screen LauncherScreen overlay and the
 * fixed NavigationBar. This is a SINGLE unified panel that lives at
 * the bottom of the screen and can be pulled UP to reveal a second
 * row of secondary icons.
 *
 * ```
 *  COLLAPSED (default):                  EXPANDED (after pull-up):
 *  ┌──────────────────────────────┐      ┌──────────────────────────────┐
 *  │                              │      │         ⌄ (animated)         │
 *  │       (main content)         │      │                              │
 *  │                              │      │  ┌──┐ ┌──┐ ┌──┐ ┌──┐         │
 *  │                              │      │  │📊│ │🕓│ │🗑│ │⚙│          │
 *  │                              │      │  └──┘ └──┘ └──┘ └──┘         │
 *  │                              │      │  Rep  His  Trsh Set          │
 *  │                              │      │  ════════════════════        │
 *  ├──────────────────────────────┤      ├──────────────────────────────┤
 *  │ 👤  🛵  📑  💰               │      │ 👤  🛵  📑  💰               │
 *  │ Mij Sku Kon Fin              │      │ Mij Sku Kon Fin              │
 *  └──────────────────────────────┘      └──────────────────────────────┘
 * ```
 *
 * The 4 primary icons are ALWAYS visible at the bottom (they are the
 * bottom nav in collapsed state and the bottom row in expanded state).
 *
 * Pulling UP on the panel reveals the secondary row + animated arrow
 * above it. Pulling DOWN (or tapping the arrow) collapses it back.
 *
 * The whole expanded panel slides up as a unit — the secondary row
 * emerges from BEHIND the primary row (which stays anchored at the
 * bottom of the panel).
 */

/** One navigation entry — used by both rows. */
data class NavPage(
    val id: String,
    val title: String,
    val icon: ImageVector,
    val tileColor: Color = ClaudeAccent
)

/** Primary row — always visible (4 most-used pages). */
val PrimaryNavPages: List<NavPage> = listOf(
    NavPage("renters",   "Mijozlar",    Icons.Default.Person,               tileColor = Color(0xFF2E7D32)),
    NavPage("scooters",  "Skuterlar",   Icons.Default.DirectionsBike,       tileColor = Color(0xFF1565C0)),
    NavPage("contracts", "Kontraktlar", Icons.Default.Apps,                 tileColor = Color(0xFFE65100)),
    NavPage("finansi",   "Moliya",      Icons.Default.AccountBalanceWallet, tileColor = Color(0xFFB8862B))
)

/** Secondary row — revealed when panel is pulled up. */
val SecondaryNavPages: List<NavPage> = listOf(
    NavPage("reports",   "Hisobotlar",  Icons.Default.Assessment, tileColor = Color(0xFF6A1B9A)),
    NavPage("history",   "Tarix",       Icons.Default.History,   tileColor = Color(0xFF00838F)),
    NavPage("trash",     "Chiqindi",    Icons.Default.Delete,    tileColor = Color(0xFFC62828)),
    NavPage("settings",  "Sozlamalar",  Icons.Default.Settings,  tileColor = Color(0xFF455A64))
)

/**
 * @param selectedId  id of currently selected page (for highlighting).
 * @param onPageClick called with page id when user taps any icon.
 * @param expanded    whether the panel is currently expanded (controlled
 *                    state from parent so the rest of the app can also
 *                    expand/collapse programmatically).
 * @param onExpandedChange called when user gesture wants to toggle.
 */
@Composable
fun ExpandableBottomNav(
    selectedId: String,
    onPageClick: (String) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit
) {
    fun toggleExpanded() = onExpandedChange(!expanded)

    // Important: this is a normal Column, not an overlay. When it expands,
    // Scaffold measures the larger bottom bar and moves content upward. This
    // prevents secondary tiles from drawing over a table or below system nav.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ClaudeCard)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        if (dragAmount < -10f && !expanded) onExpandedChange(true)
                        else if (dragAmount > 10f && expanded) onExpandedChange(false)
                    }
                )
            }
    ) {
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(expandFrom = Alignment.Bottom) + fadeIn(),
            exit = shrinkVertically(shrinkTowards = Alignment.Bottom) + fadeOut()
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().background(ClaudeBackground).padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AnimatedDownArrow(alpha = 1f, onClick = { onExpandedChange(false) })
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SecondaryNavPages.forEach { page ->
                        NavTile(page, page.id == selectedId, { onPageClick(page.id) }, size = 60.dp, iconSize = 30.dp, labelSize = 11.sp)
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth()
                .background(ClaudeCard)
                .border(1.dp, Color.Black.copy(alpha = 0.06f))
                .combinedClickable(onClick = { toggleExpanded() }, onLongClick = {})
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            PrimaryNavPages.forEach { page ->
                NavTile(page, page.id == selectedId, { onPageClick(page.id) }, size = 60.dp, iconSize = 30.dp, labelSize = 11.sp)
            }
        }
    }
}

/* ──────────────────────────────────────────────────────────────────────
   Animated down arrow — bobs up and down to hint "pull down to collapse"
   ────────────────────────────────────────────────────────────────────── */
@Composable
private fun AnimatedDownArrow(
    alpha: Float,
    onClick: () -> Unit
) {
    if (alpha < 0.05f) return  // skip rendering when invisible

    val infiniteTransition = rememberInfiniteTransition(label = "arrowBob")
    val arrowOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 8f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "arrowOffset"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .offset { IntOffset(0, arrowOffset.toInt()) }
            .combinedClickable(onClick = onClick, onLongClick = {})
            .padding(top = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(ClaudeAccent.copy(alpha = 0.12f * alpha)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = "Yopish uchun pastga suring",
                tint = ClaudeAccent.copy(alpha = alpha),
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            "Pastga suring",
            style = MaterialTheme.typography.labelSmall,
            color = ClaudeAccent.copy(alpha = alpha),
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

/* ──────────────────────────────────────────────────────────────────────
   Tile composable — squircle background + white icon + label below.
   Same style for both rows.
   ────────────────────────────────────────────────────────────────────── */

@Composable
private fun NavTile(
    page: NavPage,
    selected: Boolean,
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
                .clip(RoundedCornerShape(20.dp))
                .background(page.tileColor)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) ClaudeAccent else Color.Black.copy(alpha = 0.06f),
                    shape = RoundedCornerShape(20.dp)
                ),
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
            color = if (selected) ClaudeAccent else ClaudeDarkText,
            fontSize = labelSize,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            textAlign = TextAlign.Center
        )
    }
}
