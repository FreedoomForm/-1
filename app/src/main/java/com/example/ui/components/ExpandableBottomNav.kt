@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.ClaudeAccent
import com.example.ui.theme.ClaudeCard
import com.example.ui.theme.ClaudeDarkText

/**
 * §9.A — Bottom navigation bar (PRIMARY icons only).
 *
 * This is now a SINGLE static row of 4 primary icons:
 *   Mijozlar · Skuterlar · Kontraktlar · Moliya
 *
 * The secondary shortcuts (Hisobotlar / Tarix / Chiqindi / Sozlamalar)
 * live exclusively in the LauncherScreen free-drag curtain — the user
 * drags the curtain down from the top of the renters page to reveal
 * them. Having both this row's secondary section AND the launcher
 * curtain caused a duplicate-panel bug; the secondary row has been
 * removed here to fix it.
 *
 * Tapping the "Skuter Ijarasi" title in the TopAppBar still opens
 * the launcher curtain (see MainScreen).
 *
 * @param showPullUpHint  when true, an animated "Yukoriga torting"
 *                        chip with an up-arrow is rendered just
 *                        ABOVE the bottom nav. The MainScreen sets
 *                        this whenever the launcher curtain has been
 *                        dragged down past the bottom nav's top edge
 *                        — so the user always has a visible grab
 *                        handle to pull the panel back up.
 * @param onPullUpClick   invoked when the user taps the pull-up
 *                        hint chip. Default resets the curtain to
 *                        the top (handled by MainScreen).
 */

/** One navigation entry. */
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

@Composable
fun ExpandableBottomNav(
    selectedId: String,
    onPageClick: (String) -> Unit,
    showPullUpHint: Boolean = false,
    onPullUpClick: () -> Unit = {}
) {
    // ── "Pull up" hint chip ──────────────────────────────────────────
    // Rendered ABOVE the bottom nav when the launcher curtain has
    // been dragged down past the nav's top edge. Animates in from
    // below + fades in so it doesn't pop. Tapping it pulls the
    // curtain back to the top.
    Box(
        modifier = Modifier.fillMaxWidth()
    ) {
        // The hint chip sits just above the nav row.
        AnimatedVisibility(
            visible = showPullUpHint,
            enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { it },
            exit = fadeOut(tween(160)) + slideOutVertically(tween(180)) { it },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset { IntOffset(0, -8) }
        ) {
            PullUpHintChip(onClick = onPullUpClick)
        }

        // ── The bottom nav row itself ────────────────────────────────
        // Slightly taller than before (vertical = 14.dp instead of
        // 10.dp) per user request to "raise the bottom panel a bit".
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ClaudeCard)
                .border(1.dp, Color.Black.copy(alpha = 0.06f))
                .padding(horizontal = 12.dp, vertical = 14.dp),
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
   Pull-up hint chip — animated up-arrow + label "Yukoriga torting"
   ────────────────────────────────────────────────────────────────────── */
@Composable
private fun PullUpHintChip(
    onClick: () -> Unit
) {
    val transition = rememberInfiniteTransition(label = "pull-up-bob")
    val bob by transition.animateFloat(
        initialValue = 0f,
        targetValue = -6f,
        animationSpec = infiniteRepeatable(
            animation = tween(700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bob"
    )

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(ClaudeCard)
            .border(1.dp, ClaudeAccent.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
            .combinedClickable(onClick = onClick, onLongClick = {})
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(ClaudeAccent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowUp,
                contentDescription = null,
                tint = ClaudeAccent,
                modifier = Modifier
                    .size(22.dp)
                    .offset { IntOffset(0, bob.toInt()) }
            )
        }
        Text(
            text = "Yukoriga torting",
            color = ClaudeAccent,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/* ──────────────────────────────────────────────────────────────────────
   Tile composable — squircle background + white icon + label below.
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
