package com.xingheyuzhuan.classflow.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.xingheyuzhuan.classflow.data.repository.AppSettingsRepository
import kotlinx.coroutines.flow.first
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.http.GET
import java.io.IOException

/**
 * 对应于 API 返回的外部 JSON 结构。
 */
@Serializable
data class ApiResponse(
    @SerialName("holiday")
    val holidays: Map<String, HolidayInfo>
)

/**
 * 表示 JSON 中每个日期的假期信息。
 */
@Serializable // 必须添加
data class HolidayInfo(
    @SerialName("date")
    val date: String,
    @SerialName("holiday")
    val isHoliday: Boolean
)

/**
 * Retrofit 的 API 接口。
 */
interface SkippedDatesApiService {
    @GET("year")
    suspend fun getHolidays(): ApiResponse
}

/**
 * 单例对象，包含所有与 API 相关的逻辑。
 */
object ApiDateImporter {
    private const val BASE_URL = "https://timor.tech/api/holiday/"

    // 3. 配置 Json 引擎
    private val jsonConfig = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private fun createOkHttpClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val originalRequest = chain.request()
                val newRequest = originalRequest.newBuilder()
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 10; SM-G973F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.120 Mobile Safari/537.36"
                    )
                    .build()
                chain.proceed(newRequest)
            }
        builder.addDebugInterceptor()

        return builder.build()
    }

    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .addConverterFactory(jsonConfig.asConverterFactory("application/json".toMediaType()))
        .client(createOkHttpClient())
        .build()

    private val apiService: SkippedDatesApiService =
        retrofit.create(SkippedDatesApiService::class.java)

    /**
     * 从 API 获取跳过的日期（假期），并保存到 AppSettingsRepository 中。
     */
    suspend fun importAndSaveSkippedDates(appSettingsRepository: AppSettingsRepository) {
        try {
            val response = apiService.getHolidays()

            val skippedDates = response.holidays.values
                .filter { it.isHoliday }
                .map { it.date }
                .toSet()

            val currentSettings = appSettingsRepository.getAppSettings().first()
            val updatedSettings = currentSettings.copy(skippedDates = skippedDates)
            appSettingsRepository.insertOrUpdateAppSettings(updatedSettings)

            println("成功导入并保存了 ${skippedDates.size} 个跳过的日期。")
        } catch (e: IOException) {
            println("网络请求失败：${e.message}")
            e.printStackTrace()
        } catch (e: Exception) {
            println("导入或解析跳过的日期时出错：${e.message}")
            e.printStackTrace()
        }
    }
}
