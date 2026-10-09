package com.xingheyuzhuan.shiguangschedule.ui.campus.hairdryer

import android.content.Context
import android.content.Intent
import androidx.annotation.DrawableRes
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.xingheyuzhuan.shiguangschedule.MainActivity
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.HairdryerHistoryEntry

/**
 * 吹风机的桌面快捷方式：**每台机器一个图标**，点一下就直用（不再问、不再扫）。
 *
 * 图标只用预设：矢量、自带底色，浅色 / 深色桌面都看得清，也不必给每台机器配图。
 * 快捷方式里只带 `cd` 与扫码原文，落地仍然走 [com.xingheyuzhuan.shiguangschedule.Destination.HairdryerLaunch]
 * —— 设备该跳支付宝还是跳一卡通页面，由那次判型决定，绝不写死在图标里。
 */
object HairdryerShortcuts {

    /** 预设图标（约一打，够把几台机器区分开）。 */
    data class IconPreset(val id: String, @DrawableRes val resId: Int)

    val ICONS: List<IconPreset> = listOf(
        IconPreset("dryer", R.drawable.ic_hairdryer_dryer),
        IconPreset("drop", R.drawable.ic_hairdryer_drop),
        IconPreset("bolt", R.drawable.ic_hairdryer_bolt),
        IconPreset("flame", R.drawable.ic_hairdryer_flame),
        IconPreset("leaf", R.drawable.ic_hairdryer_leaf),
        IconPreset("star", R.drawable.ic_hairdryer_star),
        IconPreset("heart", R.drawable.ic_hairdryer_heart),
        IconPreset("cloud", R.drawable.ic_hairdryer_cloud),
        IconPreset("sun", R.drawable.ic_hairdryer_sun),
        IconPreset("moon", R.drawable.ic_hairdryer_moon),
        IconPreset("snow", R.drawable.ic_hairdryer_snow),
        IconPreset("gem", R.drawable.ic_hairdryer_gem),
    )

    val DEFAULT_ICON_ID: String = ICONS.first().id

    @DrawableRes
    fun iconResOf(iconId: String?): Int =
        ICONS.firstOrNull { it.id == iconId }?.resId ?: ICONS.first().resId

    /** 快捷方式 ID 与设备码一一对应：同一台机器重复添加就是更新同一个图标。 */
    fun shortcutIdOf(cd: String): String = "hairdryer_$cd"

    /** 当前桌面（Launcher）支不支持「请求添加图标」。 */
    fun isPinSupported(context: Context): Boolean =
        runCatching { ShortcutManagerCompat.isRequestPinShortcutSupported(context) }.getOrDefault(false)

    /** 「加到桌面」这件事的结果：每一种都要给用户一句不同的话，别一律说「失败」。 */
    enum class PinResult {
        /** 系统已受理（接下来由 Launcher 弹「是否添加」确认框）。 */
        REQUESTED,

        /** 桌面上已经有这台机器的图标了，顺手把图标 / 名字更新成这次选的。 */
        UPDATED,

        /** 桌面上已经有，且这次只是又点了一遍（图标没变）。 */
        ALREADY_PINNED,

        /** 当前 Launcher 不支持由应用发起添加快捷方式。 */
        UNSUPPORTED,

        /** 请求被系统 / Launcher 拒了（多半是快捷方式数量上限之类）。 */
        FAILED,
    }

    /**
     * 把某台机器加到桌面（已经加过的走「更新」，不再重复请求）。
     *
     * 为什么要分这么细：**同一个 ID 重复请求钉图标，系统会直接返回失败**
     * （实测 vivo 桌面就是这样，用户看到的现象是「已经加过一次，再加就提示失败」）。
     * 所以先查一遍这台机器是不是已经在桌面上：
     * - 已经在 → 用 `updateShortcuts` 更新图标 / 名字（能改就改，改不了就告诉用户「已经有了」）；
     * - 不在 → 正常请求钉图标。
     *
     * 系统弹「是否添加」确认框之后的结果我们拿不到，所以只能提示「已请求添加」。
     */
    fun addToHome(
        context: Context,
        entry: HairdryerHistoryEntry,
        label: String
    ): PinResult {
        if (!isPinSupported(context)) return PinResult.UNSUPPORTED
        val shortcut = buildShortcut(context, entry, label)

        if (isPinned(context, entry.cd)) {
            val updated = runCatching {
                ShortcutManagerCompat.updateShortcuts(context, listOf(shortcut))
            }.getOrDefault(false)
            return if (updated) PinResult.UPDATED else PinResult.ALREADY_PINNED
        }

        val requested = runCatching {
            ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
        }.getOrDefault(false)
        if (requested) return PinResult.REQUESTED

        // 有些桌面（含 vivo）对「已经在桌面上的 ID」直接回 false：再确认一次，
        // 免得把「已经有了」报成「添加失败」
        return if (isPinned(context, entry.cd)) PinResult.ALREADY_PINNED else PinResult.FAILED
    }

    /** 这台机器是不是已经钉在桌面上了。 */
    fun isPinned(context: Context, cd: String): Boolean = runCatching {
        ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED)
            .any { it.id == shortcutIdOf(cd) }
    }.getOrDefault(false)

    private fun buildShortcut(
        context: Context,
        entry: HairdryerHistoryEntry,
        label: String
    ): ShortcutInfoCompat {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_HAIRDRYER_DEVICE
            putExtra(MainActivity.EXTRA_HAIRDRYER_CD, entry.cd)
            putExtra(MainActivity.EXTRA_HAIRDRYER_RAW, entry.raw)
            // 同一台机器始终复用同一个快捷方式（不会越点越多）
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return ShortcutInfoCompat.Builder(context, shortcutIdOf(entry.cd))
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(IconCompat.createWithResource(context, iconResOf(entry.iconId)))
            .setIntent(intent)
            .build()
    }
}