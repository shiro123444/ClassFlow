package com.xingheyuzhuan.shiguangschedule.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.PortalCaptchaData

/** WebVPN 门户 4 位字符验证码。 */
@Composable
fun PortalCaptchaDialog(
    captcha: PortalCaptchaData,
    onSubmit: (String) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit
) {
    val bitmap = remember(captcha.imageBytes) {
        runCatching { BitmapFactory.decodeByteArray(captcha.imageBytes, 0, captcha.imageBytes.size)?.asImageBitmap() }
            .getOrNull()
    }
    var code by remember(captcha.attempt, captcha.imageBytes) { mutableStateOf("") }
    val isCodeValid = code.trim().length == 4

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.title_webvpn_captcha)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = when (captcha.previousErrorCode) {
                        "20023" -> stringResource(R.string.desc_webvpn_captcha_retry)
                        "20041", "20043" -> stringResource(R.string.desc_webvpn_captcha_security)
                        else -> stringResource(R.string.desc_webvpn_captcha)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = stringResource(R.string.a11y_webvpn_captcha_image),
                        modifier = Modifier
                            .width(189.dp)
                            .height(60.dp)
                            .padding(vertical = 2.dp)
                            .clickable(onClick = onRefresh),
                        contentScale = ContentScale.FillBounds
                    )
                } else {
                    Text(
                        text = stringResource(R.string.err_captcha_load_failed),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                OutlinedTextField(
                    value = code,
                    onValueChange = { value ->
                        code = value.filter { it.isLetterOrDigit() }.take(4)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.label_webvpn_captcha_code)) },
                    placeholder = { Text(stringResource(R.string.hint_webvpn_captcha_code)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    isError = code.isNotEmpty() && !isCodeValid
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(code.trim()) }, enabled = bitmap != null && isCodeValid) {
                Text(stringResource(R.string.action_submit_webvpn_captcha))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
