package com.xingheyuzhuan.shiguangschedule.data.network.wbu

import android.content.Context

/**
 * 选课功能的本地偏好（设备级）。
 *
 * 记录「首次使用免责声明是否已同意」与「是否处于 Mock 测试模式」，用 SharedPreferences 保存，
 * 与 [WbuAuthTransport] / 引导页的既有做法保持一致。
 */
object CourseSelectionPrefs {

    private const val PREFS_NAME = "classflow_course_selection"
    private const val KEY_DISCLAIMER_ACCEPTED = "disclaimer_accepted"
    private const val KEY_MOCK_ENABLED = "mock_enabled"

    fun isDisclaimerAccepted(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DISCLAIMER_ACCEPTED, false)

    fun markDisclaimerAccepted(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DISCLAIMER_ACCEPTED, true)
            .apply()
    }

    fun isMockEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_MOCK_ENABLED, false)

    fun setMockEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_MOCK_ENABLED, enabled)
            .apply()
    }
}
