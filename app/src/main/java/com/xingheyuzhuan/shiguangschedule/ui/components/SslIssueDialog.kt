package com.xingheyuzhuan.shiguangschedule.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.xingheyuzhuan.shiguangschedule.R

/** WebVPN TLS 证书校验异常 → 询问是否继续信任。 */
@Composable
fun SslIssueDialog(
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.title_ssl_exception)) },
        text = { Text(stringResource(R.string.msg_ssl_exception, message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_trust_and_continue)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}
