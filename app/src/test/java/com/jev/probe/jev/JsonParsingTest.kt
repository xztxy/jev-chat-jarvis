package com.jev.probe.jev

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonParsingTest {

    @Test
    fun parsesPlainScores() {
        assertEquals(listOf(0.2, 0.9, 0.5), Json.optScores("{\"scores\":[0.2,0.9,0.5]}"))
    }

    @Test
    fun parsesFencedAndProseWrappedScores() {
        val content = "好的，我的评分如下：\n```json\n{\"scores\":[0.7,0.3,0.6]}\n```\n希望有帮助。"
        assertEquals(listOf(0.7, 0.3, 0.6), Json.optScores(content))
    }

    @Test
    fun parsesNamedScores() {
        assertEquals(listOf(0.4, 0.8, 0.1), Json.optScores("{\"reply_a\":0.4,\"reply_b\":0.8,\"reply_c\":0.1}"))
    }

    @Test
    fun extractsObjectFromFence() {
        val text = Json.objectText("说明\n```json\n{\"true_intent\":\"casual_chat\"}\n```")
        assertTrue(text.startsWith("{"))
        assertTrue(text.endsWith("}"))
    }

    @Test
    fun returnsEmptyWhenNoScoresPresent() {
        assertTrue(Json.optScores("模型没有输出分数").isEmpty())
    }
}
