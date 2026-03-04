// DebugOkHttpExt.kt (位于 app/src/debug/java/...)

package com.xingheyuzhuan.classflow.data

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor // 在 Debug 专用文件中导入

/**
 * Debug Source Set 的实现：添加 HttpLoggingInterceptor。
 */
fun OkHttpClient.Builder.addDebugInterceptor(): OkHttpClient.Builder {
    val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }
    return this.addInterceptor(loggingInterceptor)
}
