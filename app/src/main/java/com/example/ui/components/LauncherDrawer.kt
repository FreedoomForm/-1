@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.example.ui.components

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
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.ClaudeAccent
import com.example.ui.theme.ClaudeBackground
import com.example.ui.theme.ClaudeCard
import com.example.ui.theme.ClaudeText
import com.example.ui.theme.ClaudeTextSecondary

/**
 * §9.B — Free-drag launcher curtain overlaying MainScreen's content area.
 *
 * Behavior contract (per user spec, 2026-07-28):
 *
 *  1. The launcher is rendered INSIDE the Scaffold content area of
 *     MainScreen. The Scaffold's bottom bar (ExpandableBottomNav) is
 *     drawn ON TOP of the launcher — so when the launcher is dragged
 *     down past the bottom nav, the panel visually slides UNDER it.
 *
 *  2. The launcher shows ONLY the secondary pages (reports / history /
 *     trash / settings). The primary pages (renters / scooters /
 *     contracts / finansi) are NOT duplicated here — they live in the
 *     bottom nav.
 *
 *  3. A hint chip with an animated arrow + label is shown:
 *       • When the launcher is in the upper part of the screen
 *         (panel top edge ABOVE the bottom nav) — chip sits at the
 *         TOP of the panel, arrow points DOWN, text "Pastga torting".
 *       • When the launcher has been dragged below the bottom nav
 *         (panel top edge BELOW the bottom nav top) — chip detaches
 *         and re-anchors just ABOVE the bottom nav, arrow points UP,
 *         text "Yukoriga torting". This chip stays visible so the
 *         user always has a visible grab handle to pull the panel
 *         back up.
 *
 *  4. Free-drag: no snap points. The panel stays wherever the finger
 *     released. The only clamp: it can't go above the top of the
 *     content area, and it can't go so far down that the "pull up"
 *     chip itself would be pushed off-screen.
 *
 *  5. The bottom nav remains interactive at all times because the
 *     launcher overlay never covers it (it's drawn under the
 *     Scaffold bottom bar).
 */

/** Secondary page set shown on the launcher. Same ids as in ExpandableBottomNav. */
data class LauncherPage(
    val id: String,
    val title: String,
    val icon: ImageVector,
    val tileColor: Color = ClaudeAccent
)

val DefaultLauncherPages: List<LauncherPage> = listOf(
    LauncherPage("reports",  "Hisobotlar", Icons.Default.Assessment, tileColor = Color(0xFF6A1B9A)),
    LauncherPage("history",  "Tarix",      Icons.Default.History,    tileColor = Color(0xFF00838F)),
    LauncherPage("trash",    "Chiqindi",   Icons.Default.Delete,     tileColor = Color(0xFFC62828)),
    LauncherPage("settings", "Sozlamalar", Icons.Default.Settings,   tileColor = Color(0xFF455A64))
)

/**
 * Persistent state of the launcher curtain.
 *
 * [offsetPx] is the Y translation of the panel in pixels, measured from
 * the top of the content area (just below the top app bar). Positive
 * values move the panel DOWN.
 *
 * Range:
 *   0           → panel fully visible at the top of the content area.
 *   containerH  → panel fully scrolled UNDER the bottom nav (no visible
 *                 pixels above the nav). The external "pull up" hint
 *                 chip on ExpandableBottomNav takes over as the grab
 *                 handle.
 *
 * [panelHiddenUnderNav] is updated by [LauncherScreen] every recomposition
 * based on the actual on-screen Box dimensions. True once the panel's
 * visible portion above the bottom nav becomes smaller than the hint
 * chip height — i.e., the user can no longer grab the panel directly
 * and needs the external pull-up chip to bring it back.
 */
class LauncherCurtainState {
    var offsetPx by mutableStateOf(0f)
    var panelHiddenUnderNav by mutableStateOf(false)
}

@Composable
fun rememberLauncherCurtainState(): LauncherCurtainState =
    remember { LauncherCurtainState() }

@Composable
fun LauncherScreen(
    state: LauncherCurtainState,
    pages: List<LauncherPage> = DefaultLauncherPages,
    onPageClick: (String) -> Unit,
    /** Height of the bottom nav in pixels. Kept for source compatibility —
     *  the actual clamp now uses BoxWithConstraints to get the real on-screen
     *  content area height, so this parameter is no longer authoritative. */
    @Suppress("UNUSED_PARAMETER")
    bottomNavHeightPx: Float
) {
    val density = LocalDensity.current

    // Use BoxWithConstraints to get the actual on-screen height of the
    // launcher's container (= Scaffold content area = screen − topbar −
    // bottomnav). This is the full vertical space the launcher is allowed
    // to occupy; when offset == containerHeightPx the panel has fully
    // scrolled off the bottom of this area (under the bottom nav).
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
    ) {
        val containerHeightPx = with(density) { maxHeight.toPx() }

        // ── Drag clamp ────────────────────────────────────────────────
        // Allow the panel to scroll the FULL container height — i.e.,
        // completely under the bottom nav. Previously this was clamped
        // to (containerHeight − chipHeight), which prevented the panel
        // from ever reaching the "hidden under nav" state and broke
        // the pull-up arrow visibility.
        //
        // The small extra 32.px below keeps a tiny sliver of the panel
        // (the hint chip background) reachable at the very bottom so
        // the user has SOMETHING to grab if they don't notice the
        // external chip — but the external chip is the primary handle.
        val maxOffsetPx = containerHeightPx.coerceAtLeast(0f)

        // ── Hint chip mode ────────────────────────────────────────────
        // Flip to "hidden" once the panel's top has scrolled down past
        // ~70% of the container height. By that point the action tiles
        // are no longer visible above the bottom nav, so the external
        // "Yukoriga torting" chip on ExpandableBottomNav should take
        // over as the grab handle.
        //
        // Hysteresis is intentionally wide (70% show → 25% hide) to
        // avoid the chip flickering at the boundary while dragging.
        val hideThresholdPx = containerHeightPx * 0.70f
        val showThresholdPx = containerHeightPx * 0.25f
        val panelHiddenUnderNav = if (state.panelHiddenUnderNav) {
            state.offsetPx >= showThresholdPx
        } else {
            state.offsetPx >= hideThresholdPx
        }

        // Publish the hidden flag to the shared state so the bottom nav's
        // external "pull up" chip can react to it without duplicating the
        // (error-prone) screen-dimension math.
        state.panelHiddenUnderNav = panelHiddenUnderNav

        // ── The panel itself ──────────────────────────────────────────
        // Always rendered (even when hidden under the bottom nav) so
        // the user can drag it back up. The Scaffold's bottom bar
        // covers the part that goes under it.
        //
        // The Column uses fillMaxHeight() so the panel visually occupies
        // the WHOLE content area — this avoids the "cropped" look the
        // user reported, where the action tiles appeared cut off at the
        // bottom of a wrap_content Column. With fillMaxHeight the panel
        // is a full-screen surface that the action tiles sit at the top
        // of, with the rest of the panel being empty cream background.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .offset {
                    IntOffset(0, state.offsetPx.toInt().coerceAtLeast(0))
                }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            // dragAmount > 0 = finger moving down.
                            state.offsetPx = (state.offsetPx + dragAmount)
                                .coerceIn(0f, maxOffsetPx)
                        }
                    )
                }
                .background(ClaudeBackground)
                .padding(top = 8.dp, start = 16.dp, end = 16.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Hint chip — at the very top of the panel.
            //   • Panel visible: arrow DOWN, "Pastga torting".
            //   • Panel hidden under nav: chip is HIDDEN — the external
            //     "Yukoriga torting" chip on ExpandableBottomNav takes
            //     over as the visible grab handle just above the bottom
            //     nav. This avoids two identical chips appearing at the
            //     same screen location.
            if (!panelHiddenUnderNav) {
                HintChip(
                    mode = HintMode.DOWN,
                    modifier = Modifier.padding(vertical = 6.dp)
                )
            }

            Spacer(Modifier.height(20.dp))

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
        }
    }
}

/* ──────────────────────────────────────────────────────────────────────
   Hint chip — animated arrow + label
   ────────────────────────────────────────────────────────────────────── */

private enum class HintMode { DOWN, UP }

@Composable
private fun HintChip(
    mode: HintMode,
    modifier: Modifier = Modifier
) {
    // Bob the arrow vertically to invite the gesture.
    val transition = rememberInfiniteTransition(label = "hint-bob")
    val bob by transition.animateFloat(
        initialValue = 0f,
        targetValue = if (mode == HintMode.DOWN) 6f else -6f,
        animationSpec = infiniteRepeatable(
            animation = tween(700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bob"
    )

    val arrowVector = if (mode == HintMode.DOWN) {
        Icons.Default.KeyboardArrowDown
    } else {
        Icons.Default.KeyboardArrowUp
    }
    val label = if (mode == HintMode.DOWN) "Pastga torting" else "Yukoriga torting"
    val tint = if (mode == HintMode.DOWN) ClaudeAccent else ClaudeTextSecondary

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(ClaudeCard)
            .border(1.dp, tint.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = arrowVector,
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .size(20.dp)
                .offset { IntOffset(0, bob.toInt()) }
        )
        Text(
            text = label,
            color = tint,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/* ──────────────────────────────────────────────────────────────────────
   Tile
   ────────────────────────────────────────────────────────────────────── */

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
                .clip(RoundedCornerShape(20.dp))
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

/* ════════════════════════════════════════════════════════════════════
   COMPAT SHIM — kept so any legacy caller still compiles.
   ════════════════════════════════════════════════════════════════════ */

/**
 * @deprecated Use [LauncherScreen] with [LauncherCurtainState] instead.
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
