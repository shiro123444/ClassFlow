package com.shiro.classflow.ui.schoolselection.web

/**
 * In-memory one-time credentials for WebView fallback login autofill.
 * Never persisted to disk; expires quickly.
 */
object WbuWebLoginAutofillStore {
    data class Credentials(
        val studentId: String,
        val password: String,
        val createdAtMillis: Long = System.currentTimeMillis()
    )

    private const val EXPIRE_MILLIS = 10 * 60 * 1000L

    @Volatile
    private var cached: Credentials? = null

    @Synchronized
    fun put(studentId: String, password: String) {
        cached = Credentials(studentId = studentId, password = password)
    }

    @Synchronized
    fun getActiveOrNull(): Credentials? {
        val value = cached ?: return null
        if (System.currentTimeMillis() - value.createdAtMillis > EXPIRE_MILLIS) {
            cached = null
            return null
        }
        return value
    }

    @Synchronized
    fun clear() {
        cached = null
    }
}

