package com.xingheyuzhuan.shiguangschedule.ui.campus.ujing

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.nfc.NfcAdapter
import android.widget.Toast
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.UjingQrLink

/**
 * 调起支付宝 U净 吹风机页面：显式包名 → 通用 Scheme → NFC Action → Universal Link 逐级兜底。
 *
 * 全局扫码与通用链接节点共用；失败时[Context] 上弹「未安装支付宝」提示。
 *
 * @return true = 已成功发起调起，调用方应退出当前过渡页。
 */
fun launchUjingHairdryer(
    context: Context,
    cd: String,
    scheme: String,
    ulinkUrl: String
): Boolean {
    val schemeUri = Uri.parse(scheme)

    // 1. 优先使用标准 alipays:// Scheme 显式调起支付宝官方客户端（经实测可直接唤起吹风机原生小程序）
    val explicitIntent = Intent(Intent.ACTION_VIEW, schemeUri).apply {
        setPackage(UjingQrLink.ALIPAY_PACKAGE_NAME)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val launched = runCatching {
        context.startActivity(explicitIntent)
        true
    }.getOrElse {
        // 2. 兜底：通用 alipays Scheme（适配分身等）
        val genericIntent = Intent(Intent.ACTION_VIEW, schemeUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching {
            context.startActivity(genericIntent)
            true
        }.getOrElse {
            // 3. 兜底：尝试 NFC Action 调起 alipay://nfc/app
            val nfcScheme = UjingQrLink.buildHairdryerNfcScheme(cd)
            val nfcIntent = Intent(NfcAdapter.ACTION_NDEF_DISCOVERED, Uri.parse(nfcScheme)).apply {
                setPackage(UjingQrLink.ALIPAY_PACKAGE_NAME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching {
                context.startActivity(nfcIntent)
                true
            }.getOrElse {
                // 4. 降级：Universal Link 网页或浏览器调起
                val ulinkIntent = Intent(Intent.ACTION_VIEW, Uri.parse(ulinkUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching {
                    context.startActivity(ulinkIntent)
                    true
                }.getOrDefault(false)
            }
        }
    }

    if (!launched) {
        Toast.makeText(context, context.getString(R.string.ujing_alipay_not_installed), Toast.LENGTH_SHORT).show()
    }
    return launched
}
