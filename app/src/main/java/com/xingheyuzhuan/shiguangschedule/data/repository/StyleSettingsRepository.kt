package com.xingheyuzhuan.shiguangschedule.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import com.xingheyuzhuan.shiguangschedule.data.model.DualColor
import com.xingheyuzhuan.shiguangschedule.data.model.ScheduleGridStyle
import com.xingheyuzhuan.shiguangschedule.data.model.schedule_style.BorderTypeProto
import com.xingheyuzhuan.shiguangschedule.data.model.schedule_style.ScheduleGridStyleProto
import com.xingheyuzhuan.shiguangschedule.data.model.schedule_style.ScheduleModeProto
import com.xingheyuzhuan.shiguangschedule.data.model.toCompose
import com.xingheyuzhuan.shiguangschedule.data.model.toProto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject

// 1. DataStore 文件名常量
const val SCHEDULE_STYLE_DATASTORE_FILE_NAME = "schedule_style_settings.pb"

// 2. DataStore Serializer (序列化器, Wire ADAPTER 风格)
object ScheduleStyleSerializer : Serializer<ScheduleGridStyleProto> {
    override val defaultValue: ScheduleGridStyleProto
        get() = ScheduleGridStyleProto()

    override suspend fun readFrom(input: InputStream): ScheduleGridStyleProto {
        return try {
            ScheduleGridStyleProto.ADAPTER.decode(input)
        } catch (exception: Exception) {
            ScheduleGridStyleProto()
        }
    }

    override suspend fun writeTo(t: ScheduleGridStyleProto, output: OutputStream) {
        ScheduleGridStyleProto.ADAPTER.encode(output, t)
    }
}

// 定义扩展属性 (单例声明)
/**
 * 扩展属性：定义 ScheduleGridStyle 的 DataStore。
 * 放在这里可以确保单例性，同时让实现细节对外部隐藏。
 */
val Context.scheduleGridStyleDataStore: DataStore<ScheduleGridStyleProto> by dataStore(
    fileName = SCHEDULE_STYLE_DATASTORE_FILE_NAME,
    serializer = ScheduleStyleSerializer
)

/**
 * 样式自持版本号的中央备份信封
 * 放在数据源头，对齐课表 envelope 的设计，保持物理传输字段名 appVersionCode
 */
@kotlinx.serialization.Serializable
data class StyleBackupEnvelope(
    val backupTimestamp: Long,
    val appVersionCode: Int,
    val styleProtoBytes: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as StyleBackupEnvelope

        if (backupTimestamp != other.backupTimestamp) return false
        if (appVersionCode != other.appVersionCode) return false
        if (!styleProtoBytes.contentEquals(other.styleProtoBytes)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = backupTimestamp.hashCode()
        result = 31 * result + appVersionCode
        result = 31 * result + styleProtoBytes.contentHashCode()
        return result
    }
}

// 4. StyleSettingsRepository (仓库类)
/**
 * 样式设置的数据仓库，负责与 Proto DataStore 进行交互。
 */
class StyleSettingsRepository @Inject constructor(
    private val dataStore: DataStore<ScheduleGridStyleProto>,
    @ApplicationContext private val context: Context
) {

    /**
     * 获取当前样式的快照（一次性读取，用于业务逻辑校验）
     */
    suspend fun getStyleOnce(): ScheduleGridStyle {
        return dataStore.data.map { it.toCompose() }.first()
    }

    /**
     * 响应式样式流（用于 UI 订阅刷新）
     */
    val styleFlow: Flow<ScheduleGridStyle> = dataStore.data
        .map { proto -> proto.toCompose() }

    companion object {
        /**
         * 样式备份协议版本号
         */
        const val STYLE_SCHEMA_VERSION = 1
    }

    // --- 备份与恢复扩展 API ---

    /**
     * 仅导出当前原生的样式配置字节数组 (排除壁纸路径)
     */
    suspend fun exportRawStyleBytes(): ByteArray {
        val currentProto = dataStore.data.first()
        val exportProto = currentProto.copy(background_image_path = "")
        return ScheduleGridStyleProto.ADAPTER.encode(exportProto)
    }

    /**
     * 将清洗/升级完毕后的原生字节数组还原 (缝合本地壁纸并写入)
     */
    suspend fun restoreRawStyleBytes(bytes: ByteArray): Result<Unit> = runCatching {
        val currentLocalProto = dataStore.data.first()
        val localWallpaperPath = currentLocalProto.background_image_path

        val backupProto = ScheduleGridStyleProto.ADAPTER.decode(bytes)
        val finalProto = backupProto.copy(background_image_path = localWallpaperPath)

        dataStore.updateData { finalProto }
        com.xingheyuzhuan.shiguangschedule.widget.updateAllWidgets(context)
    }

    // --- 通用写入 API (Wire 风格：copy 生成新实例) ---
    private suspend fun updateStyle(
        transform: (ScheduleGridStyleProto) -> ScheduleGridStyleProto
    ) {
        dataStore.updateData { currentProto ->
            transform(currentProto)
        }
    }

    // --- 原子化公共写入 API (Setters, Wire copy 风格) ---

    /** 设置时间列宽度 (DP 值) */
    suspend fun setTimeColumnWidth(widthDp: Float) = updateStyle { it.copy(time_column_width_dp = widthDp) }
    /** 设置日表头高度 (DP 值) */
    suspend fun setDayHeaderHeight(heightDp: Float) = updateStyle { it.copy(day_header_height_dp = heightDp) }
    /** 设置节次高度 (DP 值) */
    suspend fun setSectionHeight(heightDp: Float) = updateStyle { it.copy(section_height_dp = heightDp) }

    /** 设置圆角半径 (DP 值) */
    suspend fun setCourseBlockCornerRadius(radiusDp: Float) = updateStyle { it.copy(course_block_corner_radius_dp = radiusDp) }
    /** 设置外部边距 (DP 值) */
    suspend fun setCourseBlockOuterPadding(paddingDp: Float) = updateStyle { it.copy(course_block_outer_padding_dp = paddingDp) }
    /** 设置内部填充 (DP 值) */
    suspend fun setCourseBlockInnerPadding(paddingDp: Float) = updateStyle { it.copy(course_block_inner_padding_dp = paddingDp) }
    /** 设置透明度 (0.0f - 1.0f) */
    suspend fun setCourseBlockAlpha(alpha: Float) = updateStyle { it.copy(course_block_alpha_float = alpha) }
    /** 设置毛玻璃预设 (0..3) */
    suspend fun setGlassPreset(preset: Int) = updateStyle { it.copy(glass_preset = preset.coerceIn(0, 3)) }
    /** 设置背景遮罩透明度 (0.0..0.8) */
    suspend fun setBackgroundDimAlpha(alpha: Float) = updateStyle { it.copy(background_dim_alpha = alpha.coerceIn(0f, 0.8f)) }
    /** 设置背景缩放 (1.0..3.0) */
    suspend fun setBackgroundScale(scale: Float) = updateStyle { it.copy(background_scale = scale.coerceIn(0.8f, 5f)) }
    /** 设置背景水平偏移比例 (-0.5..0.5) */
    suspend fun setBackgroundOffsetX(offset: Float) = updateStyle { it.copy(background_offset_x = offset.coerceIn(-0.5f, 0.5f)) }
    /** 设置背景垂直偏移比例 (-0.5..0.5) */
    suspend fun setBackgroundOffsetY(offset: Float) = updateStyle { it.copy(background_offset_y = offset.coerceIn(-0.5f, 0.5f)) }
    /** 设置课程块毛玻璃模糊半径 (DP, 0 = 关闭) */
    suspend fun setCourseBlockBlurRadius(radiusDp: Float) = updateStyle { it.copy(course_block_blur_radius_dp = radiusDp.coerceIn(0f, 30f)) }
    /** 设置课程块无色玻璃（放弃课程颜色，使用中性玻璃色） */
    suspend fun setCourseBlockColorless(colorless: Boolean) = updateStyle { it.copy(course_block_colorless = colorless) }

    /** 原子化设置背景缩放与偏移，避免连续写入导致卡顿 */
    suspend fun setBackgroundTransform(scale: Float, offsetX: Float, offsetY: Float) = updateStyle {
        it.copy(
            background_scale = scale.coerceIn(0.8f, 5f),
            background_offset_x = offsetX.coerceIn(-0.5f, 0.5f),
            background_offset_y = offsetY.coerceIn(-0.5f, 0.5f)
        )
    }

    // ── 上游同步字段 setter ──

    /** 设置页面文字颜色 (null = 跟随主题) */
    suspend fun setPageTextColor(color: Long?) = updateStyle {
        it.copy(page_text_color_long = color)
    }

    /** 设置课程块文字颜色 (null = 跟随主题) */
    suspend fun setCourseTextColor(color: Long?) = updateStyle {
        it.copy(course_text_color_long = color)
    }

    /** 设置课程块文字水平居中 */
    suspend fun setTextAlignCenterHorizontal(enabled: Boolean) = updateStyle {
        it.copy(text_align_center_horizontal = enabled)
    }

    /** 设置课程块文字垂直居中 */
    suspend fun setTextAlignCenterVertical(enabled: Boolean) = updateStyle {
        it.copy(text_align_center_vertical = enabled)
    }

    /** 设置课程块边框类型 */
    suspend fun setBorderType(type: BorderTypeProto) = updateStyle {
        it.copy(border_type = type)
    }

    /** 设置课表时间段模式 */
    suspend fun setScheduleMode(mode: ScheduleModeProto) = updateStyle {
        it.copy(schedule_mode = mode)
    }

    /** 设置颜色列表映射 */
    suspend fun setCourseColorMaps(maps: List<DualColor>) {
        updateStyle {
            it.copy(course_color_maps = maps.map { map -> map.toProto() })
        }
        com.xingheyuzhuan.shiguangschedule.widget.updateAllWidgets(context)
    }

    /** 重置为默认样式 */
    suspend fun resetAllStyleSettings() {
        dataStore.updateData {
            ScheduleGridStyleProto()
        }
        com.xingheyuzhuan.shiguangschedule.widget.updateAllWidgets(context)
    }

    /** * 设置是否隐藏左侧时间列的具体时间
     * @param hide true 表示隐藏，false 表示显示 (默认)
     */
    suspend fun setHideSectionTime(hide: Boolean) = updateStyle {
        it.copy(hide_section_time = hide)
    }

    /** * 设置是否隐藏星期栏下的日期
     * @param hide true 表示隐藏，false 表示显示 (默认)
     */
    suspend fun setHideDateUnderDay(hide: Boolean) = updateStyle {
        it.copy(hide_date_under_day = hide)
    }

    /**
     * 设置是否隐藏网格线
     * @param hide true 表示隐藏，false 表示显示 (默认)
     */
    suspend fun setHideGridLines(hide: Boolean) = updateStyle {
        it.copy(hide_grid_lines = hide)
    }

    /** * 设置是否在课程格内显示开始时间
     * @param show true 表示显示，false 表示不显示 (默认)
     */
    suspend fun setShowStartTime(show: Boolean) = updateStyle {
        it.copy(show_start_time = show)
    }

    /** * 设置课程块字体的缩放比例
     * @param scale 缩放因子，例如 1.0 为原始大小
     */
    suspend fun setCourseBlockFontScale(scale: Float) = updateStyle {
        it.copy(course_block_font_scale = scale)
    }

    suspend fun setCourseFontFamilyPreset(preset: Int) = updateStyle {
        it.copy(course_font_family_preset = preset.coerceIn(0, 3))
    }

    /**
     * 设置是否隐藏上课地点
     * @param hide true 表示隐藏，false 表示显示 (默认)
     */
    suspend fun setHideLocation(hide: Boolean) = updateStyle {
        it.copy(hide_location = hide)
    }

    /**
     * 设置是否隐藏授课老师
     * @param hide true 表示隐藏，false 表示显示 (默认)
     */
    suspend fun setHideTeacher(hide: Boolean) = updateStyle {
        it.copy(hide_teacher = hide)
    }

    /**
     * 设置是否移除地点前的 @ 符号
     * @param remove true 表示移除，false 表示保留 (默认)
     */
    suspend fun setRemoveLocationAt(remove: Boolean) = updateStyle {
        it.copy(remove_location_at = remove)
    }

    /** * 设置背景壁纸的物理路径
     */
    suspend fun setBackgroundImagePath(path: String) = updateStyle {
        it.copy(background_image_path = path)
    }

    /**
     * 核心修改：重置为默认样式（但保留壁纸）
     * 如果你希望“重置样式”不影响壁纸，需要手动备份路径。
     */
    suspend fun resetAllStyleSettingsExceptWallpaper() {
        dataStore.updateData { currentProto ->
            // 1. 先把当前的壁纸路径备份下来
            val currentPath = currentProto.background_image_path

            // 2. 创建一个全默认对象，再 copy 之前的路径进去
            ScheduleGridStyleProto().copy(background_image_path = currentPath)
        }
        com.xingheyuzhuan.shiguangschedule.widget.updateAllWidgets(context)
    }
}
