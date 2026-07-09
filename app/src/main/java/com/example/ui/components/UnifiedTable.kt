package com.example.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.theme.*
import kotlinx.coroutines.launch

/* ============================================================================
   UNIFIED TABLE COMPONENTS
   ============================================================================
   Used across all screens (renters, scooters, contract history) for
   consistent UI design.
   ============================================================================ */

// ── Sort State ──────────────────────────────────────────────────────────────

enum class SortState { NONE, ASCENDING, DESCENDING }

/**
 * 4-state sort cycle per user request:
 *   NONE(0) → ASC(1) → NONE(2) → DESC(3) → NONE(0) → ...
 *
 * Clicking a column that is NOT active starts it at ASC (index 1).
 * Clicking the active column advances the cycle index (mod 4).
 */
data class TableSortState(
    val activeColumn: String? = null,
    val cycleIndex: Int = 0
) {
    fun stateFor(columnId: String): SortState {
        if (activeColumn != columnId) return SortState.NONE
        return when (cycleIndex) {
            1 -> SortState.ASCENDING
            3 -> SortState.DESCENDING
            else -> SortState.NONE
        }
    }

    fun click(columnId: String): TableSortState =
        if (activeColumn == columnId) {
            copy(cycleIndex = (cycleIndex + 1) % 4)
        } else {
            copy(activeColumn = columnId, cycleIndex = 1)
        }

    val isActive: Boolean get() = cycleIndex == 1 || cycleIndex == 3
}

// ── Phone Receiver Sort Icon (custom, 3-state animated) ─────────────────────

/**
 * Custom phone-receiver icon with 3 animated states:
 *   NONE       → receiver horizontal (0°), faded
 *   ASCENDING  → receiver lifted up (-35°, counterclockwise), full opacity
 *   DESCENDING → receiver lowered down (+35°, clockwise), full opacity
 *
 * Drawn via Canvas — two circles (earpiece + mouthpiece) connected by a
 * rounded bar. Smoothly animates between states with a spring.
 */
@Composable
fun PhoneReceiverSortIcon(
    state: SortState,
    modifier: Modifier = Modifier,
    tint: Color = ClaudeAccent,
    iconSize: Int = 18
) {
    val targetRotation by animateFloatAsState(
        targetValue = when (state) {
            SortState.NONE -> 0f
            SortState.ASCENDING -> -35f
            SortState.DESCENDING -> 35f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "phoneRotation"
    )

    val targetAlpha by animateFloatAsState(
        targetValue = if (state == SortState.NONE) 0.35f else 1f,
        animationSpec = tween(300),
        label = "phoneAlpha"
    )

    Box(
        modifier = modifier
            .size(iconSize.dp)
            .graphicsLayer {
                rotationZ = targetRotation
                alpha = targetAlpha
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            val endRadius = h * 0.24f
            val barHeight = h * 0.3f
            val margin = w * 0.12f

            // Left end (mouthpiece)
            drawCircle(
                color = tint,
                radius = endRadius,
                center = Offset(margin + endRadius, h * 0.5f)
            )
            // Right end (earpiece)
            drawCircle(
                color = tint,
                radius = endRadius,
                center = Offset(w - margin - endRadius, h * 0.5f)
            )
            // Connecting bar
            drawRoundRect(
                color = tint,
                topLeft = Offset(margin + endRadius * 0.5f, h * 0.5f - barHeight / 2),
                size = Size(w - 2 * margin - endRadius, barHeight),
                cornerRadius = CornerRadius(barHeight / 2, barHeight / 2)
            )
        }
    }
}

// ── Sortable Header Cell (icon + animated phone receiver) ───────────────────

/**
 * Table header cell showing ONLY an icon (no text label) for quick visual
 * association, plus an animated phone-receiver sort indicator.
 *
 * Sort cycle on click: NONE → ASC → NONE → DESC → NONE → ...
 */
@Composable
fun RowScope.SortableHeaderCell(
    icon: ImageVector,
    weight: Float,
    columnId: String,
    sortState: TableSortState,
    onClick: () -> Unit
) {
    val state = sortState.stateFor(columnId)
    val iconTint = if (state == SortState.NONE) ClaudeTextSecondary else ClaudeAccent

    Row(
        modifier = Modifier
            .weight(weight)
            .clip(RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(vertical = 6.dp, horizontal = 2.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = columnId,
            tint = iconTint,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(3.dp))
        PhoneReceiverSortIcon(
            state = state,
            tint = if (state == SortState.NONE) ClaudeTextSecondary else ClaudeAccent,
            iconSize = 14
        )
    }
}

/**
 * Non-sortable header cell — icon only, no sort indicator.
 */
@Composable
fun RowScope.NonSortableHeaderCell(
    icon: ImageVector,
    weight: Float,
    contentDescription: String = ""
) {
    Box(
        modifier = Modifier
            .weight(weight)
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = ClaudeTextSecondary,
            modifier = Modifier.size(18.dp)
        )
    }
}

// ── Fixed-width variants (for horizontally-scrollable tables) ─────────────

/**
 * Sortable header cell with FIXED width (instead of weight).
 *
 * Use inside Row(Modifier.horizontalScroll(...)) when the table has so many
 * columns that their total width exceeds the screen — weights would just
 * squeeze every column to unreadable width, so we use fixed Dp widths and
 * let the user scroll horizontally to see the rest.
 */
@Composable
fun SortableHeaderCellFixed(
    icon: ImageVector,
    widthDp: androidx.compose.ui.unit.Dp,
    columnId: String,
    sortState: TableSortState,
    onClick: () -> Unit
) {
    val state = sortState.stateFor(columnId)
    val iconTint = if (state == SortState.NONE) ClaudeTextSecondary else ClaudeAccent

    Row(
        modifier = Modifier
            .width(widthDp)
            .clip(RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(vertical = 6.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = columnId,
            tint = iconTint,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(3.dp))
        PhoneReceiverSortIcon(
            state = state,
            tint = if (state == SortState.NONE) ClaudeTextSecondary else ClaudeAccent,
            iconSize = 14
        )
    }
}

/**
 * Non-sortable header cell with FIXED width.
 */
@Composable
fun NonSortableHeaderCellFixed(
    icon: ImageVector,
    widthDp: androidx.compose.ui.unit.Dp,
    contentDescription: String = ""
) {
    Box(
        modifier = Modifier
            .width(widthDp)
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = ClaudeTextSecondary,
            modifier = Modifier.size(18.dp)
        )
    }
}

// ── Unified Search Bar (with calendar + filter buttons) ─────────────────────

/**
 * Unified search bar used on ALL screens for consistent design.
 * Pill-shaped (24.dp), white container, always has:
 *   - Search leading icon
 *   - Calendar trailing button (opens date filter)
 *   - Filter trailing button (opens column filter side panel)
 */
@Composable
fun UnifiedSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onCalendarClick: (() -> Unit)? = null,
    calendarActive: Boolean = false,
    onFilterClick: (() -> Unit)? = null,
    filterActive: Boolean = false
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(placeholder, color = ClaudeTextSecondary) },
        leadingIcon = {
            Icon(Icons.Default.Search, contentDescription = "Qidirish", tint = ClaudeTextSecondary)
        },
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onFilterClick != null) {
                    IconButton(onClick = onFilterClick, modifier = Modifier.size(40.dp)) {
                        Icon(
                            Icons.Default.Tune,
                            contentDescription = "Filtrlash",
                            tint = if (filterActive) ClaudeAccent else ClaudeTextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                if (onCalendarClick != null) {
                    IconButton(onClick = onCalendarClick, modifier = Modifier.size(40.dp)) {
                        Icon(
                            Icons.Default.DateRange,
                            contentDescription = "Sana bo'yicha filter",
                            tint = if (calendarActive) ClaudeAccent else ClaudeTextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(24.dp),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = ClaudeDivider,
            focusedBorderColor = ClaudeAccent,
            unfocusedContainerColor = Color.White,
            focusedContainerColor = Color.White
        ),
        singleLine = true
    )
}

// ── Filter Side Panel ───────────────────────────────────────────────────────

data class FilterColumn(
    val id: String,
    val label: String,
    val placeholder: String = "",
    val keyboardType: androidx.compose.ui.text.input.KeyboardType = androidx.compose.ui.text.input.KeyboardType.Text
)

/**
 * Side panel that slides in from the right edge.
 * Contains a text field for each column; user types values and clicks
 * "Qidirish" to apply all filters simultaneously.
 */
@Composable
fun FilterSidePanel(
    columns: List<FilterColumn>,
    filterValues: Map<String, String>,
    onFilterChange: (String, String) -> Unit,
    onSearch: () -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
    visible: Boolean
) {
    if (!visible) return

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable { onDismiss() }
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(340.dp)
                .clickable { /* consume click — don't dismiss when clicking panel */ },
            shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp),
            color = ClaudeCard
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Tune, contentDescription = null, tint = ClaudeAccent)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Filtrlash",
                            style = MaterialTheme.typography.titleLarge,
                            color = ClaudeText,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Yopish", tint = ClaudeTextSecondary)
                    }
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = ClaudeDivider)
                Spacer(Modifier.height(16.dp))

                // Filter fields
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(columns, key = { it.id }) { col ->
                        OutlinedTextField(
                            value = filterValues[col.id] ?: "",
                            onValueChange = { onFilterChange(col.id, it) },
                            label = { Text(col.label) },
                            placeholder = { Text(col.placeholder) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = col.keyboardType
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedBorderColor = ClaudeDivider,
                                focusedBorderColor = ClaudeAccent,
                                unfocusedContainerColor = Color.White,
                                focusedContainerColor = Color.White
                            )
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Active filter count
                val activeCount = filterValues.count { it.value.isNotBlank() }
                if (activeCount > 0) {
                    Text(
                        "$activeCount ta filtr faol",
                        modifier = Modifier.padding(bottom = 8.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = ClaudeAccent
                    )
                }

                // Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SecondaryButton(
                        label = "Tozalash",
                        icon = Icons.Default.Clear,
                        onClick = onReset,
                        modifier = Modifier.weight(1f)
                    )
                    PrimaryButton(
                        label = "Qidir",
                        icon = Icons.Default.Search,
                        onClick = {
                            onSearch()
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

// ── Helper: apply filters + sort to a list ──────────────────────────────────

/**
 * Applies text-based "contains" filtering (case-insensitive) to a list.
 * For each column in [columns], if filterValues has a non-blank entry,
 * the item's string value for that column must contain the filter text.
 *
 * All filters are AND-ed (item must match ALL active filters).
 */
fun <T> applyFilters(
    items: List<T>,
    columns: List<FilterColumn>,
    filterValues: Map<String, String>,
    valueExtractor: (T, String) -> String
): List<T> {
    val activeFilters = columns.filter { (filterValues[it.id] ?: "").isNotBlank() }
    if (activeFilters.isEmpty()) return items
    return items.filter { item ->
        activeFilters.all { col ->
            val filterText = filterValues[col.id] ?: ""
            val itemValue = valueExtractor(item, col.id)
            itemValue.contains(filterText, ignoreCase = true)
        }
    }
}
