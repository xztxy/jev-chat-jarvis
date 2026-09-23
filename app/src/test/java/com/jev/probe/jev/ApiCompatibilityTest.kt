package com.jev.probe.jev

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import org.json.JSONArray
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

class ApiCompatibilityTest {
    @Test fun urls() {
        assertEquals("https://example.com/v1/chat/completions", ApiUrls.chat("https://example.com"))
        assertEquals("https://example.com/chat/completions", ApiUrls.chat("https://example.com/chat/completions"))
        assertEquals("https://example.com/api/v1/chat/completions", ApiUrls.chat("https://example.com/api/v1/"))
        assertEquals("https://example.com/v1/responses", ApiUrls.chat("https://example.com/v1/responses"))
    }
    @Test fun gatewayRoundTrip() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var request = JSONObject()
        server.createContext("/v1/models") { ex ->
            val bytes = "{\"data\":[{\"id\":\"custom-model\"}]}".toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
        }
        server.createContext("/v1/responses") { ex ->
            request = JSONObject(ex.requestBody.bufferedReader().readText())
            val bytes = "{\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"收到\"}]}]}".toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}/v1"
            assertEquals(listOf("custom-model"), HttpJson.fetchModels(base, "test"))
            val body = JSONObject().put("model", "custom-model").put("temperature", 0.1)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "测试")))
            val response = HttpJson.post("$base/responses", "test", body, Route.REPLY)
            assertTrue(request.has("input")); assertFalse(request.has("messages")); assertFalse(request.has("temperature"))
            assertEquals("收到", response.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content"))
        } finally { server.stop(0) }
    }
}
