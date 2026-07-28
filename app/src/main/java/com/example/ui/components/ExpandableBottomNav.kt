package com.example.ui.components

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

/**
 * @param selectedId  id of currently selected page (for highlighting).
 * @param onPageClick called with page id when user taps any icon.
 */
@Composable
fun ExpandableBottomNav(
    selectedId: String,
    onPageClick: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ClaudeCard)
            .border(1.dp, Color.Black.copy(alpha = 0.06f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        PrimaryNavPages.forEach { page ->
            NavTile(page, page.id == selectedId, { onPageClick(page.id) }, size = 60.dp, iconSize = 30.dp, labelSize = 11.sp)
        }
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
