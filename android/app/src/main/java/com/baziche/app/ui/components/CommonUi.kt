package com.baziche.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.baziche.app.R

/** Map server error codes to localized strings. */
fun errorStringRes(code: String): Int? = when (code) {
    "INVALID_CREDENTIALS" -> R.string.err_INVALID_CREDENTIALS
    "INVALID_PHONE" -> R.string.err_INVALID_PHONE
    "PHONE_TAKEN" -> R.string.err_PHONE_TAKEN
    "USERNAME_TAKEN" -> R.string.err_USERNAME_TAKEN
    "RATE_LIMITED" -> R.string.err_RATE_LIMITED
    "NETWORK" -> R.string.err_NETWORK
    "ACCOUNT_SUSPENDED" -> R.string.err_ACCOUNT_SUSPENDED
    else -> null
}

@Composable
fun ErrorCard(code: String, fallback: String, onRetry: (() -> Unit)? = null) {
    val text = errorStringRes(code)?.let { stringResource(it) } ?: fallback.ifEmpty { stringResource(R.string.err_generic) }
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
            if (onRetry != null) {
                Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
            }
        }
    }
}

@Composable
fun LoadingButton(text: String, loading: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled && !loading, modifier = Modifier.fillMaxWidth()) {
        if (loading) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        else Text(text)
    }
}
