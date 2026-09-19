package com.baziche.app.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.baziche.app.R
import com.baziche.app.ui.theme.BazicheBgCard
import com.baziche.app.ui.theme.BazicheBgCardElevated
import com.baziche.app.ui.theme.BazicheBgRoot
import com.baziche.app.ui.theme.BazicheBorder
import com.baziche.app.ui.theme.BazicheBorderLight
import com.baziche.app.ui.theme.BazicheEmerald
import com.baziche.app.ui.theme.BazicheEmeraldBright
import com.baziche.app.ui.theme.BazicheLime
import com.baziche.app.ui.theme.BazicheRed
import com.baziche.app.ui.theme.BazicheTextMuted
import com.baziche.app.ui.theme.BazicheTextPrimary
import com.baziche.app.ui.theme.BazicheTextSecondary

/** Map server error codes to localized Persian/English string resources. */
fun errorStringRes(code: String): Int? = when (code) {
    "INVALID_CREDENTIALS" -> R.string.err_INVALID_CREDENTIALS
    "INVALID_PHONE" -> R.string.err_INVALID_PHONE
    "INVALID_EMAIL" -> R.string.err_INVALID_EMAIL
    "PHONE_TAKEN" -> R.string.err_PHONE_TAKEN
    "EMAIL_TAKEN" -> R.string.err_EMAIL_TAKEN
    "USERNAME_TAKEN" -> R.string.err_USERNAME_TAKEN
    "CODE_COOLDOWN" -> R.string.err_CODE_COOLDOWN
    "CODE_EXPIRED" -> R.string.err_CODE_EXPIRED
    "INVALID_CODE" -> R.string.err_INVALID_CODE
    "EMAIL_NOT_VERIFIED" -> R.string.err_EMAIL_NOT_VERIFIED
    "MAX_ATTEMPTS_EXCEEDED" -> R.string.err_MAX_ATTEMPTS_EXCEEDED
    "RATE_LIMITED" -> R.string.err_RATE_LIMITED
    "NETWORK" -> R.string.err_NETWORK
    "ACCOUNT_SUSPENDED" -> R.string.err_ACCOUNT_SUSPENDED
    else -> null
}

@Composable
fun ErrorCard(
    code: String,
    fallback: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    val text = errorStringRes(code)?.let { stringResource(it) } ?: fallback.ifEmpty { stringResource(R.string.err_generic) }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2C1414)),
        border = androidx.compose.foundation.BorderStroke(1.dp, BazicheRed.copy(alpha = 0.5f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFFFB4AB),
                    fontWeight = FontWeight.Medium,
                )
            }
            if (onRetry != null) {
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onRetry,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BazicheRed.copy(alpha = 0.3f)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(stringResource(R.string.retry), fontSize = 12.sp, color = Color.White)
                }
            }
        }
    }
}

/**
 * Modern Studio Primary Button with tactile press animation and emerald-lime gradient.
 */
@Composable
fun BazichePrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: (@Composable () -> Unit)? = null,
) {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = tween(durationMillis = 100),
        label = "btn_scale",
    )

    val gradient = if (enabled && !loading) {
        Brush.horizontalGradient(listOf(BazicheEmerald, BazicheEmeraldBright))
    } else {
        Brush.horizontalGradient(listOf(Color(0xFF202A24), Color(0xFF2A3830)))
    }

    Box(
        modifier = modifier
            .scale(scale)
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(gradient)
            .border(
                width = 1.dp,
                color = if (enabled && !loading) BazicheLime.copy(alpha = 0.4f) else Color.Transparent,
                shape = RoundedCornerShape(14.dp),
            )
            .pointerInput(enabled && !loading) {
                if (enabled && !loading) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitFirstDown(requireUnconsumed = false)
                            isPressed = true
                            waitForUpOrCancellation()
                            isPressed = false
                        }
                    }
                }
            }
            .clickable(enabled = enabled && !loading, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = BazicheLime,
                strokeWidth = 2.5.dp,
            )
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                if (icon != null) {
                    icon()
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (enabled) BazicheTextPrimary else BazicheTextMuted,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                )
            }
        }
    }
}

/**
 * Outlined Secondary Button with dark surface and subtle border.
 */
@Composable
fun BazicheSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: (@Composable () -> Unit)? = null,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .height(50.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = BazicheBgCard,
            contentColor = BazicheEmeraldBright,
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, BazicheBorderLight),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                icon()
                Spacer(Modifier.width(8.dp))
            }
            Text(text, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * Common LoadingButton adapter (backward-compatible).
 */
@Composable
fun LoadingButton(
    text: String,
    loading: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    BazichePrimaryButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        loading = loading,
    )
}

/**
 * Modern Dark Studio Card.
 */
@Composable
fun BazicheCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    backgroundColor: Color = BazicheBgCard,
    borderColor: Color = BazicheBorder,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val clickableModifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier
    Box(
        modifier = clickableModifier
            .clip(shape)
            .background(backgroundColor)
            .border(1.dp, borderColor, shape)
            .padding(16.dp),
    ) {
        content()
    }
}

/**
 * Modern Dark Outlined Text Field with high-contrast text and emerald focus.
 */
@Composable
fun BazicheTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: String? = null,
    placeholder: String? = null,
    supportingText: String? = null,
    isError: Boolean = false,
    singleLine: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    trailingIcon: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        label = label?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it, color = BazicheTextMuted) } },
        supportingText = supportingText?.let { { Text(it, color = if (isError) BazicheRed else BazicheTextMuted) } },
        isError = isError,
        singleLine = singleLine,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        trailingIcon = trailingIcon,
        leadingIcon = leadingIcon,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = BazicheBgCard,
            unfocusedContainerColor = BazicheBgCard,
            disabledContainerColor = BazicheBgRoot,
            focusedBorderColor = BazicheEmeraldBright,
            unfocusedBorderColor = BazicheBorder,
            errorBorderColor = BazicheRed,
            focusedTextColor = BazicheTextPrimary,
            unfocusedTextColor = BazicheTextPrimary,
            focusedLabelColor = BazicheEmeraldBright,
            unfocusedLabelColor = BazicheTextSecondary,
        ),
    )
}

/**
 * Status Badge (e.g. Free build available, Plan status).
 */
@Composable
fun BazicheBadge(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = BazicheEmeraldBright,
    backgroundColor: Color = color.copy(alpha = 0.15f),
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Animated Shimmer Box for skeleton loading.
 */
@Composable
fun ShimmerBox(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmer_trans",
    )

    val brush = Brush.linearGradient(
        colors = listOf(
            BazicheBgCard,
            BazicheBgCardElevated,
            BazicheBgCard,
        ),
        start = Offset.Zero,
        end = Offset(x = translateAnim, y = translateAnim),
    )

    Box(
        modifier = modifier
            .clip(shape)
            .background(brush),
    )
}
