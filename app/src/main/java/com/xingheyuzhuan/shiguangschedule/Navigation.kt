package com.xingheyuzhuan.shiguangschedule

import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.NavMetadataKey
import kotlinx.serialization.Serializable

/**
 * 导航元数据 Key 定义
 */
object ShiguangNavMetadata {
    /** 作用：标记是否为一级主界面，用于控制切换动画（主界面间无过渡） */
    object IsMainScreenKey : NavMetadataKey<Boolean>
}

/**
 * 应用所有目的地（页面）的定义
 * 采用 Kotlin Serialization 实现类型安全的参数传递（上游 navigation3 同步）
 */
@Serializable
sealed interface Destination : NavKey {

    // --- 一级导航页面（底栏对应页面，通常无滑动动画） ---

    @Serializable data object CourseSchedule : Destination
    @Serializable data object Settings : Destination
    @Serializable data object TodaySchedule : Destination

    // --- 普通功能页面（二级页面，通常使用标准滑动动画） ---

    @Serializable data object TimeSlotSettings : Destination
    @Serializable data object ManageCourseTables : Destination
    @Serializable data object SchoolSelectionListScreen : Destination
    @Serializable data object CourseTableConversion : Destination
    @Serializable data object NotificationSettings : Destination
    @Serializable data object MoreOptions : Destination
    @Serializable data object LanguageSettings : Destination
    @Serializable data object OpenSourceLicenses : Destination
    @Serializable data object UpdateRepo : Destination
    @Serializable data object QuickActions : Destination
    @Serializable data object TweakSchedule : Destination
    @Serializable data object QuickDelete : Destination
    @Serializable data object ContributionList : Destination
    @Serializable data object CourseManagementList : Destination
    @Serializable data object StyleSettings : Destination
    @Serializable data object ThemeSettings : Destination
    @Serializable data object BackupAndRestore : Destination

    // ── ClassFlow 独有页面 ──

    @Serializable data object WallpaperAdjust : Destination
    @Serializable data object GradeQuery : Destination
    @Serializable data object FreeClassroomQuery : Destination
    @Serializable data object AcademicProgress : Destination
    @Serializable data object LibraryBorrow : Destination
    @Serializable data object CredentialManagement : Destination
    @Serializable data object CourseSelection : Destination
    @Serializable data object QrScan : Destination

    /** 一卡通付款码（原生取码页面，无需进入平台 WebApp）。 */
    @Serializable data object CampusCardPayCode : Destination

    /**
     * 吹风机页（校园服务 → 吹风机）：判型设置 + 使用记录 + 桌面快捷方式。
     *
     * 记录是**本机**的：扫过哪几台、各自是蓝牙还是 4G、给它们起的名字与图标都只存本地。
     */
    @Serializable data object HairdryerHub : Destination

    // --- 动态传参页面 ---

    @Serializable
    data class AdapterSelection(
        val schoolId: String,
        val schoolName: String,
        val categoryNumber: Int,
        val resourceFolder: String
    ) : Destination

    @Serializable
    data class WebView(
        val initialUrl: String? = "about:blank",
        val assetJsPath: String? = null,
        /** 通用链接节点等非 WBU 场景：隐藏底部「导入课程」引导栏。 */
        val hideImportBar: Boolean = false
    ) : Destination

    @Serializable
    data class WebApp(
        val appId: String,
        val initialTargetUrl: String? = null,
        val pendingAutoScan: String? = null,
        /**
         * 页面**自己授权完成之后**才落上去的 hash 路由（例：U净 吹风机的
         * `#/deviceSelector?deviceId=…`）。
         *
         * 为什么不能直接拼进 [initialTargetUrl]：这类页面把授权流程放在 `/`「授权中」那一步，
         * 而设备页是 `requireAuth` 路由 —— 冷启动直接落在设备页时，路由守卫拿不到令牌，
         * 会把人扔到登录页。所以由容器在页面里等令牌出现，再切路由（见 `WebAppScreen`）。
         */
        val pendingHashRoute: String? = null
    ) : Destination

    @Serializable
    data class UjingWater(
        val cd: String,
        val scanId: Long = 0L
    ) : Destination

    /**
     * 通用链接节点（`/url/{code}`、短别名 `/u/{code}`）。
     *
     * 回退栈里只携带 code 与内嵌载荷，不携带整条 raw URL：
     * 既避免 fragment 参与导航序列化，也保证「复制链接」统一使用规范形式重建。
     */
    @Serializable
    data class LinkHub(
        /** 服务端短码；纯内嵌节点为 null。 */
        val code: String? = null,
        /** 内嵌紧凑载荷（base64url）。 */
        val inline: String? = null,
        /** 链接来源（`scheme://host[:port]`）：短码按它请求，兼容本地联调地址。 */
        val origin: String? = null,
        val scanId: Long = 0L
    ) : Destination

    @Serializable
    data class AddEditCourse(
        val courseId: String? = null
    ) : Destination

    @Serializable
    data class CourseManagementDetail(
        val courseName: String
    ) : Destination

    /**
     * 洗浴设备直达链接（`/s/{系统}/{设备号}[/{端口}]`）的落地页。
     *
     * 与 [UjingWater]、[WebApp] 同级：链接里就是设备身份，落地即进洗浴流程。
     * 独立成一个目的地（而不是复用 [LinkHub]）是因为洗浴的动作边界与「直接扫控水器二维码」
     * 完全等价、恒免确认，复用节点页只会白搭一层「分享的内容」确认页外壳（还会闪一下）。
     */
    @Serializable
    data class ShowerDirect(
        /** `y`（1 栋智能控水）或 `l`（2-3 栋 lifeService），见 `CampusShowerLink`。 */
        val system: String,
        /** 1 栋为 5 位机号；2-3 栋为水表设备号 imei。 */
        val code: String,
        /** 多路控水器端口（仅 2-3 栋可带）。 */
        val port: String? = null,
        val scanId: Long = 0L
    ) : Destination

    /**
     * 马影河 2-3 栋淋浴**原生**用水页（生活服务 lifeService，`appId=65`）。
     *
     * 扫码 / `/s/l/{设备}` 深链 / 链接节点在入口处先做一次设备正证（`getDevicesType`），
     * 确认是淋浴（101）就直接进这一页 —— 不再把一卡通那个「扩展应用」网页拽进来，
     * 于是「切后台被回收后回来」「转屏」都不会再自动重开一单（页面每步都以服务端状态为准）。
     * 其它设备类型（洗衣机 / 饮水机 / 电吹风…）仍走 [WebApp]。
     *
     * [implid] / [feeitemid] 是平台启动地址里给的计费上下文（换缴费项就是另一组值），
     * 所以随参数带过来，页面不写死。
     */
    @Serializable
    data class ShowerWater(
        /** 2-3 栋水表设备号（imei）。 */
        val deviceId: String,
        /** 多路控水器端口，可空。 */
        val port: String? = null,
        val implid: String,
        val feeitemid: String,
        /**
         * 平台那个生活服务页面的地址（带票据、**不含** `scanResult`）。
         *
         * 只作为逃生口：原生这一步只要用户觉得不对（或服务端行为例外），可以一键切回网页，
         * 而因为不带一次性自启载体，切回去也不会顺手开一单。
         */
        val webFallbackUrl: String? = null,
        val scanId: Long = 0L
    ) : Destination

    /**
     * 吹风机分流**过渡页**：先把「这台是手机蓝牙还是云端（控制盒 / 4G）」定下来，再决定去哪。
     *
     * 三种入口（扫码 / NFC / 桌面快捷方式与记录直达）共用这一个页面，判型、首次确认弹窗、
     * 跳转都只写一份：
     * - 蓝牙（或探测失败兜底）→ 应用层品牌过场 + 调起支付宝 U净 小程序；
     * - 云端 → 换成 [WebApp]（一卡通「自助吹风」页面，地址已停在左右机选择那一步）。
     *
     * [source] 决定这次用哪套判型设置（见 `HairdryerLaunchSource`）：扫码用扫码设置、
     * NFC 用 NFC 设置、直达用这条记录自己的类型。
     */
    @Serializable
    data class HairdryerLaunch(
        /** 设备码（`cd`）。 */
        val cd: String,
        /** 扫码原文（逐字回传：换成别的写法服务端会答「当前设备未绑定。」）。 */
        val raw: String = "",
        val source: String = com.xingheyuzhuan.shiguangschedule.data.model.wbu.HairdryerLaunchSource.SCAN.name,
        val scanId: Long = 0L
    ) : Destination
}

/**
 * 作用：快速判断目的地是否属于“一级导航”，供 NavEntry 注入元数据
 */
val Destination.isMainScreen: Boolean
    get() = this is Destination.CourseSchedule ||
            this is Destination.Settings ||
            this is Destination.TodaySchedule

/**
 * 作用：判定两个目的地之间是否为「无缝交接」，需要禁用转场动画。
 *
 * [Destination.ShowerDirect] / [Destination.LinkHub] 这两个「过渡页」与它们要跳转的
 * [Destination.WebApp] 首屏都由同一个品牌过渡组件（`WbuLoadingPlaceholder`）铺满，两侧像素一致；
 * 此时若照常播 300ms 滑动，反而会看到「过渡页滑走、新页滑入」两段动画连在一起。硬切才是真正的无缝。
 *
 * 说明：`LinkHub -> WebApp` 只可能来自免确认节点（`campus_shower` 是当前唯一会返回
 * `OpenWebApp` 的处理器，且恒为免确认），需确认的节点不会走到这里。
 */
fun isSeamlessHandoff(from: Destination?, to: Destination?): Boolean =
    (from is Destination.ShowerDirect && to is Destination.WebApp) ||
            (from is Destination.WebApp && to is Destination.ShowerDirect) ||
            (from is Destination.LinkHub && to is Destination.WebApp) ||
            // 吹风机过渡页与 WebApp 首屏同为品牌过渡组件，硬切才看不出切换
            (from is Destination.HairdryerLaunch && to is Destination.WebApp)
