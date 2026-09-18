package com.xingheyuzhuan.shiguangschedule.data.network.wbu

import com.xingheyuzhuan.shiguangschedule.data.model.wbu.CourseSelectionInit
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.KkxFrom
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.RetakeCourse
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.SelectedCourse
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.SelectionOutcome
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.TeachingClass

/**
 * 选课数据源抽象：真实教务（[WbuCourseSelectionClient]）与本地 Mock（[MockCourseSelectionDataSource]）共用同一套调用契约，
 * 便于在无开放批次 / 无校园网时用暗号手势切到 Mock 自测。
 */
interface CourseSelectionDataSource {

    suspend fun queryInit(): Result<CourseSelectionInit>

    suspend fun queryClasses(from: KkxFrom, pcid: String, pcenc: String): Result<List<TeachingClass>>

    suspend fun queryChildClasses(
        jxbid: String,
        pcid: String,
        pcenc: String,
        from: String
    ): Result<List<String>>

    suspend fun selectClass(
        jxbid: String,
        pcid: String,
        zjxbid: String = "",
        sfqc: Boolean = false
    ): Result<SelectionOutcome>

    suspend fun dropClass(jxbid: String, pcid: String): Result<String>

    suspend fun cancelWaitlist(jxbid: String, pcid: String): Result<String>

    suspend fun querySelectedCourses(): Result<List<SelectedCourse>>

    suspend fun queryRetakeBind(pcid: String): Result<List<RetakeCourse>>

    suspend fun queryRetakeClasses(cxmdid: String, kcid: String, pcid: String): Result<List<TeachingClass>>

    suspend fun retakeSelect(
        jxbid: String,
        fjxbid: String,
        kcid: String,
        cxmdid: String,
        pcid: String
    ): Result<SelectionOutcome>

    suspend fun retakeDrop(
        jxbid: String,
        kcid: String,
        cxmdid: String,
        pcid: String
    ): Result<String>
}
