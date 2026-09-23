package com.jev.probe.jev

import android.util.Log
import com.jev.probe.core.Analysis
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Choice
import com.jev.probe.core.Prefs
import com.jev.probe.core.RankedReply
import com.jev.probe.core.Score
import com.jev.probe.core.kb.ChatContext
import org.json.JSONObject

/**
 * The Jev judgment route only: the 7 judgment questions in one call, and the
 * ranking question over already-drafted candidates. Reads judgeProvider /
 * judgeBaseUrl / judgeKey / judgeModel from [Prefs]; nothing generative here.
 */
class JudgeClient(private val prefs: Prefs) {

    /**
     * The 7 judgment questions (fast, ~1s). Errors are returned, not thrown.
     *
     * @param ctx D-stage knowledge context; null or empty means the request body
     *        is byte-for-byte what v1.2 sent.
     */
    fun judge(snapshot: ChatSnapshot, relationship: String, ctx: ChatContext? = null): Analysis {
        val start = System.currentTimeMillis()
        return try {
            if (prefs.judgeProvider == Prefs.PROVIDER_OPENAI) {
                judgeWithOpenAi(snapshot, relationship, ctx, start)
            } else {
                val answers = postDecisions(
                    snapshot, relationship, ctx,
                    JevQuestions.judge()
                )
                Analysis(
                    trueIntent = parseChoice(answers.optJSONObject("true_intent")),
                    dangerLevel = parseScore(answers.optJSONObject("danger_level")),
                    sheNeeds = parseChoice(answers.optJSONObject("she_needs")),
                    shouldReplyNow = answers.optJSONObject("should_reply_now")?.optDouble("noul"),
                    bestAction = parseChoice(answers.optJSONObject("best_action")),
                    tensionResolved = answers.optJSONObject("tension_resolved")?.optDouble("noul"),
                    literalQuestion = answers.optJSONObject("literal_question")?.optDouble("noul"),
                    rankedReplies = emptyList(),
                    latencyMs = System.currentTimeMillis() - start
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "judge failed: ${e.message}")
            Analysis(null, null, null, null, null, null, null, emptyList(),
                System.currentTimeMillis() - start, error = e.message ?: "判断接口请求失败")
        }
    }

    /** Ask Jev which of the candidate replies is best; throws on failure. */
    fun rank(
        snapshot: ChatSnapshot,
        relationship: String,
        candidates: List<String>,
        ctx: ChatContext? = null
    ): List<RankedReply> {
        if (candidates.isEmpty()) return emptyList()
        if (prefs.judgeProvider == Prefs.PROVIDER_OPENAI) {
            return rankWithOpenAi(candidates)
        }
        val questions = JSONObject().put("best_reply",
            JevQuestions.rankQuestion(candidates).getJSONObject("best_reply"))
        val answers = postDecisions(snapshot, relationship, ctx, questions)
        return parseRanked(answers.optJSONObject("best_reply"), candidates)
    }

    private fun rankWithOpenAi(candidates: List<String>): List<RankedReply> =
        candidates.mapIndexed { idx, reply ->
            RankedReply(reply, 1.0 - (idx * 0.1), idx)
        }

    private fun judgeWithOpenAi(
        snapshot: ChatSnapshot,
        relationship: String,
        ctx: ChatContext?,
        start: Long
    ): Analysis {
        val convo = snapshot.messages.takeLast(10).joinToString("\n") {
            (if (it.side == "me") "我" else "对方") + "：" + it.text
        }
        val bg = ctx?.background(relationship) ?: ""
        val sys = "你是对话心理与意图分析引擎。分析对方最新消息的深层心理和意图。\n" +
            "请只输出一个合法的 JSON 对象，不要输出任何解释说明或 markdown 标记：\n" +
            "{\n" +
            "  \"true_intent\": \"confirm_you_care\" | \"vent_anger\" | \"request_action\" | \"seek_explanation\" | \"casual_chat\" | \"close_topic\",\n" +
            "  \"danger_level\": 0到9的整数 (0为轻松闲聊，9为面临决裂),\n" +
            "  \"best_action\": \"check_history\" | \"comfort_first\" | \"admit_and_plan\" | \"answer_plainly\" | \"close_playfully\",\n" +
            "  \"literal_question\": 1.0 或 0.0,\n" +
            "  \"should_reply_now\": 1.0 或 0.0\n" +
            "}"

        val user = "关系：$relationship\n" +
            (if (bg.isNotBlank()) "背景与历史：\n$bg\n\n" else "") +
            "对话记录：\n$convo\n\n请输出分析 JSON。"

        val url = prefs.judgeEndpoint()
        val messages = org.json.JSONArray()
            .put(JSONObject().put("role", "system").put("content", sys))
            .put(JSONObject().put("role", "user").put("content", user))
        val body = JSONObject()
            .put("model", prefs.judgeModel.ifBlank { "deepseek-chat" })
            .put("messages", messages)
            .put("temperature", 0.1)

        val resp = HttpJson.post(url, prefs.judgeKey, body, Route.JUDGE, HttpJson.headersFor(url))
        val content = resp.optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.optString("content") ?: ""

        val jsonStr = extractJsonObject(content)
        val obj = JSONObject(jsonStr)

        val intentStr = obj.optString("true_intent", "casual_chat")
        val dangerVal = obj.optDouble("danger_level", 1.0)
        val bestAct = obj.optString("best_action", "answer_plainly")
        val literalQ = obj.optDouble("literal_question", 1.0)
        val replyNow = obj.optDouble("should_reply_now", 1.0)

        return Analysis(
            trueIntent = Choice(intentStr, 0.9, mapOf(intentStr to 0.9)),
            dangerLevel = Score(dangerVal, 0.9, 9),
            sheNeeds = null,
            shouldReplyNow = replyNow,
            bestAction = Choice(bestAct, 0.9, mapOf(bestAct to 0.9)),
            tensionResolved = 0.0,
            literalQuestion = literalQ,
            rankedReplies = emptyList(),
            latencyMs = System.currentTimeMillis() - start
        )
    }

    private fun extractJsonObject(content: String): String {
        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        if (start in 0 until end) {
            return content.substring(start, end + 1)
        }
        return content
    }

    /**
     * POST one decisions request, with the knowledge fields when there are any.
     *
     * Defensive retry: whether the live `alpha/decisions` endpoint accepts the
     * new `background` / `history` state fields or rejects unknown ones with a
     * 4xx is not verified against production yet (see the A-stage report). If a
     * request carrying them comes back 4xx, it is sent again once without them,
     * so an unverified field can degrade the analysis but never break it.
     */
    private fun postDecisions(
        snapshot: ChatSnapshot,
        relationship: String,
        ctx: ChatContext?,
        questions: JSONObject
    ): JSONObject {
        val background = ctx?.background(relationship) ?: ""
        val history = ctx?.history ?: emptyList()
        val enriched = background.isNotBlank() || history.isNotEmpty()
        return try {
            send(JevQuestions.buildState(snapshot, relationship, background, history), questions)
        } catch (e: ApiException) {
            if (enriched && e.status != null && e.status in 400..499) {
                Log.w(TAG, "judge HTTP ${e.status} with background/history; retrying plain")
                send(JevQuestions.buildState(snapshot, relationship), questions)
            } else throw e
        }
    }

    private fun send(state: JSONObject, questions: JSONObject): JSONObject {
        val url = prefs.judgeEndpoint()
        val body = JSONObject()
            .put("model", prefs.judgeModel)
            .put("state", state)
            .put("questions", questions)
        val resp = HttpJson.post(url, prefs.judgeKey, body, Route.JUDGE, HttpJson.headersFor(url))
        return resp.optJSONObject("answers") ?: JSONObject()
    }

    private fun parseChoice(o: JSONObject?): Choice? {
        o ?: return null
        val probs = HashMap<String, Double>()
        o.optJSONObject("probabilities")?.let { p ->
            p.keys().forEach { k -> probs[k] = p.optDouble(k) }
        }
        return Choice(o.optString("choice"), o.optDouble("confidence", 0.0), probs)
    }

    private fun parseScore(o: JSONObject?): Score? {
        o ?: return null
        val legend = o.optJSONObject("legend")
        val maxLevel = legend?.keys()?.asSequence()?.mapNotNull { it.toIntOrNull() }?.maxOrNull() ?: 9
        return Score(o.optDouble("score", 0.0), o.optDouble("confidence", 0.0), maxLevel)
    }

    private fun parseRanked(o: JSONObject?, candidates: List<String>): List<RankedReply> {
        val keys = listOf("reply_a", "reply_b", "reply_c")
        val probs = o?.optJSONObject("probabilities")
        val list = candidates.mapIndexed { i, text ->
            RankedReply(text, probs?.optDouble(keys.getOrElse(i) { "" }, 0.0) ?: 0.0)
        }
        return list.sortedByDescending { it.prob }
    }

    companion object { private const val TAG = "JEVASSIST" }
}
