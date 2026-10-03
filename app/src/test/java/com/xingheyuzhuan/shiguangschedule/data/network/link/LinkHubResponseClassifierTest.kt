package com.xingheyuzhuan.shiguangschedule.data.network.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 服务端响应分类（线格式 A）单测：纯函数，不联网。
 *
 * 契约见 `LINK_HUB_PROTOCOL.md` 第 3 节。
 */
class LinkHubResponseClassifierTest {

    private val url = "https://hub.example.com/url/ab12cd"

    private fun classify(status: Int, contentType: String?, body: String) =
        LinkHubResponseClassifier.classify(status, contentType, body, url)

    @Test
    fun parsesJsonEnvelope() {
        val result = classify(
            200,
            "application/json; charset=utf-8",
            """{"v":1,"type":"open","title":"示例","payload":{"url":"https://example.com/a"}}"""
        )
        assertTrue("期望 Ok，实际 $result", result is LinkHubFetchResult.Ok)
        val envelope = (result as LinkHubFetchResult.Ok).envelope
        assertEquals("open", envelope.type)
        assertEquals("示例", envelope.title)
    }

    @Test
    fun parsesJsonEnvelopeWithUnknownFields() {
        val result = classify(
            200,
            "application/json",
            """{"v":1,"type":"text","author":"someone","payload":{"text":"hi"}}"""
        )
        assertTrue(result is LinkHubFetchResult.Ok)
        assertEquals("text", (result as LinkHubFetchResult.Ok).envelope.type)
    }

    @Test
    fun acceptsContentTypeWithCaseAndExtraParams() {
        val body = """{"v":1,"type":"text","payload":{"text":"hi"}}"""
        assertTrue(classify(200, "APPLICATION/JSON; charset=UTF-8", body) is LinkHubFetchResult.Ok)
        assertTrue(classify(200, "application/problem+json", body) is LinkHubFetchResult.Ok)
    }

    @Test
    fun reportsUnsupportedProtocolVersion() {
        val result = classify(200, "application/json", """{"v":2,"type":"open","payload":{"url":"https://x/a"}}""")
        assertEquals(LinkHubFetchResult.UnsupportedVersion, result)
    }

    @Test
    fun reportsMalformedJson() {
        assertTrue(classify(200, "application/json", "{not json") is LinkHubFetchResult.Malformed)
        // 缺 type（必填）
        assertTrue(classify(200, "application/json", """{"v":1,"payload":{}}""") is LinkHubFetchResult.Malformed)
        // 空类型名
        assertTrue(classify(200, "application/json", """{"v":1,"type":"  "}""") is LinkHubFetchResult.Malformed)
    }

    @Test
    fun fallsBackToBrowserForHtmlResponse() {
        val result = classify(200, "text/html; charset=utf-8", "<!doctype html><h1>打开 ClassFlow</h1>")
        assertEquals(LinkHubFetchResult.BrowserFallback(url), result)
    }

    @Test
    fun fallsBackToBrowserWhenContentTypeMissing() {
        // 老服务端可能不带 Content-Type：交给 WebView 兜底，而不是判定失败
        assertEquals(LinkHubFetchResult.BrowserFallback(url), classify(200, null, "hello"))
    }

    @Test
    fun mapsNotFoundAndGone() {
        assertEquals(LinkHubFetchResult.NotFound, classify(404, "text/html", "404 Not Found"))
        assertEquals(LinkHubFetchResult.Expired, classify(410, "application/json", """{"error":"revoked"}"""))
    }

    @Test
    fun mapsOtherStatusesToServerError() {
        assertEquals(LinkHubFetchResult.ServerError(500), classify(500, "text/html", "oops"))
        assertEquals(LinkHubFetchResult.ServerError(429), classify(429, "application/json", "{}"))
        // 3xx：客户端不跟随重定向，按失败处理（避免被引到站外）
        assertEquals(LinkHubFetchResult.ServerError(302), classify(302, "text/html", ""))
    }

    @Test
    fun nonJsonErrorBodyStillMapsByStatus() {
        // 404/410 即使响应体是 HTML 也按状态码判定，优先于「HTML 兜底」
        assertEquals(LinkHubFetchResult.NotFound, classify(404, "text/html", "<html>404</html>"))
        assertEquals(LinkHubFetchResult.Expired, classify(410, "text/html", "<html>410</html>"))
    }
}