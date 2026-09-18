package com.xingheyuzhuan.shiguangschedule.data.model.wbu

/**
 * 凭据所属的服务类型。
 *
 * 存储层按「服务类型 × 账号」两维组织凭据 key（见 WbuAuthTransport 的 key 生成），
 * 从而支持各服务独立保存/切换账号。当前为单用户阶段，所有服务仅维护默认账号
 * [DEFAULT_ACCOUNT]；结构已预埋多账号，将来无需迁移数据。
 */
enum class CredentialService(val id: String) {
    /** 统一身份认证 (IDS / CAS)。 */
    UNIFIED_AUTH("ids"),

    /** 教务系统 (JWXT)。当前与统一认证共用同一密码。 */
    JIAOWU("jwxt"),

    /** 图书馆 (汇文 OPAC)。无密码，仅会话。 */
    LIBRARY("library"),

    /** WebVPN 门户。密码 + 网关通行证 TWFID。 */
    WEBVPN("webvpn"),

    /** 一卡通。暂未接入，预留占位。 */
    CAMPUS_CARD("card"),

    /** WebDAV 备份。地址/用户名/密码，无 token。 */
    WEBDAV("webdav");

    companion object {
        /** 单用户阶段的默认账号标识。 */
        const val DEFAULT_ACCOUNT = "primary"

        fun fromId(id: String): CredentialService? = entries.firstOrNull { it.id == id }
    }
}
