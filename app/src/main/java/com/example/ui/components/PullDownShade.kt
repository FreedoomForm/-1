package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.ui.theme.ClaudeAccent
import com.example.ui.theme.ClaudeCard
import com.example.ui.theme.ClaudeText
import com.example.ui.theme.ClaudeTextSecondary

/**
 * Pull-down shade component that can be dragged from top to bottom.
 * The shade reveals content underneath and stops above the bottom navigation.
 * 
 * @param isExpanded Whether the shade is fully expanded
 * @param onExpandedChange Callback when shade state changes
 * @param shadeContent Content to display in the shade
 * @param mainContent Main content underneath the shade
 * @param bottomNavHeight Height of the bottom navigation bar to avoid
 */
@Composable
fun PullDownShadeLayout(
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    shadeContent: @Composable () -> Unit,
    mainContent: @Composable () -> Unit,
    bottomNavHeight: Int = 80
) {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
    val bottomNavPx = with(density) { bottomNavHeight.dp.toPx() }
    val maxShadeHeight = screenHeightPx - bottomNavPx
    
    // Shade position: 0 = fully collapsed (top), 1 = fully expanded (bottom)
    var shadeProgress by remember { mutableStateOf(if (isExpanded) 1f else 0f) }
    var isDragging by remember { mutableStateOf(false) }
    
    val animatedProgress by animateFloatAsState(
        targetValue = if (isDragging) shadeProgress else if (isExpanded) 1f else 0f,
        animationSpec = tween(300),
        label = "shadeProgress"
    )
    
    // Sync external state
    LaunchedEffect(isExpanded) {
        if (!isDragging) {
            shadeProgress = if (isExpanded) 1f else 0f
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Main content (always visible)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = bottomNavHeight.dp)
        ) {
            mainContent()
        }
        
        // Shade overlay
        val shadeHeightDp = with(density) { (maxShadeHeight * animatedProgress).toDp() }
        
        if (animatedProgress > 0.01f) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(shadeHeightDp)
                    .align(Alignment.TopCenter)
                    .zIndex(10f)
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragStart = { isDragging = true },
                            onDragEnd = {
                                isDragging = false
                                // Snap to nearest state
                                val newExpanded = shadeProgress > 0.5f
                                shadeProgress = if (newExpanded) 1f else 0f
                                onExpandedChange(newExpanded)
                            },
                            onDragCancel = { isDragging = false },
                            onVerticalDrag = { change, dragAmount ->
                                change.consume()
                                val delta = dragAmount / maxShadeHeight
                                shadeProgress = (shadeProgress + delta).coerceIn(0f, 1f)
                            }
                        )
                    },
                color = Color(0xFF0A0A0A),
                shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp),
                shadowElevation = 16.dp
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Shade content
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        shadeContent()
                    }
                    
                    // Handle bar at bottom
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .width(40.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color(0xFF4A4A4A))
                        )
                    }
                }
            }
        }
        
        // Pull handle at top (when collapsed)
        if (animatedProgress < 0.1f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(30.dp)
                    .align(Alignment.TopCenter)
                    .zIndex(5f)
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragStart = { isDragging = true },
                            onDragEnd = {
                                isDragging = false
                                val newExpanded = shadeProgress > 0.3f
                                shadeProgress = if (newExpanded) 1f else 0f
                                onExpandedChange(newExpanded)
                            },
                            onDragCancel = { isDragging = false },
                            onVerticalDrag = { change, dragAmount ->
                                change.consume()
                                val delta = dragAmount / maxShadeHeight
                                shadeProgress = (shadeProgress + delta).coerceIn(0f, 1f)
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(ClaudeAccent.copy(alpha = 0.5f))
                )
            }
        }
    }
}

/**
 * Quick stats shade content for the launcher screen.
 */
@Composable
fun LauncherShadeContent(
    totalRenters: Int,
    activeRenters: Int,
    totalScooters: Int,
    availableScooters: Int,
    todayIncome: Double,
    totalDebt: Double,
    onQuickAction: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            "Tezkor ma'lumotlar",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        
        Spacer(Modifier.height(8.dp))
        
        // Stats grid
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ShadeStatCard(
                label = "Ijarachilar",
                value = "$activeRenters/$totalRenters",
                color = Color(0xFF10B981),
                modifier = Modifier.weight(1f)
            )
            ShadeStatCard(
                label = "Skuterlar",
                value = "$availableScooters/$totalScooters",
                color = Color(0xFF3B82F6),
                modifier = Modifier.weight(1f)
            )
        }
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ShadeStatCard(
                label = "Bugungi daromad",
                value = "${todayIncome.toLong()} UZS",
                color = Color(0xFF10B981),
                modifier = Modifier.weight(1f)
            )
            ShadeStatCard(
                label = "Umumiy qarz",
                value = "${totalDebt.toLong()} UZS",
                color = Color(0xFFEF4444),
                modifier = Modifier.weight(1f)
            )
        }
        
        Spacer(Modifier.height(16.dp))
        
        // Quick actions
        Text(
            "Tezkor harakatlar",
            style = MaterialTheme.typography.labelMedium,
            color = Color(0xFF9CA3AF)
        )
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            QuickActionButton(
                label = "Yangi ijara",
                color = Color(0xFF10B981),
                onClick = { onQuickAction("new_rental") },
                modifier = Modifier.weight(1f)
            )
            QuickActionButton(
                label = "To'lov qabul",
                color = Color(0xFF3B82F6),
                onClick = { onQuickAction("accept_payment") },
                modifier = Modifier.weight(1f)
            )
            QuickActionButton(
                label = "Hisobot",
                color = Color(0xFFF59E0B),
                onClick = { onQuickAction("report") },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ShadeStatCard(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.15f)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Spacer(Modifier.height(4.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF9CA3AF)
            )
        }
    }
}

@Composable
private fun QuickActionButton(
    label: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.2f),
        onClick = onClick
    ) {
        Box(
            modifier = Modifier.padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = color
            )
        }
    }
}
