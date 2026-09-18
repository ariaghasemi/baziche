package com.baziche.app.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.baziche.app.ui.theme.BazicheBgRoot
import com.baziche.app.ui.theme.BazicheBorder
import com.baziche.app.ui.theme.BazicheCyan
import com.baziche.app.ui.theme.BazicheEmerald
import com.baziche.app.ui.theme.BazicheEmeraldBright
import com.baziche.app.ui.theme.BazicheLime

/**
 * Official BAZICHE Logo Component.
 * Pure stylized English 'B' on a pure black background.
 * Strictly conforms to Section 30 of the Master Directive:
 * - Pure black background
 * - Exactly one large stylized gaming 'B'
 * - No words, no extra characters, no separate gamepads
 */
@Composable
fun BazicheLogo(
    modifier: Modifier = Modifier,
    size: Dp = 80.dp,
    animated: Boolean = true,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "logo_glow")
    val glowAlpha by if (animated) {
        infiniteTransition.animateFloat(
            initialValue = 0.4f,
            targetValue = 0.9f,
            animationSpec = infiniteRepeatable(
                animation = tween(2200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "alpha",
        )
    } else {
        rememberInfiniteTransition(label = "static").animateFloat(
            initialValue = 0.7f,
            targetValue = 0.7f,
            animationSpec = infiniteRepeatable(tween(1000)),
            label = "static_alpha",
        )
    }

    Box(
        modifier = modifier
            .size(size)
            .shadow(16.dp, RoundedCornerShape(22), spotColor = BazicheEmeraldBright.copy(alpha = glowAlpha))
            .clip(RoundedCornerShape(22))
            .background(BazicheBgRoot)
            .border(1.5.dp, BazicheBorder, RoundedCornerShape(22)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(size * 0.76f)) {
            val w = this.size.width
            val h = this.size.height

            // 1. Subtle high-tech diagonal accent line in background
            drawLine(
                color = Color(0xFF14221C),
                start = Offset(w * 0.15f, h * 0.85f),
                end = Offset(w * 0.85f, h * 0.15f),
                strokeWidth = 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
            )

            // 2. Main Outer Path of stylized 'B'
            val mainBPath = Path().apply {
                val left = w * 0.22f
                val right = w * 0.82f
                val top = h * 0.14f
                val bottom = h * 0.86f
                val midY = h * 0.50f

                // Left vertical stem
                moveTo(left, top)
                // Top horizontal bar
                lineTo(w * 0.62f, top)
                // Upper right loop
                cubicTo(right * 0.95f, top, right * 0.95f, midY, w * 0.60f, midY)
                // Middle junction notch
                lineTo(w * 0.54f, midY)
                lineTo(w * 0.64f, midY)
                // Lower right dynamic loop
                cubicTo(right, midY, right, bottom, w * 0.58f, bottom)
                // Bottom horizontal bar
                lineTo(left, bottom)
                // Close back to top
                close()
            }

            // 3. Draw gradient fill for 'B'
            val bBrush = Brush.linearGradient(
                colors = listOf(BazicheEmerald, BazicheEmeraldBright, BazicheLime),
                start = Offset(0f, 0f),
                end = Offset(w, h),
            )
            drawPath(path = mainBPath, brush = bBrush)

            // 4. Upper Inner Cutout (Beveled Gamer Window)
            val upperInnerPath = Path().apply {
                addRoundRect(
                    RoundRect(
                        rect = Rect(
                            left = w * 0.38f,
                            top = h * 0.26f,
                            right = w * 0.62f,
                            bottom = h * 0.42f,
                        ),
                        cornerRadius = CornerRadius(w * 0.06f, w * 0.06f),
                    ),
                )
            }
            drawPath(path = upperInnerPath, color = BazicheBgRoot)

            // 5. Lower Inner Cutout (Dynamic Game Play Facet)
            val lowerInnerPath = Path().apply {
                addRoundRect(
                    RoundRect(
                        rect = Rect(
                            left = w * 0.38f,
                            top = h * 0.58f,
                            right = w * 0.66f,
                            bottom = h * 0.74f,
                        ),
                        cornerRadius = CornerRadius(w * 0.06f, w * 0.06f),
                    ),
                )
            }
            drawPath(path = lowerInnerPath, color = BazicheBgRoot)

            // 6. Gamer Cyber Notch on Spine (Mechanical negative space)
            val spineNotch = Path().apply {
                moveTo(w * 0.18f, h * 0.47f)
                lineTo(w * 0.28f, h * 0.50f)
                lineTo(w * 0.18f, h * 0.53f)
                close()
            }
            drawPath(path = spineNotch, color = BazicheBgRoot)

            // 7. Glowing Edge Highlights (Cyber neon accent)
            drawPath(
                path = mainBPath,
                brush = Brush.verticalGradient(
                    colors = listOf(BazicheLime, BazicheCyan.copy(alpha = glowAlpha)),
                ),
                style = Stroke(
                    width = 2.5f,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
        }
    }
}
