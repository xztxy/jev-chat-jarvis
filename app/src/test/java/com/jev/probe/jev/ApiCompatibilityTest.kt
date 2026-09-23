package com.jev.probe.jev

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class ApiCompatibilityTest {

    @Test
    fun urls() {
        assertEquals("https://example.com/v1/chat/completions", ApiUrls.chat("https://example.com"))
        assertEquals("https://example.com/api/v1/chat/completions", ApiUrls.chat("https://example.com/api/v1/"))
        assertEquals("https://example.com/custom/chat/completions", ApiUrls.chat("https://example.com/custom/chat/completions"))
        assertEquals("https://example.com/v1/responses", ApiUrls.chat("https://example.com/v1/responses"))
    }

    @Test
    fun gatewayRoundTrip() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("{\"data\":[{\"id\":\"custom-model\"}]}"))
        server.enqueue(MockResponse().setBody("{\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"收到\"}]}]}"))
        server.start()
        try {
            val base = server.url("/v1").toString().trimEnd('/')
            assertEquals(listOf("custom-model"), HttpJson.fetchModels(base, "test"))

            val body = JSONObject().put("model", "custom-model").put("temperature", 0.1)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "测试")))
            val response = HttpJson.post("$base/responses", "test", body, Route.REPLY)

            server.takeRequest()
            val sent = JSONObject(server.takeRequest().body.readUtf8())
            assertTrue(sent.has("input"))
            assertFalse(sent.has("messages"))
            assertFalse(sent.has("temperature"))
            assertEquals("收到", response.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun emptyTextIsReportedAsFailure() {
        val server = MockWebServer()
            server.enqueue(MockResponse().setBody("{\"choices\":[{\"message\":{\"content\":\"\"}}]}"))
            var attempts = 0
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    attempts++
                    return MockResponse().setBody("{\"choices\":[{\"message\":{\"content\":\"\"}}]}")
                }
            }
        server.start()
        try {
            val body = JSONObject().put("model", "custom-model")
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "测试")))
            try {
                HttpJson.post(server.url("/v1/chat/completions").toString(), "test", body, Route.REPLY)
                fail("empty model text must not look like success")
            } catch (e: ApiException) {
                assertTrue(attempts >= 3)
                assertTrue((e.status == 200) || (e.status == null && e.snippet.contains("未返回文本")))
            }
        } finally {
            server.shutdown()
        }
    }
}
