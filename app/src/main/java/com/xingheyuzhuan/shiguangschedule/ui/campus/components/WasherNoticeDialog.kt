package com.xingheyuzhuan.shiguangschedule.ui.campus.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WasherAvailability
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WasherUnavailableReason

/**
 * 洗衣机「当前不可下单」提示（扫码链路与 `/wm/` 深链链路共用）。
 *
 * 服务端只回一个「能不能下单」，具体原因靠 [WasherUnavailableReason] 区分：
 * 他人正在使用 / 已被预约 / 故障 / 离线 / 未开通或停用 / 码查不到设备，各有独立文案
 * （措辞对齐 U净 官方 washer-h5 的扫码异常页），不再一律说「设备离线」。
 */
@Composable
fun WasherNoticeDialog(
    notice: WasherAvailability.Unavailable,
    onDismiss: () -> Unit
) {
    val titleRes = when (notice.reason) {
        WasherUnavailableReason.RUNNING -> R.string.dialog_ujing_washer_running_title
        WasherUnavailableReason.RESERVED -> R.string.dialog_ujing_washer_reserved_title
        WasherUnavailableReason.FAULT -> R.string.dialog_ujing_washer_fault_title
        WasherUnavailableReason.OFFLINE -> R.string.dialog_ujing_washer_offline_title
        WasherUnavailableReason.DISABLED -> R.string.dialog_ujing_washer_disabled_title
        WasherUnavailableReason.UNKNOWN_DEVICE -> R.string.dialog_ujing_washer_unknown_title
        WasherUnavailableReason.NOT_ORDERABLE -> R.string.dialog_ujing_washer_unavailable_title
    }
    val messageRes = when (notice.reason) {
        WasherUnavailableReason.RUNNING -> R.string.dialog_ujing_washer_running_message
        WasherUnavailableReason.RESERVED -> R.string.dialog_ujing_washer_reserved_message
        WasherUnavailableReason.FAULT -> R.string.dialog_ujing_washer_fault_message
        WasherUnavailableReason.OFFLINE -> R.string.dialog_ujing_washer_offline_message
        WasherUnavailableReason.DISABLED -> R.string.dialog_ujing_washer_disabled_message
        WasherUnavailableReason.UNKNOWN_DEVICE -> R.string.dialog_ujing_washer_unknown_message
        WasherUnavailableReason.NOT_ORDERABLE -> R.string.dialog_ujing_washer_unavailable_message
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = {
            Column {
                Text(stringResource(messageRes))
                val mobile = notice.merchantMobile
                if (!mobile.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.dialog_ujing_washer_merchant_phone, mobile),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_confirm)) }
        }
    )
}
