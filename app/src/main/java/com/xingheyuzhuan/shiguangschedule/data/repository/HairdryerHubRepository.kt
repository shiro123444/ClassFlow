package com.xingheyuzhuan.shiguangschedule.data.repository

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.HairdryerCodeFormat
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.HairdryerDetectionMode
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.HairdryerDeviceType
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.HairdryerHistoryEntry
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.HairdryerLaunchSource
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.HairdryerNfcMode
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.HairdryerTypeSource
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.detectionMode
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.UjingHairdryerCode
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.UjingHairdryerEntryResolver
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.UjingHairdryerProbe
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * 吹风机页面的本地存储与业务动作：**判型策略**、**使用记录**、**后台补全设备名**。
 *
 * 只存本机（独立 DataStore 文件 `hairdryer_hub`），刻意不参与 WebDAV 同步：
 * 「我扫过哪几台吹风机、给它们起了什么名字、桌面上放了哪个图标」是这台手机的私事。
 */
@Singleton
class HairdryerHubRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @Named("HairdryerHub") private val dataStore: DataStore<Preferences>,
    private val probe: UjingHairdryerProbe,
) {

    /** 判型设置（设置对话框读写）。 */
    data class Settings(
        /** 扫码判型模式。 */
        val mode: HairdryerDetectionMode = HairdryerDetectionMode.AUTO,
        /** NFC 触碰判型模式。 */
        val nfcMode: HairdryerNfcMode = HairdryerNfcMode.FOLLOW,
        /**
         * 用户对「码的印刷格式」的裁决：true = 判据正确（可信任），false = 不可信（走接口）。
         * 键没有出现 = 从没问过，下次遇到这种格式要弹一次确认。
         */
        val formatVerdicts: Map<HairdryerCodeFormat, Boolean> = emptyMap(),
    ) {
        /** NFC 实际使用的扫码模式（[HairdryerNfcMode.FOLLOW] 时跟随扫码设置）。 */
        val effectiveNfcMode: HairdryerDetectionMode
            get() = when (nfcMode) {
                HairdryerNfcMode.FOLLOW -> mode
                HairdryerNfcMode.AUTO -> HairdryerDetectionMode.AUTO
                HairdryerNfcMode.AUTO_DEVICE -> HairdryerDetectionMode.AUTO_DEVICE
                HairdryerNfcMode.BLUETOOTH -> HairdryerDetectionMode.BLUETOOTH
                HairdryerNfcMode.CLOUD -> HairdryerDetectionMode.CLOUD
            }
    }

    private object Keys {
        val DETECTION_MODE = stringPreferencesKey("detection_mode")
        val NFC_MODE = stringPreferencesKey("nfc_mode")
        val HISTORY = stringPreferencesKey("history")

        fun formatVerdict(format: HairdryerCodeFormat) =
            stringPreferencesKey("format_verdict_${format.name}")
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** 后台补全用的应用级作用域：跳转已经发生，补名字不能挂在页面的 ViewModel 上。 */
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ────────────────────────────── 设置 ──────────────────────────────

    val settings: Flow<Settings> = dataStore.data.map { preferences ->
        Settings(
            mode = HairdryerDetectionMode.fromNameOrNull(preferences[Keys.DETECTION_MODE])
                ?: HairdryerDetectionMode.AUTO,
            nfcMode = HairdryerNfcMode.fromNameOrNull(preferences[Keys.NFC_MODE])
                ?: HairdryerNfcMode.FOLLOW,
            formatVerdicts = HairdryerCodeFormat.entries.mapNotNull { format ->
                when (preferences[Keys.formatVerdict(format)]) {
                    VERDICT_TRUSTED -> format to true
                    VERDICT_REJECTED -> format to false
                    else -> null
                }
            }.toMap()
        )
    }

    suspend fun currentSettings(): Settings = settings.first()

    suspend fun setDetectionMode(mode: HairdryerDetectionMode) {
        dataStore.edit { it[Keys.DETECTION_MODE] = mode.name }
    }

    suspend fun setNfcMode(mode: HairdryerNfcMode) {
        dataStore.edit { it[Keys.NFC_MODE] = mode.name }
    }

    /**
     * 记住用户对某种码格式的裁决。
     *
     * @param correct 用户说「判据正确」；false = 判据不正确（这种格式以后一律走设备信息）。
     */
    suspend fun setFormatVerdict(format: HairdryerCodeFormat, correct: Boolean) {
        if (format == HairdryerCodeFormat.UNKNOWN) return
        dataStore.edit { it[Keys.formatVerdict(format)] = if (correct) VERDICT_TRUSTED else VERDICT_REJECTED }
    }

    /** 忘掉所有格式裁决：下次再遇到这些格式会重新问一遍（设置里的「重新确认」）。 */
    suspend fun resetFormatVerdicts() {
        dataStore.edit { preferences ->
            HairdryerCodeFormat.entries.forEach { preferences.remove(Keys.formatVerdict(it)) }
        }
    }

    /**
     * 这次入口该用哪套判型模式。
     *
     * - 扫码：扫码判型设置；
     * - NFC 触碰：NFC 设置（默认跟随扫码）；
     * - 直达（桌面快捷方式 / 记录里点一下）：**这台机器上次是怎么用的就怎么用** ——
     *   桌面图标本来就代表它，不该临时换一套判型方式。
     */
    suspend fun effectiveMode(
        cd: String,
        source: HairdryerLaunchSource,
        settings: Settings? = null,
    ): HairdryerDetectionMode {
        // 默认参数里不能调挂起函数，所以这里自己兜一下「没传就现读」
        val current = settings ?: currentSettings()
        return when (source) {
            HairdryerLaunchSource.SCAN -> current.mode
            HairdryerLaunchSource.NFC -> current.effectiveNfcMode
            HairdryerLaunchSource.DIRECT ->
                currentHistory().firstOrNull { it.cd == cd }?.type?.detectionMode ?: current.mode
        }
    }

    /**
     * 组装一次分流要用的策略（把设备码的格式、用户裁决、以及本地记录里已证实的类型组合起来）。
     *
     * [HairdryerTypeSource.PROBE] / [HairdryerTypeSource.MANUAL] 的记录才算「已证实」：
     * 格式判据与「探测失败兜底」都不算，否则一条判错的记录会把自己越锁越死。
     */
    suspend fun policyFor(cd: String, mode: HairdryerDetectionMode): UjingHairdryerEntryResolver.Policy {
        val format = UjingHairdryerCode.formatOf(cd)
        val verdict = currentSettings().formatVerdicts[format]
        val known = currentHistory()
            .firstOrNull { it.cd == cd }
            ?.takeIf {
                it.source == HairdryerTypeSource.PROBE || it.source == HairdryerTypeSource.MANUAL
            }
            ?.type
        return UjingHairdryerEntryResolver.Policy(
            mode = mode,
            formatTrusted = verdict,
            formatRejected = verdict == false,
            knownType = known
        )
    }

    // ────────────────────────────── 使用记录 ──────────────────────────────

    /** 使用记录：最近用过的排在前面。 */
    val history: Flow<List<HairdryerHistoryEntry>> = dataStore.data.map { preferences ->
        decodeHistory(preferences[Keys.HISTORY])
    }

    suspend fun currentHistory(): List<HairdryerHistoryEntry> = history.first()

    /**
     * 记一次使用（成功跳转之后调用）。
     *
     * 同一台机器（同 [HairdryerHistoryEntry.cd]）只保留一条记录：更新结论、次数与时间，
     * 用户起过的名字与桌面图标原样保留。
     */
    suspend fun record(
        cd: String,
        raw: String,
        type: HairdryerDeviceType,
        source: HairdryerTypeSource,
        snapshot: UjingHairdryerProbe.Snapshot? = null,
    ) {
        if (cd.isBlank()) return
        val now = System.currentTimeMillis()
        dataStore.edit { preferences ->
            val list = decodeHistory(preferences[Keys.HISTORY])
            val existing = list.firstOrNull { it.cd == cd }
            val merged = (existing ?: HairdryerHistoryEntry(cd = cd)).copy(
                raw = raw.ifBlank { existing?.raw ?: "" },
                // 结论只在「这次确实探测过」时才覆盖，免得手滑一次就把记录带偏
                type = if (snapshot != null || existing == null) type else existing.type,
                source = if (snapshot != null || existing == null) source else existing.source,
                deviceId = snapshot?.deviceId ?: existing?.deviceId,
                subDeviceId = snapshot?.subDeviceId ?: existing?.subDeviceId,
                hubTypeName = snapshot?.hubTypeName ?: existing?.hubTypeName,
                deviceNo = snapshot?.deviceNo ?: existing?.deviceNo,
                storeName = snapshot?.storeName ?: existing?.storeName,
                subjectName = snapshot?.subjectName ?: existing?.subjectName,
                useCount = (existing?.useCount ?: 0) + 1,
                lastUsedAt = now,
            )
            val updated = (list.filterNot { it.cd == cd } + merged)
                .sortedByDescending { it.lastUsedAt }
                .take(MAX_HISTORY)
            preferences[Keys.HISTORY] = json.encodeToString(updated)
        }
    }

    suspend fun rename(cd: String, alias: String?) {
        update(cd) { it.copy(alias = alias?.trim()?.takeIf { value -> value.isNotBlank() }) }
    }

    /** 手动改类型（用户在记录里纠正判型）。 */
    suspend fun setType(cd: String, type: HairdryerDeviceType) {
        update(cd) { it.copy(type = type, source = HairdryerTypeSource.MANUAL) }
    }

    suspend fun setIcon(cd: String, iconId: String?) {
        update(cd) { it.copy(iconId = iconId) }
    }

    suspend fun delete(cd: String) {
        dataStore.edit { preferences ->
            val list = decodeHistory(preferences[Keys.HISTORY]).filterNot { it.cd == cd }
            preferences[Keys.HISTORY] = json.encodeToString(list)
        }
    }

    /**
     * 后台补全一条记录里的设备名（店铺 / 服务主体 / 控制盒）。
     *
     * 触发时机：格式判据直接分流时没有探测过（蓝牙快路径），记录里只有设备码，
     * 列表就会只显示一串数字。这里补一次探测，把名字填上；顺带修正被判据判错的类型
     * （只改「格式 / 兜底」来的结论，探测结论与用户手动指定不动）。
     */
    fun enrichAsync(cd: String, raw: String) {
        if (cd.isBlank() || raw.isBlank()) return
        scope.launch { runCatching { enrich(cd, raw) } }
    }

    /** 同 [enrichAsync]，但等结果（页面下拉刷新等需要确定性的地方用）。 */
    suspend fun enrich(cd: String, raw: String): Boolean {
        val existing = currentHistory().firstOrNull { it.cd == cd } ?: return false
        return when (val outcome = probe.probe(raw)) {
            is UjingHairdryerProbe.Outcome.Ok -> {
                val snapshot = outcome.snapshot
                update(cd) { entry ->
                    val fixType = entry.source == HairdryerTypeSource.FORMAT ||
                        entry.source == HairdryerTypeSource.FALLBACK
                    entry.copy(
                        deviceId = snapshot.deviceId,
                        subDeviceId = snapshot.subDeviceId,
                        hubTypeName = snapshot.hubTypeName ?: entry.hubTypeName,
                        deviceNo = snapshot.deviceNo ?: entry.deviceNo,
                        storeName = snapshot.storeName ?: entry.storeName,
                        subjectName = snapshot.subjectName ?: entry.subjectName,
                        type = if (fixType) snapshot.type else entry.type,
                        source = if (fixType) HairdryerTypeSource.PROBE else entry.source,
                    )
                }
                true
            }

            is UjingHairdryerProbe.Outcome.Failed -> {
                Log.d(TAG, "记录 $cd 的名称补全失败：${outcome.reason}")
                existing.storeName != null
            }
        }
    }

    private suspend fun update(cd: String, transform: (HairdryerHistoryEntry) -> HairdryerHistoryEntry) {
        dataStore.edit { preferences ->
            val list = decodeHistory(preferences[Keys.HISTORY])
            val updated = list.map { if (it.cd == cd) transform(it) else it }
            preferences[Keys.HISTORY] = json.encodeToString(updated)
        }
    }

    private fun decodeHistory(raw: String?): List<HairdryerHistoryEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<HairdryerHistoryEntry>>(raw) }
            .getOrElse {
                Log.w(TAG, "吹风机记录解析失败，按空处理", it)
                emptyList()
            }
    }

    companion object {
        private const val TAG = "HairdryerHubRepository"

        /** 记录上限：够用又不至于把 DataStore 写胖。 */
        private const val MAX_HISTORY = 60

        private const val VERDICT_TRUSTED = "TRUST"
        private const val VERDICT_REJECTED = "REJECT"
    }
}
