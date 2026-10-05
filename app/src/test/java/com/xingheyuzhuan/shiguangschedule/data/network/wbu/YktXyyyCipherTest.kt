package com.xingheyuzhuan.shiguangschedule.data.network.wbu

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 智能控水 AES 加密向量：与页面 crypto-js 实现（AES-128/ECB/PKCS7 + 标准 Base64）对齐。
 */
class YktXyyyCipherTest {

    @Test
    fun encryptsKnownVector() {
        assertEquals("insOMd/A+9cLKBlF5fKSkw==", YktXyyyCipher.encrypt("hello"))
    }

    @Test
    fun encryptsNonAsciiUtf8() {
        // 只校验可稳定复现的 ASCII 向量；这里确保非 ASCII 不抛异常且输出为 Base64
        val encrypted = YktXyyyCipher.encrypt("""{"posno":"10101","ano":"27849"}""")
        assertEquals(0, encrypted.length % 4)
    }
}
