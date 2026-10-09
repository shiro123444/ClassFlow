package com.xingheyuzhuan.shiguangschedule.data.model.wbu

import kotlinx.serialization.Serializable

/**
 * 吹风机设备类型：**手机蓝牙**直连，还是**云端（控制盒 / 4G）**。
 *
 * 判据来自一卡通「自助吹风」页面自己的分支：`moduleType == 7` 才走控制盒下单，
 * 其余（实测本校区为 1）在网页容器里一律弹「暂不支持蓝牙设备」。
 */
@Serializable
enum class HairdryerDeviceType {
    /** 手机蓝牙：只有支付宝 / U净 App 这类能开蓝牙的容器能用（跳支付宝）。 */
    BLUETOOTH,

    /** 云端（控制盒 / 4G）：一卡通「自助吹风」页面自己就能下单（跳一卡通 U净 页面）。 */
    CLOUD;

    companion object {
        fun fromNameOrNull(value: String?): HairdryerDeviceType? =
            entries.firstOrNull { it.name == value }
    }
}

/**
 * 这台机器**已经知道**是什么类型时，等价于哪个判型模式 ——
 * 用于「直达」（桌面快捷方式 / 记录里点一下）：上次怎么用的这次就怎么用。
 */
val HairdryerDeviceType.detectionMode: HairdryerDetectionMode
    get() = if (this == HairdryerDeviceType.CLOUD) {
        HairdryerDetectionMode.CLOUD
    } else {
        HairdryerDetectionMode.BLUETOOTH
    }

/**
 * 扫码 / NFC 遇到吹风机码时的判型策略（设置页可改）。
 *
 * 默认 [AUTO]：先看码的印刷格式（本地、瞬时、不耗票据），格式不认识或判据已被用户否掉时，
 * 再走接口探测（`controlBox/devices/scan` + `/info` 的 `moduleType`）。
 */
@Serializable
enum class HairdryerDetectionMode {
    /** 自动：格式优先，探测兜底。 */
    AUTO,

    /** 自动（仅设备信息）：不看格式，一律问接口。 */
    AUTO_DEVICE,

    /** 蓝牙：永远跳支付宝（老校区老码、或探测结果不可信时用）。 */
    BLUETOOTH,

    /** 4G：永远跳一卡通「自助吹风」页面。 */
    CLOUD;

    companion object {
        fun fromNameOrNull(value: String?): HairdryerDetectionMode? =
            entries.firstOrNull { it.name == value }
    }
}

/**
 * 设备码的**印刷格式**（纯本地判据，见 `UjingHairdryerCode`）。
 *
 * 实测本校区：
 * - 16 位纯数字（`0014202206120446`）→ 手机蓝牙机（`moduleType = 1`）；
 * - 32 位十六进制（`00006D11488800030263110002870000`）→ 云端 / 4G 机（`moduleType = 7`）。
 */
@Serializable
enum class HairdryerCodeFormat {
    /** 16 位纯数字。 */
    LEGACY_16,

    /** 32 位十六进制。 */
    CLOUD_32,

    /** 不认识的格式：只能靠接口探测。 */
    UNKNOWN;

    /** 格式给出的猜测；[UNKNOWN] 没有猜测。 */
    val guess: HairdryerDeviceType?
        get() = when (this) {
            LEGACY_16 -> HairdryerDeviceType.BLUETOOTH
            CLOUD_32 -> HairdryerDeviceType.CLOUD
            UNKNOWN -> null
        }
}

/** 一次判型结论的来源（用来在记录列表里解释「凭什么说是蓝牙 / 4G」）。 */
@Serializable
enum class HairdryerTypeSource {
    /** 接口探测（`moduleType`）——最可信。 */
    PROBE,

    /** 本地格式判据（用户没否掉过）。 */
    FORMAT,

    /** 用户在设置里手动指定。 */
    MANUAL,

    /** 探测失败、也没有可用判据，按蓝牙（支付宝）兜底 —— 与改造前的行为一致。 */
    FALLBACK,
}

/**
 * 吹风机分流过渡页（`Destination.HairdryerLaunch`）的入口来源。
 *
 * 来源决定「这次该用哪套判型设置」：扫码用扫码设置、NFC 用 NFC 设置、
 * 直达（桌面快捷方式 / 记录里点一下）用这条记录自己的类型。
 */
@Serializable
enum class HairdryerLaunchSource {
    /** 全局扫一扫。 */
    SCAN,

    /** 直达：桌面快捷方式、吹风机记录里的「直接使用」。 */
    DIRECT,

    /** NFC 触碰（`/hd/{cd}` 深链）。 */
    NFC;

    companion object {
        fun fromNameOrNull(value: String?): HairdryerLaunchSource? =
            entries.firstOrNull { it.name == value }
    }
}

/** NFC 触碰吹风机标签时的判型策略；默认跟随扫码设置。 */
@Serializable
enum class HairdryerNfcMode {
    /** 与扫码一致（默认）。 */
    FOLLOW,

    AUTO,

    AUTO_DEVICE,

    BLUETOOTH,

    CLOUD;

    companion object {
        fun fromNameOrNull(value: String?): HairdryerNfcMode? =
            entries.firstOrNull { it.name == value }
    }
}

/**
 * 一条吹风机使用记录（**只存本机**，不参与 WebDAV 同步）。
 *
 * 显示规则（见 `HairdryerHubScreen`）：大标题 = [alias] ?: [storeName] ?: [cd]，
 * 小标题 = [subjectName] ?: [hubTypeName]。
 */
@Serializable
data class HairdryerHistoryEntry(
    /** 设备码（`cd`），同一台机器唯一 —— 也是记录的键。 */
    val cd: String,
    /** 扫码原文（逐字保留：探测与支付宝调起都要求原样回传）。 */
    val raw: String = "",
    /** 上次实际使用的跳转方式。 */
    val type: HairdryerDeviceType = HairdryerDeviceType.BLUETOOTH,
    /** 这个结论怎么来的。 */
    val source: HairdryerTypeSource = HairdryerTypeSource.FALLBACK,
    /** 云端设备的控制盒 ID（格式判据下的蓝牙记录可能为空，之后由后台补测填上）。 */
    val deviceId: String? = null,
    /** 控制盒上可用的子机 ID（页面路由 `subDeviceId`，用来查店铺名）。 */
    val subDeviceId: String? = null,
    /** 控制盒类型名（实测「电吹风」）。 */
    val hubTypeName: String? = null,
    /** 控制盒编号（实测 33301）。 */
    val deviceNo: String? = null,
    /** 店铺名（大标题，如「南B-11」「三号宿舍。-3」）。 */
    val storeName: String? = null,
    /** 服务主体名（小标题，如「武汉商学院26-本部」）。 */
    val subjectName: String? = null,
    /** 用户重命名，优先级最高。 */
    val alias: String? = null,
    /** 桌面快捷方式图标（预设 ID，见 `HairdryerShortcutIcons`）。 */
    val iconId: String? = null,
    val useCount: Int = 1,
    /** 上次使用时间（毫秒）。 */
    val lastUsedAt: Long = 0L,
) {
    /** 列表大标题：用户起的名字 → 店铺名 → 设备码。 */
    val displayTitle: String
        get() = alias?.takeIf { it.isNotBlank() }
            ?: storeName?.takeIf { it.isNotBlank() }
            ?: cd

    /** 列表小标题：服务主体 → 控制盒类型名；都没有就留空（UI 自行决定不显示）。 */
    val displaySubtitle: String?
        get() = subjectName?.takeIf { it.isNotBlank() }
            ?: hubTypeName?.takeIf { it.isNotBlank() }
}
