package com.xingheyuzhuan.shiguangschedule.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.xingheyuzhuan.shiguangschedule.data.model.schedule_style.ScheduleGridStyleProto
import com.xingheyuzhuan.shiguangschedule.data.repository.scheduleGridStyleDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

// 定义 AppSettings Preferences DataStore 委托（公开供 MyApplication 手动构造仓库时共享同一实例）
val Context.appSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")
// 定义通用的 api_config 文件委托
private val Context.apiConfigDataStore: DataStore<Preferences> by preferencesDataStore(name = "api_config")
// 吹风机记录 / 判型策略：独立文件，天然不参与 WebDAV 同步（只存本机）
private val Context.hairdryerHubDataStore: DataStore<Preferences> by preferencesDataStore(name = "hairdryer_hub")

@Module
@InstallIn(SingletonComponent::class)
@Suppress("unused")
object DataStoreModule {

    /**
     * 提供课表网格样式 DataStore (Proto 模式)
     */
    @Provides
    @Singleton
    fun provideScheduleStyleDataStore(@ApplicationContext context: Context): DataStore<ScheduleGridStyleProto> {
        return context.scheduleGridStyleDataStore
    }

    /**
     * 提供全局设置 DataStore（主题/偏好等）
     */
    @Provides
    @Singleton
    @Named("AppSettings")
    fun provideAppSettingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> {
        return context.appSettingsDataStore
    }

    /**
     * 提供通用的 API 配置 DataStore 实例（WebDAV 等）
     */
    @Provides
    @Singleton
    @Named("ApiConfig")
    fun provideApiConfigDataStore(@ApplicationContext context: Context): DataStore<Preferences> {
        return context.apiConfigDataStore
    }

    /**
     * 吹风机页面的本地存储（使用记录 + 判型策略 + 桌面快捷方式图标）。
     *
     * 刻意放在独立文件：这些是「这台手机自己的使用习惯」，不属于要跟 WebDAV 同步的设置。
     */
    @Provides
    @Singleton
    @Named("HairdryerHub")
    fun provideHairdryerHubDataStore(@ApplicationContext context: Context): DataStore<Preferences> {
        return context.hairdryerHubDataStore
    }
}
