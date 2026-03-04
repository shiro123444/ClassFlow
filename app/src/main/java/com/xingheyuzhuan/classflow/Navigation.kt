package com.xingheyuzhuan.classflow

import android.net.Uri

sealed class Screen(val route: String) {
    object CourseSchedule : Screen("course_schedule")

    object Settings : Screen("settings")

    object TodaySchedule : Screen("today_schedule")

    object TimeSlotSettings : Screen("time_slot_settings")

    object ManageCourseTables : Screen("manage_course_tables")

    object SchoolSelectionListScreen : Screen("school_selection")

    object CourseTableConversion : Screen("course_table_conversion")

    object AdapterSelection : Screen("adapterSelection/{schoolId}/{schoolName}/{categoryNumber}/{resourceFolder}") {

        fun createRoute(schoolId: String, schoolName: String, categoryNumber: Int, resourceFolder: String): String {
            val encodedSchoolName = Uri.encode(schoolName)
            val encodedResourceFolder = Uri.encode(resourceFolder)
            return "adapterSelection/$schoolId/$encodedSchoolName/$categoryNumber/$encodedResourceFolder"
        }
    }
    object WebView : Screen("web_view/{initialUrl}/{assetJsPath}") {
        fun createRoute(initialUrl: String?, assetJsPath: String?): String {
            val urlParam = Uri.encode(initialUrl ?: "about:blank")
            val pathParam = Uri.encode(assetJsPath ?: "")
            return "web_view/$urlParam/$pathParam"
        }
    }

    object NotificationSettings : Screen("notification_settings")

    object AddEditCourse : Screen("add_edit_course_route/{courseId}") {
        // 用于编辑现有课程，将 courseId 传递给路由
        fun createRouteWithCourseId(courseId: String): String {
            return "add_edit_course_route/$courseId"
        }
        // 路由只需要导航到页面本身，并传递 null 占位符。
        fun createRouteForNewCourse(): String {
            return "add_edit_course_route/null"
        }
    }

    object MoreOptions : Screen("more_options")

    object OpenSourceLicenses : Screen("open_source_licenses")

    object UpdateRepo : Screen("update_repo")

    object QuickActions : Screen("quick_actions")

    object TweakSchedule : Screen("tweak_schedule")

    object QuickDelete : Screen("quick_delete")
    object ContributionList : Screen("contribution_list")

    object CourseManagementList : Screen("course_management_list")

    object CourseManagementDetail : Screen("course_management_detail/{courseName}") {
        /**
         * 创建二级页面的路由。
         * 课程名称需要进行 URI 编码，以防包含特殊字符。
         */
        fun createRoute(courseName: String): String {
            val encodedCourseName = Uri.encode(courseName)
            return "course_management_detail/$encodedCourseName"
        }
    }

    object StyleSettings : Screen("style_settings")
    object WallpaperAdjust : Screen("wallpaper_adjust")
}
