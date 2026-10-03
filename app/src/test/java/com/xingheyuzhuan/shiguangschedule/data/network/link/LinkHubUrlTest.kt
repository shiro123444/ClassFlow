package com.xingheyuzhuan.shiguangschedule.data.network.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkHubUrlTest {

    private val host = LinkHubUrl.host

    @Test
    fun parsesServerCodeNode() {
        val node = LinkHubUrl.parse("https://$host/url/ab12cd")
        assertEquals("ab12cd", node?.code)
        assertNull(node?.inline)
    }

    @Test
    fun parsesShortAliasNode() {
        val node = LinkHubUrl.parse("https://$host/u/ab12cd")
        assertEquals("ab12cd", node?.code)
        assertNull(node?.inline)
    }

    @Test
    fun parsesInlinePayloadWithCode() {
        val node = LinkHubUrl.parse("https://$host/url/ab12cd#ERERER")
        assertEquals("ab12cd", node?.code)
        assertEquals("ERERER", node?.inline)
    }

    @Test
    fun parsesInlinePayloadWithoutCode() {
        val node = LinkHubUrl.parse("https://$host/u/#ERERER")
        assertNull(node?.code)
        assertEquals("ERERER", node?.inline)
    }

    @Test
    fun parsesInlinePayloadWithDashesAndUnderscores() {
        val node = LinkHubUrl.parse("https://$host/url/#a-b_c")
        assertEquals("a-b_c", node?.inline)
    }

    @Test
    fun pathIsCaseSensitiveForAlias() {
        // 路径大小写敏感：/U/ 不是节点路径
        assertNull(LinkHubUrl.parse("https://$host/U/ab12cd"))
    }

    @Test
    fun acceptsUppercaseHostAndScheme() {
        val node = LinkHubUrl.parse("HTTPS://${host.uppercase()}/url/ab12cd")
        assertEquals("ab12cd", node?.code)
    }

    @Test
    fun acceptsQueryButIgnoresIt() {
        val node = LinkHubUrl.parse("https://$host/url/ab12cd?from=qr")
        assertEquals("ab12cd", node?.code)
    }

    @Test
    fun rejectsOtherHost() {
        assertNull(LinkHubUrl.parse("https://example.com/url/ab12cd"))
    }

    @Test
    fun rejectsHttpScheme() {
        assertNull(LinkHubUrl.parse("http://$host/url/ab12cd"))
    }

    @Test
    fun rejectsNonDefaultPort() {
        assertNull(LinkHubUrl.parse("https://$host:8443/url/ab12cd"))
    }

    @Test
    fun rejectsUserInfo() {
        assertNull(LinkHubUrl.parse("https://user@$host/url/ab12cd"))
    }

    @Test
    fun rejectsDeeperPath() {
        assertNull(LinkHubUrl.parse("https://$host/url/ab12cd/extra"))
    }

    @Test
    fun rejectsIllegalCode() {
        assertNull(LinkHubUrl.parse("https://$host/url/ab%2Fcd"))
        assertNull(LinkHubUrl.parse("https://$host/url/${"a".repeat(65)}"))
    }

    @Test
    fun rejectsKeyValueFragment() {
        // `#k=v` 形式为将来扩展预留，本版本不识别
        assertNull(LinkHubUrl.parse("https://$host/url/ab12cd#i=1.ERERER"))
    }

    @Test
    fun rejectsBarePathWithoutPayload() {
        assertNull(LinkHubUrl.parse("https://$host/url"))
        assertNull(LinkHubUrl.parse("https://$host/url/"))
    }

    @Test
    fun rejectsPathOutsideNodeNamespace() {
        assertNull(LinkHubUrl.parse("https://$host/w/0011202004140940"))
        assertNull(LinkHubUrl.parse("https://$host/urls/ab12cd"))
    }

    @Test
    fun rejectsTooLongInlinePayload() {
        val payload = "A".repeat(4096)
        assertNull(LinkHubUrl.parse("https://$host/u/#$payload"))
        // 带合法短码时，非法内嵌载荷被丢弃、节点本身仍成立
        val node = LinkHubUrl.parse("https://$host/url/ab12cd#$payload")
        assertEquals("ab12cd", node?.code)
        assertNull(node?.inline)
    }

    @Test
    fun buildUrlsRoundTripThroughParser() {
        val codeNode = LinkHubUrl.parse(LinkHubUrl.buildCodeUrl("xy12"))
        assertEquals("xy12", codeNode?.code)

        val aliasNode = LinkHubUrl.parse(LinkHubUrl.buildCodeUrl("xy12", shortAlias = true))
        assertEquals("xy12", aliasNode?.code)

        val inlineNode = LinkHubUrl.parse(LinkHubUrl.buildInlineUrl(payload = "ERERER", code = "xy12"))
        assertEquals("xy12", inlineNode?.code)
        assertEquals("ERERER", inlineNode?.inline)

        val shortInline = LinkHubUrl.parse(LinkHubUrl.buildInlineUrl(payload = "ERERER", shortAlias = true))
        assertNull(shortInline?.code)
        assertEquals("ERERER", shortInline?.inline)
        assertTrue(LinkHubUrl.buildCodeUrl("xy12", shortAlias = true).contains("/u/"))
    }
}
