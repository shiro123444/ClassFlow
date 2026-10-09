package com.xingheyuzhuan.shiguangschedule.ui.campus.hairdryer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.HairdryerDetectionMode
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.HairdryerNfcMode
import com.xingheyuzhuan.shiguangschedule.data.repository.HairdryerHubRepository

/**
 * 吹风机判型设置。
 *
 * 用户唯一需要理解的一件事：**扫码之后到底跳哪儿**。所以每个选项都写成
 * 「蓝牙（跳支付宝）」「4G（跳一卡通页面）」这种「名字（后果）」的形式，
 * 而不是 `moduleType`、`Bluetooth/Cloud` 这类内部说法。
 */
@Composable
fun HairdryerSettingsDialog(
    settings: HairdryerHubRepository.Settings,
    onDismiss: () -> Unit,
    onModeChange: (HairdryerDetectionMode) -> Unit,
    onNfcModeChange: (HairdryerNfcMode) -> Unit,
    onResetFormatVerdicts: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.hairdryer_settings_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                SectionTitle(stringResource(R.string.hairdryer_settings_mode_section))
                ModeOption(
                    title = stringResource(R.string.hairdryer_mode_auto),
                    desc = stringResource(R.string.hairdryer_mode_auto_desc),
                    selected = settings.mode == HairdryerDetectionMode.AUTO,
                    onClick = { onModeChange(HairdryerDetectionMode.AUTO) }
                )
                ModeOption(
                    title = stringResource(R.string.hairdryer_mode_auto_device),
                    desc = stringResource(R.string.hairdryer_mode_auto_device_desc),
                    selected = settings.mode == HairdryerDetectionMode.AUTO_DEVICE,
                    onClick = { onModeChange(HairdryerDetectionMode.AUTO_DEVICE) }
                )
                ModeOption(
                    title = stringResource(R.string.hairdryer_mode_bluetooth),
                    desc = stringResource(R.string.hairdryer_mode_bluetooth_desc),
                    selected = settings.mode == HairdryerDetectionMode.BLUETOOTH,
                    onClick = { onModeChange(HairdryerDetectionMode.BLUETOOTH) }
                )
                ModeOption(
                    title = stringResource(R.string.hairdryer_mode_cloud),
                    desc = stringResource(R.string.hairdryer_mode_cloud_desc),
                    selected = settings.mode == HairdryerDetectionMode.CLOUD,
                    onClick = { onModeChange(HairdryerDetectionMode.CLOUD) }
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

                SectionTitle(stringResource(R.string.hairdryer_settings_nfc_section))
                ModeOption(
                    title = stringResource(R.string.hairdryer_nfc_follow),
                    desc = null,
                    selected = settings.nfcMode == HairdryerNfcMode.FOLLOW,
                    onClick = { onNfcModeChange(HairdryerNfcMode.FOLLOW) }
                )
                ModeOption(
                    title = stringResource(R.string.hairdryer_mode_auto),
                    desc = null,
                    selected = settings.nfcMode == HairdryerNfcMode.AUTO,
                    onClick = { onNfcModeChange(HairdryerNfcMode.AUTO) }
                )
                ModeOption(
                    title = stringResource(R.string.hairdryer_mode_auto_device),
                    desc = null,
                    selected = settings.nfcMode == HairdryerNfcMode.AUTO_DEVICE,
                    onClick = { onNfcModeChange(HairdryerNfcMode.AUTO_DEVICE) }
                )
                ModeOption(
                    title = stringResource(R.string.hairdryer_mode_bluetooth),
                    desc = null,
                    selected = settings.nfcMode == HairdryerNfcMode.BLUETOOTH,
                    onClick = { onNfcModeChange(HairdryerNfcMode.BLUETOOTH) }
                )
                ModeOption(
                    title = stringResource(R.string.hairdryer_mode_cloud),
                    desc = null,
                    selected = settings.nfcMode == HairdryerNfcMode.CLOUD,
                    onClick = { onNfcModeChange(HairdryerNfcMode.CLOUD) }
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

                TextButton(onClick = onResetFormatVerdicts) {
                    Text(stringResource(R.string.hairdryer_settings_reset_format))
                }
                Text(
                    text = stringResource(R.string.hairdryer_settings_reset_format_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
                )
                Text(
                    text = stringResource(R.string.hairdryer_settings_local_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_confirm)) }
        }
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 4.dp)
    )
}

@Composable
private fun ModeOption(
    title: String,
    desc: String?,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (desc != null) {
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
