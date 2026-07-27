@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.example.ui.components

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.ui.layout.onGloballyPositioned
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
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
import com.example.ui.theme.ClaudeDivider
import com.example.ui.theme.ClaudeText
import com.example.ui.theme.ClaudeTextSecondary
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * §9.A — In-app launcher + interactive navigation drawer.
 *
 * Two layers, exactly as the user described:
 *
 * 1. **Launcher** — full-screen canvas with **free-form draggable** cube icons.
 *    Long-press + drag = move the cube anywhere on the canvas.
 *    Tap = open the page. Positions are persisted to SharedPreferences
 *    (so layout survives restarts).
 *
 * 2. **Drawer / curtain** — a swipe-up gesture at the bottom reveals the
 *    "Android-home-screen-mini" curtain. The curtain has:
 *      • 4 square icons in the bottom row (always visible once open)
 *      • Row 2 revealed as the curtain rises above 30%
 *      • Row 3 revealed as the curtain rises above 70%
 *    Drag height controls curtain height — same gesture, smooth UX.
 *
 * All cubes and curtain icons have 72dp / 56dp touch targets (§11).
 */

/** One launcher page descriptor. */
data class LauncherPage(
    val id: String,
    val title: String,
    val icon: ImageVector,
    val accentColor: Color = ClaudeAccent
)

/** Default page set (order = default position seed). */
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

/* ──────────────────────────────────────────────────────────────────────
   Position persistence — cube positions stored as JSON in SharedPreferences.
   Stored as fractions of canvas size so they survive rotation / device size.
   ────────────────────────────────────────────────────────────────────── */

private const val PREFS_NAME = "launcher_layout"
private const val KEY_POSITIONS = "cube_positions_json"

private fun loadPositions(context: Context): Map<String, Offset> {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val raw = prefs.getString(KEY_POSITIONS, null) ?: return emptyMap()
    return try {
        val obj = JSONObject(raw)
        val out = mutableMapOf<String, Offset>()
        for (key in obj.keys()) {
            val o = obj.getJSONObject(key)
            out[key] = Offset(o.getDouble("x").toFloat(), o.getDouble("y").toFloat())
        }
        out
    } catch (_: Exception) { emptyMap() }
}

private fun savePositions(context: Context, positions: Map<String, Offset>) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val obj = JSONObject()
    positions.forEach { (id, off) ->
        val o = JSONObject()
        o.put("x", off.x.toDouble())
        o.put("y", off.y.toDouble())
        obj.put(id, o)
    }
    prefs.edit().putString(KEY_POSITIONS, obj.toString()).apply()
}

/* ════════════════════════════════════════════════════════════════════
   LAUNCHER — free-form draggable cubes on a full-screen canvas.
   ════════════════════════════════════════════════════════════════════ */

/**
 * Full-screen launcher.
 *
 * @param pages list of pages to show.
 * @param onPageClick called with page id when user taps a cube.
 * @param onDrawerPullUp called when user swipes up on the bottom handle.
 */
@Composable
fun LauncherScreen(
    pages: List<LauncherPage> = DefaultLauncherPages,
    onPageClick: (String) -> Unit,
    onDrawerPullUp: () -> Unit = {}
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    // Position in fractions (0..1) of the canvas (excluding bottom handle).
    var positions by remember { mutableStateOf(loadPositions(context)) }
    // Dragged cube id (so we can elevate it visually).
    var draggedId by remember { mutableStateOf<String?>(null) }

    // Seed default positions if a new page appears without one.
    val seeded = remember(positions, pages) {
        val out = positions.toMutableMap()
        pages.forEachIndexed { idx, p ->
            if (out[p.id] == null) {
                // 2-column seed grid for first launch.
                val col = idx % 2
                val row = idx / 2
                out[p.id] = Offset(0.15f + col * 0.55f, 0.08f + row * 0.18f)
            }
        }
        out
    }

    fun persist(newPositions: Map<String, Offset>) {
        positions = newPositions
        savePositions(context, newPositions)
    }

    Box(modifier = Modifier.fillMaxSize().background(ClaudeBackground)) {
        // ── Header ──────────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Scooter Rent",
                style = MaterialTheme.typography.headlineSmall,
                color = ClaudeText,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "Kubikni bosib ushlang va sudrang — joyini o'zgartiring",
                style = MaterialTheme.typography.bodySmall,
                color = ClaudeTextSecondary,
                textAlign = TextAlign.Center
            )
        }

        // ── Canvas with draggable cubes ─────────────────────────────────
        // Drag deltas tracked in px, converted to fractions of canvas size,
        // persisted to SharedPreferences so layout survives restart.
        var canvasWidthPx by remember { mutableStateOf(1) }
        var canvasHeightPx by remember { mutableStateOf(1) }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 80.dp, bottom = 56.dp, start = 8.dp, end = 8.dp)
                .onGloballyPositioned { coords ->
                    canvasWidthPx = coords.size.width.coerceAtLeast(1)
                    canvasHeightPx = coords.size.height.coerceAtLeast(1)
                }
        ) {
            // Render each cube at its fractional position.
            seeded.forEach { page ->
                val pos = seeded[page.id] ?: return@forEach
                val cubeSizePx = with(density) { 72.dp.toPx() }
                Box(
                    modifier = Modifier
                        .offset {
                            val w = canvasWidthPx.coerceAtLeast(1)
                            val h = canvasHeightPx.coerceAtLeast(1)
                            val x = (pos.x * w).toInt() - cubeSizePx.toInt() / 2
                            val y = (pos.y * h).toInt() - cubeSizePx.toInt() / 2
                            IntOffset(
                                x.coerceIn(0, (w - cubeSizePx).toInt().coerceAtLeast(0)),
                                y.coerceIn(0, (h - cubeSizePx).toInt().coerceAtLeast(0))
                            )
                        }
                        .size(72.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            if (draggedId == page.id) ClaudeAccentBg else ClaudeCard
                        )
                        .border(
                            width = if (draggedId == page.id) 2.dp else 1.dp,
                            color = if (draggedId == page.id) ClaudeAccent else ClaudeDivider,
                            shape = RoundedCornerShape(16.dp)
                        )
                        .combinedClickable(
                            onClick = { onPageClick(page.id) },
                            onLongClick = { /* long press starts drag below */ }
                        )
                        .pointerInput(page.id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { draggedId = page.id },
                                onDragEnd = {
                                    draggedId = null
                                    persist(seeded)
                                },
                                onDragCancel = { draggedId = null },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    val w = canvasWidthPx.coerceAtLeast(1).toFloat()
                                    val h = canvasHeightPx.coerceAtLeast(1).toFloat()
                                    if (w > 0f && h > 0f) {
                                        val cur = seeded[page.id] ?: return@detectDragGesturesAfterLongPress
                                        val newX = (cur.x + dragAmount.x / w).coerceIn(0f, 1f)
                                        val newY = (cur.y + dragAmount.y / h).coerceIn(0f, 1f)
                                        seeded[page.id] = Offset(newX, newY)
                                    }
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(page.accentColor.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = page.icon,
                                contentDescription = page.title,
                                tint = page.accentColor,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(Modifier.height(4.dp))
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
            }
        }

        // ── Bottom handle (swipe up to open curtain drawer) ─────────────
        DrawerPullHandle(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            onPullUp = onDrawerPullUp
        )
    }
}

/* ════════════════════════════════════════════════════════════════════
   PROGRESSIVE CURTAIN DRAWER
   — bottom sheet whose height tracks the drag, revealing rows one-by-one.
   ════════════════════════════════════════════════════════════════════ */

/**
 * Multi-level bottom navigation drawer (curtain).
 *
 * The drawer has three rows:
 *   • Row 1 (always visible): 4 main pages — Mijozlar / Skuterlar / Kontraktlar / Moliya
 *   • Row 2 (revealed at ≥30% drag): Hisobotlar / Tarix
 *   • Row 3 (revealed at ≥70% drag): Chiqindi / Sozlamalar
 *
 * When [expanded] = true, the curtain animates up; the user can drag it
 * higher to reveal more rows. Swipe down or tap scrim to dismiss.
 *
 * @param expanded whether the drawer is currently shown.
 * @param onDismiss called when user dismisses the drawer.
 * @param onPageSelect called with page id when a row item is tapped.
 */
@Composable
fun NavigationDrawerSheet(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onPageSelect: (String) -> Unit
) {
    if (!expanded) return
    val scope = rememberCoroutineScope()

    // Curtain height as a fraction (0..1) of the screen height.
    // 0.18 = only row 1 visible (compact); 0.45 = row 2 added; 0.70 = all rows.
    val minH = 0.18f
    val midH = 0.45f
    val maxH = 0.70f
    val heightFraction = remember { Animatable(minH) }

    LaunchedEffect(expanded) {
        if (expanded) {
            // Snap to min then animate up to mid for a smooth reveal.
            heightFraction.snapTo(minH)
            heightFraction.animateTo(midH, tween(280))
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Scrim — tap dismisses.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f))
                .combinedClickable(onClick = onDismiss, onLongClick = {})
        )

        // The curtain itself.
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(heightFraction.value)
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            // dragAmount > 0 = finger moved down → curtain shrinks.
                            // dragAmount < 0 = finger moved up → curtain grows.
                            val screenPx = this.size.height.toFloat().coerceAtLeast(1f)
                            val delta = -dragAmount / screenPx
                            scope.launch {
                                heightFraction.snapTo(
                                    (heightFraction.value + delta).coerceIn(minH, maxH)
                                )
                            }
                        }
                    )
                },
            color = ClaudeCard,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(top = 8.dp, bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Drag handle pill at the top of the curtain.
                Box(
                    modifier = Modifier
                        .width(44.dp)
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(ClaudeTextSecondary)
                )
                Spacer(Modifier.height(8.dp))

                // Row 1 — always visible (4 main pages).
                DrawerRow(
                    pages = listOf(
                        DefaultLauncherPages[0], // Mijozlar
                        DefaultLauncherPages[1], // Skuterlar
                        DefaultLauncherPages[2], // Kontraktlar
                        DefaultLauncherPages[3]  // Moliya
                    ),
                    onPageSelect = { id -> onPageSelect(id); onDismiss() }
                )

                // Row 2 — revealed at ≥30%.
                if (heightFraction.value >= 0.30f) {
                    Spacer(Modifier.height(12.dp))
                    DrawerRow(
                        pages = listOf(
                            DefaultLauncherPages[4], // Hisobotlar
                            DefaultLauncherPages[5]  // Tarix
                        ),
                        onPageSelect = { id -> onPageSelect(id); onDismiss() }
                    )
                }

                // Row 3 — revealed at ≥70%.
                if (heightFraction.value >= 0.70f) {
                    Spacer(Modifier.height(12.dp))
                    DrawerCompactRow(
                        pages = listOf(
                            DefaultLauncherPages[6], // Chiqindi
                            DefaultLauncherPages[7]  // Sozlamalar
                        ),
                        onPageSelect = { id -> onPageSelect(id); onDismiss() }
                    )
                }

                Spacer(Modifier.weight(1f))

                // Hint at the bottom — only visible when curtain is small.
                if (heightFraction.value < 0.40f) {
                    Text(
                        "Yuqoriga sudrang — qo'shimcha sahifalar",
                        style = MaterialTheme.typography.labelSmall,
                        color = ClaudeTextSecondary,
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}

/* ──────────────────────────────────────────────────────────────────────
   Smaller building blocks
   ────────────────────────────────────────────────────────────────────── */

/** Full row with 4 square-ish icons (with labels). */
@Composable
private fun DrawerRow(
    pages: List<LauncherPage>,
    onPageSelect: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        pages.forEach { page ->
            DrawerItem(page = page, onClick = { onPageSelect(page.id) })
        }
    }
}

/** Compact square icons (no labels) — used for the last row. */
@Composable
private fun DrawerCompactRow(
    pages: List<LauncherPage>,
    onPageSelect: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        pages.forEach { page ->
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(page.accentColor.copy(alpha = 0.14f))
                    .combinedClickable(onClick = { onPageSelect(page.id) }, onLongClick = {}),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = page.icon,
                    contentDescription = page.title,
                    tint = page.accentColor,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}

/** Single drawer item — square-ish card with icon + label below. */
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
                .size(56.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(page.accentColor.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = page.icon,
                contentDescription = page.title,
                tint = page.accentColor,
                modifier = Modifier.size(32.dp)
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

/**
 * Handle bar at the very bottom of the launcher — swipe up to open the
 * curtain drawer. 36dp tall, full-width, with a pill + hint text.
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
            .height(40.dp)
            .background(ClaudeCard)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        // dragAmount < 0 = swipe up (open).
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
                    .width(44.dp)
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
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
