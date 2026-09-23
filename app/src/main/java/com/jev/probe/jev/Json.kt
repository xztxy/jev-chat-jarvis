package com.jev.probe.jev

import org.json.JSONObject

/** Tolerant readers for model output that may be wrapped in prose or fences. */
object Json {

    /** The first JSON object text, tolerating ```json fences and surrounding prose. */
    fun objectText(content: String): String {
        val fenced = Regex("```(?:json)?\\s*(\\{.*?\\})\\s*```", RegexOption.DOT_MATCHES_ALL)
            .find(content)?.groupValues?.get(1)
        if (!fenced.isNullOrBlank()) return fenced
        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        return if (start in 0 until end) content.substring(start, end + 1) else content
    }

    /**
     * Scores out of {"scores":[0.1,0.2,0.3]}. Falls back to a bare array, then to
     * named keys (reply_a/b/c, a/b/c) so a model that answers with an object of
     * per-reply values still ranks instead of failing the whole analysis.
     */
    fun optScores(content: String): List<Double> {
        val body = objectText(content)
        runCatching {
            val array = JSONObject(body).optJSONArray("scores")
            if (array != null && array.length() > 0) {
                return (0 until array.length()).map { array.optDouble(it) }
            }
        }
        runCatching {
            val start = content.indexOf('[')
            val end = content.lastIndexOf(']')
            if (start in 0 until end) {
                val array = org.json.JSONArray(content.substring(start, end + 1))
                if (array.length() > 0) return (0 until array.length()).map { array.optDouble(it) }
            }
        }
        runCatching {
            val obj = JSONObject(body)
            val keys = listOf("reply_a", "reply_b", "reply_c", "a", "b", "c")
            val values = keys.mapNotNull { key -> if (obj.has(key)) obj.optDouble(key) else null }
            if (values.isNotEmpty()) return values
        }
        return emptyList()
    }
}
