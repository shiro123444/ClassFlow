// com/shiro/classflow/ui/schoolselection/web/AndroidBridge.kt
package com.xingheyuzhuan.shiguangschedule.ui.schoolselection.web

import android.content.Context
import android.os.Handler
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuSyncEngine
import com.xingheyuzhuan.shiguangschedule.data.repository.normalizeImportedTimeSlots
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebView
import android.widget.Toast
import com.xingheyuzhuan.shiguangschedule.data.db.main.TimeSlot
import com.xingheyuzhuan.shiguangschedule.data.repository.AppSettingsRepository
import com.xingheyuzhuan.shiguangschedule.data.repository.CourseConversionRepository
import com.xingheyuzhuan.shiguangschedule.data.repository.CourseImportExport
import com.xingheyuzhuan.shiguangschedule.data.repository.CourseTableRepository
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.xingheyuzhuan.shiguangschedule.data.repository.TimeSlotRepository

private const val TAG = "AndroidBridge"


// JS 端传来的时间段 JSON 模型
@Serializable
data class TimeSlotJsonModel(
    val number: Int,
    val startTime: String,
    val endTime: String
)

/**
 * AndroidBridge：处理 WebView 与 Native 代码的通信。
 * 通过 Channel 发送 WebUiEvent，实现与 Compose UI 的解耦。
 */
class AndroidBridge(
    private val context: Context,
    private val coroutineScope: CoroutineScope,
    private val webView: WebView,
    private val uiEventChannel: Channel<WebUiEvent>,
    private val courseConversionRepository: CourseConversionRepository,
    private val timeSlotRepository: TimeSlotRepository,
    private val courseTableRepository: CourseTableRepository,
    private val appSettingsRepository: AppSettingsRepository,
    private val onTaskCompleted: () -> Unit
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val handler = Handler(Looper.getMainLooper())
    private var importTableId: String? = null
    private var currentToast: Toast? = null

    // 外部设置导入课表 ID
    fun setImportTableId(tableId: String) {
        this.importTableId = tableId
    }

    /** JS 调用：显示短暂的 Toast 消息。 */
    @JavascriptInterface
    fun showToast(message: String) {
        handler.post {
            currentToast?.cancel()
            val newToast = Toast.makeText(context, message, Toast.LENGTH_SHORT)
            newToast.show()
            currentToast = newToast
        }
    }

    /** JS 调用：显示 Alert 弹窗。通过 Channel 发送事件。 */
    @JavascriptInterface
    fun showAlert(titleText: String, contentText: String, confirmText: String, promiseId: String) {
        handler.post {
            val data = AlertDialogData(titleText, contentText, confirmText)

            val promiseCallback: (Boolean) -> Unit = { confirmed ->
                if (confirmed) {
                    resolveJsPromise(promiseId, "true")
                } else {
                    resolveJsPromise(promiseId, "false")
                }
            }

            val success = uiEventChannel.trySend(
                WebUiEvent.ShowAlert(data, promiseCallback)
            ).isSuccess

            if (!success) {
                rejectJsPromise(promiseId, "原生 UI 事件队列已满。")
            }
        }
    }

    /**
     * JS 调用：显示 Prompt 弹窗，并支持异步 JS 验证。
     * - Compose 发送输入给 onRequestValidation。
     * - Android 执行 JS 验证，结果通过 errorFeedbackFlow 反馈。
     */
    @JavascriptInterface
    fun showPrompt(
        titleText: String,
        tipText: String,
        defaultText: String,
        validatorJsFunction: String,
        promiseId: String
    ) {
        handler.post {
            val data = PromptDialogData(titleText, tipText, defaultText, validatorJsFunction)

            // 错误反馈通道
            val errorFlow = MutableSharedFlow<String?>(extraBufferCapacity = 1)

            // 取消回调：解决 Promise 为 null
            val onCancel: () -> Unit = {
                resolveJsPromise(promiseId, "null")
            }

            // 验证请求回调 (Compose 触发)
            val onRequestValidation: (String) -> Unit = { input ->
                handler.post {
                    // 无验证函数时直接成功
                    if (data.validatorJsFunction.isNullOrEmpty()) {
                        val escapedInput = input.replace("'", "\\'")
                        resolveJsPromise(promiseId, "'$escapedInput'")
                        return@post
                    }

                    // 构造 JS 验证代码
                    val jsScript = "javascript: ${data.validatorJsFunction}('${input.replace("'", "\\'")}')"

                    // 执行 JS 验证
                    webView.evaluateJavascript(jsScript, ValueCallback { result ->
                        val validationResult = result?.trim('\"')

                        if (validationResult.isNullOrEmpty() || validationResult.equals("false", ignoreCase = true)) {
                            // 验证成功：解决 Promise
                            val escapedInput = input.replace("'", "\\'")
                            resolveJsPromise(promiseId, "'$escapedInput'")
                        } else {
                            // 验证失败：发送错误给 Compose UI
                            coroutineScope.launch {
                                errorFlow.emit(validationResult)
                            }
                        }
                    })
                }
            }

            // 发送事件
            val success = uiEventChannel.trySend(
                WebUiEvent.ShowPrompt(data, onRequestValidation, errorFlow.asSharedFlow(), onCancel)
            ).isSuccess

            if (!success) {
                rejectJsPromise(promiseId, "原生 UI 事件队列已满。")
            }
        }
    }

    /** JS 调用：显示单选列表弹窗。通过 Channel 发送事件。 */
    @JavascriptInterface
    fun showSingleSelection(
        titleText: String,
        itemsJsonString: String,
        defaultSelectedIndex: Int,
        promiseId: String
    ) {
        handler.post {
            try {
                val items = json.decodeFromString<List<String>>(itemsJsonString)
                val data = SingleSelectionDialogData(titleText, items, defaultSelectedIndex)

                val promiseCallback: (Int?) -> Unit = { selectedIndex ->
                    if (selectedIndex != null) {
                        resolveJsPromise(promiseId, selectedIndex.toString())
                    } else {
                        resolveJsPromise(promiseId, "null")
                    }
                }

                val success = uiEventChannel.trySend(
                    WebUiEvent.ShowSingleSelection(data, promiseCallback)
                ).isSuccess

                if (!success) {
                    rejectJsPromise(promiseId, "原生 UI 事件队列已满。")
                }

            } catch (e: Exception) {
                Log.e(TAG, "解析单选列表 itemsJsonString 失败: ${e.message}", e)
                Toast.makeText(context, "单选列表数据错误，无法显示。", Toast.LENGTH_LONG).show()
                rejectJsPromise(promiseId, "选项列表 JSON 无效: ${e.message}")
            }
        }
    }

    /** JS 调用：将课程数据传回 Android 端进行保存。 */
    @JavascriptInterface
    fun saveImportedCourses(coursesJsonString: String, promiseId: String) {
        Log.d(TAG, "接收到课程数据，大小: ${coursesJsonString.length / 1024} KB")
        coroutineScope.launch(Dispatchers.Main) {
            try {
                val tableId = importTableId

                if (tableId == null) {
                    Toast.makeText(context, "导入失败：未选择课表。", Toast.LENGTH_LONG).show()
                    rejectJsPromise(promiseId, "课表选择已取消。")
                    return@launch
                }

                val importedCoursesList = json.decodeFromString<List<CourseImportExport.ImportCourseJsonModel>>(coursesJsonString)
                // 依据「保留教师工号」开关：默认去除教师名中的工号
                val keepTeacherId = WbuSyncEngine.getKeepTeacherId(context)
                val adjustedList = if (keepTeacherId) {
                    importedCoursesList
                } else {
                    importedCoursesList.map { it.copy(teacher = WbuSyncEngine.cleanTeacherId(it.teacher)) }
                }
                courseConversionRepository.importCoursesFromList(tableId, adjustedList)

                Toast.makeText(context, "课程导入成功！课表已更新。", Toast.LENGTH_LONG).show()
                resolveJsPromise(promiseId, "true")
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(context, "课程导入失败: ${e.message}", Toast.LENGTH_LONG).show()
                rejectJsPromise(promiseId, "课程导入失败: ${e.message}")
            }
        }
    }

    /**
     * 将课表配置数据传回 Android 端进行保存。
     *
     * @param configJsonString 课表配置的 JSON 字符串，对应 CourseConfigJsonModel。
     * @param promiseId 用于异步回调的 Promise ID。
     */
    @JavascriptInterface
    fun saveCourseConfig(configJsonString: String, promiseId: String) {
        Log.d(TAG, "接收到课表配置数据，大小: ${configJsonString.length} 字节")
        coroutineScope.launch(Dispatchers.Main) {
            try {
                val tableId = importTableId

                if (tableId == null) {
                    Toast.makeText(context, "配置导入失败：未选择目标课表。", Toast.LENGTH_LONG).show()
                    rejectJsPromise(promiseId, "课表选择已取消或未设置。")
                    return@launch
                }

                // 解析 JSON 字符串 (由于模型已设置默认值，缺失可选字段不会报错)
                val importedConfig = json.decodeFromString<CourseImportExport.CourseConfigJsonModel>(configJsonString)

                // 调用 Repository 层的业务逻辑更新配置
                // 该方法会处理配置的合并逻辑（例如保留 showWeekends）
                courseConversionRepository.importCourseConfig(tableId, importedConfig)

                Toast.makeText(context, "课表配置导入成功！", Toast.LENGTH_LONG).show()
                resolveJsPromise(promiseId, "true")
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(context, "课表配置导入失败: ${e.message}", Toast.LENGTH_LONG).show()
                rejectJsPromise(promiseId, "课表配置导入失败: ${e.message}")
            }
        }
    }

    /** JS 调用：将预设时间段数据传回 Android 端进行保存。 */
    @JavascriptInterface
    fun savePresetTimeSlots(timeSlotsJsonString: String, promiseId: String) {
        Log.d(TAG, "接收到预设时间段数据，大小: ${timeSlotsJsonString.length / 1024} KB")
        coroutineScope.launch(Dispatchers.Main) {
            try {
                val tableId = importTableId

                if (tableId == null) {
                    Toast.makeText(context, "导入失败：未选择课表。", Toast.LENGTH_LONG).show()
                    rejectJsPromise(promiseId, "课表选择已取消。")
                    return@launch
                }

                val importedTimeSlotsJson = json.decodeFromString<List<TimeSlotJsonModel>>(timeSlotsJsonString)

                val rawTimeSlots = importedTimeSlotsJson.map { jsonModel ->
                    TimeSlot(
                        number = jsonModel.number,
                        startTime = jsonModel.startTime,
                        endTime = jsonModel.endTime,
                        courseTableId = tableId
                    )
                }

                val normalizedTimeSlots = normalizeImportedTimeSlots(
                    timeSlots = rawTimeSlots,
                    classDuration = 45,
                    breakDuration = 10,
                    tableId = tableId
                )

                timeSlotRepository.replaceAllForCourseTable(tableId, normalizedTimeSlots)

                Toast.makeText(context, "预设时间段导入成功！", Toast.LENGTH_LONG).show()
                resolveJsPromise(promiseId, "true")
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(context, "预设时间段导入失败: ${e.message}", Toast.LENGTH_LONG).show()
                rejectJsPromise(promiseId, "预设时间段导入失败: ${e.message}")
            }
        }
    }

    /** JS 调用：将学号、学期代码等元数据传回 Android 端保存到课表及全局会话中。 */
    @JavascriptInterface
    fun saveTableMeta(metaJsonString: String, promiseId: String) {
        Log.d(TAG, "接收到课表元数据: $metaJsonString")
        coroutineScope.launch(Dispatchers.Main) {
            try {
                // 优先使用当前导入上下文中的 importTableId；若已置空则回退取当前活跃课表
                val tableId = importTableId ?: appSettingsRepository.getAppSettingsOnce()?.currentCourseTableId
                if (tableId == null) {
                    rejectJsPromise(promiseId, "未选择目标课表。")
                    return@launch
                }
                val obj = runCatching { JSONObject(metaJsonString) }.getOrNull()
                val studentId = obj?.optString("studentId", "")?.trim()?.takeIf { it.isNotBlank() }
                val semesterCode = obj?.optString("semesterCode", "")?.trim()?.takeIf { it.isNotBlank() }
                val tableName = obj?.optString("tableName", "")?.trim()?.takeIf { it.isNotBlank() }

                val table = courseTableRepository.getCourseTableById(tableId)
                if (table != null) {
                    val allTables = courseTableRepository.getAllCourseTables().first()
                    val effectiveSid = studentId ?: table.studentId.orEmpty()
                    val effectiveSemester = semesterCode ?: table.semesterCode.orEmpty()

                    // 若传入了指定名称优先使用；若当前课表为默认的“我的课表”且有学期信息，则自动重命名为规范学期名称
                    val targetName = when {
                        tableName != null -> tableName
                        table.name == "我的课表" && effectiveSemester.isNotBlank() ->
                            WbuSyncEngine.computeNonConflictingTableName(effectiveSemester, effectiveSid, allTables)
                        else -> table.name
                    }

                    Log.d(TAG, "更新课表 $tableId 元数据: 原名=${table.name} -> 目标名=$targetName, 学号=$studentId, 学期=$semesterCode")

                    val updated = table.copy(
                        name = targetName,
                        studentId = studentId ?: table.studentId,
                        semesterCode = semesterCode ?: table.semesterCode
                    )
                    courseTableRepository.updateCourseTable(updated)
                    if (studentId != null) {
                        WbuSyncEngine.setSavedStudentId(context, studentId)
                    }
                }
                resolveJsPromise(promiseId, "true")
            } catch (e: Exception) {
                Log.e(TAG, "保存课表元数据失败: ${e.message}", e)
                rejectJsPromise(promiseId, "保存课表元数据失败: ${e.message}")
            }
        }
    }

    /** JS 调用：通知 Native 端 JS 任务已逻辑完成。 */
    @JavascriptInterface
    fun notifyTaskCompletion() {
        handler.post {
            importTableId = null
            onTaskCompleted()
        }
    }

    /** 在 JS 环境中解决 Promise。 */
    private fun resolveJsPromise(promiseId: String, result: String) {
        handler.post {
            webView.evaluateJavascript("window._resolveAndroidPromise('$promiseId', $result);", null)
        }
    }

    /** 在 JS 环境中拒绝 Promise。 */
    private fun rejectJsPromise(promiseId: String, error: String) {
        handler.post {
            val escapedError = error.replace("'", "\\'")
            webView.evaluateJavascript("window._rejectAndroidPromise('$promiseId', '$escapedError');", null)
        }
    }
}
