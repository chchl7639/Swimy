package com.swimy.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class Msg(val role: String, val text: String)

object ChatClient {
    // 대화에 쓸 모델 (바꾸려면 이 줄만 수정)
    const val MODEL = "claude-haiku-5-5"

    // 스위미의 성격 (말투를 바꾸고 싶으면 여기를 수정)
    private const val SYSTEM =
        "너는 '스위미'라는 이름의 작고 귀여운 구(공) 모양 펫 비서야. 사용자의 폰 화면 위를 돌아다니며 살고 있어. " +
        "말투는 다정하고 장난기 있는 반말이고, 한두 문장으로 짧게 답해. " +
        "이모지는 가끔만 써. 모르는 건 아는 척하지 말고 솔직하게 모른다고 말해."

    // 앱이 켜져 있는 동안 대화를 기억해요 (최근 20개)
    val history = mutableListOf<Msg>()

    fun trimHistory() {
        while (history.size > 20) history.removeAt(0)
    }

    fun apiKey(ctx: Context): String =
        ctx.getSharedPreferences("swimy", Context.MODE_PRIVATE).getString("api_key", "") ?: ""

    fun saveKey(ctx: Context, key: String) {
        ctx.getSharedPreferences("swimy", Context.MODE_PRIVATE)
            .edit().putString("api_key", key.trim()).apply()
    }

    // 백그라운드 스레드에서만 호출하세요. 실패하면 예외를 던져요.
    fun ask(key: String, msgs: List<Msg>): String {
        val arr = JSONArray()
        msgs.dropWhile { it.role != "user" }.forEach {
            arr.put(JSONObject().put("role", it.role).put("content", it.text))
        }
        val body = JSONObject()
            .put("model", MODEL)
            .put("max_tokens", 300)
            .put("system", SYSTEM)
            .put("messages", arr)

        val conn = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("x-api-key", key)
            conn.setRequestProperty("anthropic-version", "2023-06-01")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val msg = try {
                    JSONObject(text).optJSONObject("error")?.optString("message") ?: text
                } catch (e: Exception) {
                    text
                }
                throw Exception("오류 $code: $msg")
            }
            val content = JSONObject(text).getJSONArray("content")
            val sb = StringBuilder()
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                if (block.optString("type") == "text") sb.append(block.optString("text"))
            }
            return sb.toString().trim().ifEmpty { "…" }
        } finally {
            conn.disconnect()
        }
    }
}
