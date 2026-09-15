package com.xingheyuzhuan.shiguangschedule.data.network.wbu

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 统一认证（金智 CAS）登录二维码的链接解析。
 *
 * 二维码由服务端渲染，真实内容形如：
 * `http://ids.wbu.edu.cn/authserver/qrCode/qrCodeLogin.do?uuid=QR-xxxxxxxxxxxxxxxxxxxx`
 *
 * 只校验 path 与 uuid 前缀，不校验 host：直连与 WebVPN 代理宿主的 path 一致，
 * 而请求始终发往本机会话所属的 ids 基址，host 不参与判定。
 */
object CasQrLink {

    private const val PATH_SUFFIX = "/authserver/qrCode/qrCodeLogin.do"

    /** uuid 形如 `QR-` + 随机串。 */
    private val UUID_PATTERN = Regex("^QR-[A-Za-z0-9]+$")

    /** 从二维码原文解析 uuid；不是统一认证登录二维码时返回 null。 */
    fun parseUuid(raw: String): String? {
        val url = raw.trim().toHttpUrlOrNull() ?: return null
        if (!url.encodedPath.endsWith(PATH_SUFFIX)) return null
        val uuid = url.queryParameter("uuid")?.trim().orEmpty()
        return uuid.takeIf { UUID_PATTERN.matches(it) }
    }
}
