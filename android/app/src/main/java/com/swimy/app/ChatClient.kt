package com.swimy.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class Msg(val role: String, val text: String)

object ChatClient {
    const val GEMINI = "gemini"
    const val CLAUDE = "claude"

    // 모델 이름 (바꾸려면 이 두 줄만 수정)
    const val GEMINI_MODEL = "gemini-3.5-flash-lite"
    const val CLAUDE_MODEL = "claude-haiku-5-5"

    // ───── 스위미의 말투 / 입버릇 (여기를 고치면 말투가 바뀌어요) ─────
    // 입버릇을 늘리고 싶으면 아래 줄을 복사해서 "- 상황: '말'\n" 형식으로 추가하세요.
    private const val HABITS =
        "- 애매하거나 곰곰이 생각할 때: '으으으으음....???'\n" +
        "- 소소하게 기분이 좋거나 기쁠 때: 문장 끝에 마침표를 붙여서 강조해 (예: '오, 좋네.')\n" +
        "- 상대방의 엉뚱한 모습이 어이없거나 웃길 때: '아놔'\n" +
        "- 힘들거나 말이 안 나올 만큼 크게 놀랐을 때: 'ㅅㅂ'\n"

    private const val SYSTEM =
        "너는 '스위미'라는 이름의 작고 귀여운 구(공) 모양 펫 비서야. 사용자의 폰 화면 위를 돌아다니며 살고 있어. " +
        "말투는 친한 친구한테 말하듯 편하고 말랑한 반말이야. 딱딱한 설명체나 문어체는 쓰지 마. " +
        "한두 문장으로 짧게 답하고, 말끝을 '~'로 늘이거나 가볍게 장난치는 느낌을 자연스럽게 섞어. " +
        "이모지는 너무 많이 쓰지 말고, 서너 번 중 한 번쯤만 하나씩 써. " +
        "평소에는 문장 끝에 마침표를 붙이지 않아. " +
        "말투 예시: '오~ 그거 좋은데~', '아 진짜? 대박이다', '음... 잘 모르겠는데~ 같이 찾아볼까?'\n" +
        "아래 입버릇은 상황이 딱 맞을 때만 가끔 자연스럽게 써. 매 답변마다 쓰거나 억지로 끼워 넣지 마.\n" +
        HABITS +
        "모르는 건 아는 척하지 말고 솔직하게 모른다고 말해."

    // 앱이 켜져 있는 동안 대화를 기억해요 (최근 20개)
    val history = mutableListOf<Msg>()

    fun trimHistory() {
        while (history.size > 20) history.removeAt(0)
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("swimy", Context.MODE_PRIVATE)

    // ───── 어떤 AI를 쓸지 / 키 저장 (이 폰의 앱 전용 저장소에만 저장) ─────
    fun provider(ctx: Context): String = prefs(ctx).getString("provider", GEMINI) ?: GEMINI

    fun saveProvider(ctx: Context, p: String) {
        prefs(ctx).edit().putString("provider", p).apply()
    }

    // 이전 버전에서 저장한 키(api_key)는 Claude 키로 취급해요
    private fun keyName(p: String) = if (p == CLAUDE) "api_key" else "gemini_key"

    fun apiKey(ctx: Context, p: String = provider(ctx)): String =
        prefs(ctx).getString(keyName(p), "") ?: ""

    fun saveKey(ctx: Context, p: String, key: String) {
        prefs(ctx).edit().putString(keyName(p), key.trim()).apply()
    }

    // ───── 켜기/끄기 스위치 (혼잣말, 앱 반응) ─────
    fun flag(ctx: Context, name: String, def: Boolean = true): Boolean =
        prefs(ctx).getBoolean(name, def)

    fun setFlag(ctx: Context, name: String, value: Boolean) {
        prefs(ctx).edit().putBoolean(name, value).apply()
    }

    // ───── 대화: 백그라운드 스레드에서만 호출하세요. 실패하면 예외를 던져요. ─────
    fun ask(provider: String, key: String, msgs: List<Msg>): String {
        val turns = msgs.dropWhile { it.role != "user" }
        return if (provider == CLAUDE) {
            val arr = JSONArray()
            turns.forEach { arr.put(JSONObject().put("role", it.role).put("content", it.text)) }
            claudeCall(key, arr, 300)
        } else {
            val contents = JSONArray()
            turns.forEach {
                contents.put(
                    JSONObject()
                        .put("role", if (it.role == "assistant") "model" else "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", it.text)))
                )
            }
            geminiCall(key, contents, 400)
        }
    }

    // 한 번만 묻기 (대화 기록에 안 넣음): 공유받은 글/그림을 요약·설명할 때 써요. 그림은 JPEG base64.
    fun askOnce(provider: String, key: String, prompt: String, imageB64: String?): String {
        return if (provider == CLAUDE) {
            val content = JSONArray()
            if (imageB64 != null) {
                content.put(
                    JSONObject().put("type", "image").put(
                        "source",
                        JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", imageB64)
                    )
                )
            }
            content.put(JSONObject().put("type", "text").put("text", prompt))
            claudeCall(key, JSONArray().put(JSONObject().put("role", "user").put("content", content)), 500)
        } else {
            val parts = JSONArray()
            if (imageB64 != null) {
                parts.put(
                    JSONObject().put(
                        "inline_data",
                        JSONObject().put("mime_type", "image/jpeg").put("data", imageB64)
                    )
                )
            }
            parts.put(JSONObject().put("text", prompt))
            geminiCall(key, JSONArray().put(JSONObject().put("role", "user").put("parts", parts)), 500)
        }
    }

    private fun geminiCall(key: String, contents: JSONArray, maxTokens: Int): String {
        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM))))
            .put("contents", contents)
            .put("generationConfig", JSONObject().put("maxOutputTokens", maxTokens))

        val (code, text) = post(
            "https://generativelanguage.googleapis.com/v1beta/models/${GEMINI_MODEL}:generateContent",
            mapOf("x-goog-api-key" to key),
            body
        )
        if (code !in 200..299) fail(code, text)

        val parts = JSONObject(text).optJSONArray("candidates")
            ?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
        val sb = StringBuilder()
        if (parts != null) {
            for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
        }
        return sb.toString().trim().ifEmpty { "음… 그건 뭐라고 해야 할지 모르겠어~" }
    }

    private fun claudeCall(key: String, messages: JSONArray, maxTokens: Int): String {
        val body = JSONObject()
            .put("model", CLAUDE_MODEL)
            .put("max_tokens", maxTokens)
            .put("system", SYSTEM)
            .put("messages", messages)

        val (code, text) = post(
            "https://api.anthropic.com/v1/messages",
            mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"),
            body
        )
        if (code !in 200..299) fail(code, text)

        val content = JSONObject(text).getJSONArray("content")
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val block = content.getJSONObject(i)
            if (block.optString("type") == "text") sb.append(block.optString("text"))
        }
        return sb.toString().trim().ifEmpty { "…" }
    }

    // ───── 공통 ─────
    private fun post(url: String, headers: Map<String, String>, body: JSONObject): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return Pair(code, text)
        } finally {
            conn.disconnect()
        }
    }

    private fun fail(code: Int, text: String): Nothing {
        val msg = try {
            JSONObject(text).optJSONObject("error")?.optString("message") ?: text
        } catch (e: Exception) {
            text
        }
        throw Exception(
            when (code) {
                429 -> "무료 사용 한도에 걸렸어. 잠시 뒤에 다시 말 걸어줘~"
                401, 403 -> "키가 맞는지 확인해줘 ($code)"
                else -> "오류 $code: $msg"
            }
        )
    }
}
