package com.xingheyuzhuan.shiguangschedule

import android.content.Context

/**
 * navigation3 环境下替代 NavController 的窄接口。
 * Screen 组件统一接收此接口，内部不再依赖字符串路由。
 */
interface NavBridge {
    val context: Context

    /** 导航到目标页面（二级页面：入栈） */
    fun navigate(destination: Destination)

    /** 底栏一级页面切换（清空栈后切换，避免残留二级页面栈） */
    fun navigateToMain(destination: Destination)

    /** 用目标页面替换当前栈顶页面（用于「扫码页 → 结果页」这类不保留返回栈的场景） */
    fun replace(destination: Destination)

    /** 返回上一页 */
    fun popBackStack()

    /** 向上返回（工具栏返回按钮） */
    fun navigateUp()
}
