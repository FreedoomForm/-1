package com.example.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.theme.ClaudeAccent
import com.example.ui.theme.ClaudeBackground
import com.example.ui.theme.ClaudeCard
import com.example.ui.theme.ClaudeDivider
import com.example.ui.theme.ClaudeText
import com.example.ui.theme.ClaudeTextSecondary
import com.example.ui.theme.StatusOk
import com.example.ui.theme.StatusOverdue

/* ============================================================================
   UNIFIED BUTTON COMPONENT
   ============================================================================
   One professional icon + one-word text label + smooth press animation.

   Variants:
     PRIMARY    — amber filled, white text          (main CTA: "Saqla", "To'lov")
     SECONDARY  — white card + gray border           (alt action: "Bekor", "Tekshir")
     SUCCESS    — green filled, white text           (positive: "Yangila")
     DANGER     — red filled, white text             (destructive: "O'chir")
     DANGER_OUT — white + red border + red text      (destructive secondary)
     TEXT       — transparent, accent text           (inline dialog: "Yopish")

   Animation:
     On press → scale to 0.95 + icon rotates -8° + icon scales to 0.9
     Release  → spring back to 1.0
   ============================================================================ */

enum class UnifiedButtonVariant {
    PRIMARY,
    SECONDARY,
    SUCCESS,
    DANGER,
    DANGER_OUTLINED,
    TEXT
}

@Composable
fun UnifiedButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: UnifiedButtonVariant = UnifiedButtonVariant.PRIMARY,
    enabled: Boolean = true,
    loading: Boolean = false,
    height: Int = 44,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    // Press animation: button scales down + icon rotates and shrinks slightly
    val buttonScale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "btnScale"
    )
    val iconRotation by animateFloatAsState(
        targetValue = if (isPressed) -8f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "iconRotation"
    )
    val iconScale by animateFloatAsState(
        targetValue = if (isPressed) 0.85f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessHigh
        ),
        label = "iconScale"
    )

    // Resolve colors by variant
    val (containerColor, contentColor, border) = when (variant) {
        UnifiedButtonVariant.PRIMARY -> Triple(ClaudeAccent, Color.White, null)
        UnifiedButtonVariant.SECONDARY -> Triple(ClaudeCard, ClaudeText, BorderStroke(1.dp, ClaudeDivider))
        UnifiedButtonVariant.SUCCESS -> Triple(StatusOk, Color.White, null)
        UnifiedButtonVariant.DANGER -> Triple(StatusOverdue, Color.White, null)
        UnifiedButtonVariant.DANGER_OUTLINED -> Triple(ClaudeCard, StatusOverdue, BorderStroke(1.dp, StatusOverdue))
        UnifiedButtonVariant.TEXT -> Triple(Color.Transparent, ClaudeAccent, null)
    }

    val disabledAlpha = if (enabled) 1f else 0.45f

    Surface(
        modifier = modifier
            .height(height.dp)
            .graphicsLayer {
                scaleX = buttonScale
                scaleY = buttonScale
                alpha = disabledAlpha
            },
        shape = RoundedCornerShape(10.dp),
        color = containerColor,
        contentColor = contentColor,
        border = border,
        enabled = enabled,
        onClick = onClick,
        interactionSource = interactionSource
    ) {
        Row(
            modifier = Modifier
                .padding(contentPadding)
                .graphicsLayer { /* row stays still; only outer Surface scales */ },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = contentColor
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier
                        .size(18.dp)
                        .graphicsLayer {
                            rotationZ = iconRotation
                            scaleX = iconScale
                            scaleY = iconScale
                        }
                )
            }
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                color = contentColor,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
    }
}

/* ── Convenience aliases for the most common variants ─────────────────────── */

@Composable
fun PrimaryButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false
) = UnifiedButton(
    label = label,
    icon = icon,
    onClick = onClick,
    modifier = modifier,
    variant = UnifiedButtonVariant.PRIMARY,
    enabled = enabled,
    loading = loading
)

@Composable
fun SecondaryButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false
) = UnifiedButton(
    label = label,
    icon = icon,
    onClick = onClick,
    modifier = modifier,
    variant = UnifiedButtonVariant.SECONDARY,
    enabled = enabled,
    loading = loading
)

@Composable
fun SuccessButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false
) = UnifiedButton(
    label = label,
    icon = icon,
    onClick = onClick,
    modifier = modifier,
    variant = UnifiedButtonVariant.SUCCESS,
    enabled = enabled,
    loading = loading
)

@Composable
fun DangerButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false
) = UnifiedButton(
    label = label,
    icon = icon,
    onClick = onClick,
    modifier = modifier,
    variant = UnifiedButtonVariant.DANGER,
    enabled = enabled,
    loading = loading
)

@Composable
fun DangerOutlinedButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false
) = UnifiedButton(
    label = label,
    icon = icon,
    onClick = onClick,
    modifier = modifier,
    variant = UnifiedButtonVariant.DANGER_OUTLINED,
    enabled = enabled,
    loading = loading
)

@Composable
fun TextActionButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) = UnifiedButton(
    label = label,
    icon = icon,
    onClick = onClick,
    modifier = modifier,
    variant = UnifiedButtonVariant.TEXT,
    enabled = enabled,
    height = 36,
    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
)
