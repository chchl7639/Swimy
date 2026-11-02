package com.swimy.app

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

// 다른 앱에서 '공유 → 스위미'를 누르면, 글/링크/그림을 읽고 요약·설명해서 스위미 말풍선으로 알려줘요.
class ShareActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()
        @Suppress("DEPRECATION")
        val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }

        val app = applicationContext
        val provider = ChatClient.provider(app)
        val key = ChatClient.apiKey(app, provider)

        // 스위미가 안 떠 있으면 띄우기
        if (OverlayService.instance == null && Settings.canDrawOverlays(this)) {
            startForegroundService(Intent(this, OverlayService::class.java))
        }
        if (key.isBlank()) {
            deliver("아직 API 키가 없어! 스위미 앱 첫 화면에서 키를 저장해 줘~")
            finish()
            return
        }
        if (text.isNullOrBlank() && uri == null) {
            finish()
            return
        }

        Thread {
            // 그림은 이 화면이 열려 있는 동안(읽기 권한이 살아 있는 동안) 먼저 읽어 둠
            val img = if (uri != null) imageToBase64(uri) else null
            main.post { finish() }

            // 스위미가 뜰 때까지 잠깐 기다림 (최대 3초)
            var waited = 0
            while (OverlayService.instance == null && waited < 30) {
                Thread.sleep(100)
                waited++
            }
            main.post { OverlayService.instance?.thinkingBubble() }

            val url = text?.let { Regex("https?://\\S+").find(it)?.value }
            val page = if (img == null && url != null) fetchPage(url) else null
            val prompt = buildPrompt(text, url, page, img != null)
            val result = runCatching { ChatClient.askOnce(provider, key, prompt, img) }

            main.post {
                result.onSuccess { out ->
                    deliver(out)
                    remember(text, out)
                }.onFailure { err ->
                    deliver("앗, 못 읽었어… (${err.message})")
                }
            }
        }.start()
    }

    private fun deliver(text: String) {
        val svc = OverlayService.instance
        if (svc != null) {
            svc.replyBubble(text, true)
        } else {
            Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show()
        }
    }

    // 나중에 "방금 그거 더 자세히" 같은 이어지는 질문을 할 수 있도록 대화 기록에 남김
    private fun remember(text: String?, out: String) {
        val brief = text?.take(300)?.takeIf { it.isNotBlank() } ?: "[그림]"
        val h = ChatClient.history
        if (h.isEmpty() || h.last().role == "assistant") {
            h.add(Msg("user", "(내가 공유한 것을 요약해 달라고 했어) $brief"))
            h.add(Msg("assistant", out))
            ChatClient.trimHistory()
        }
    }

    private fun buildPrompt(text: String?, url: String?, page: String?, hasImage: Boolean): String {
        val t = text?.take(8000).orEmpty()
        return when {
            hasImage ->
                "공유받은 그림(사진)이야. 무엇이 보이는지 스위미 말투로 3문장 이내로 설명해줘." +
                    (if (t.isNotBlank()) "\n같이 온 글: $t" else "")
            url != null && page != null ->
                "공유받은 링크의 페이지 내용 일부야. 핵심을 스위미 말투로 3문장 이내로 요약해줘.\n[링크] $url\n[내용]\n$page"
            url != null ->
                "링크를 공유받았는데 내용을 읽지 못했어. 읽지 못했다고 솔직하게 말하고, 글을 복사해서 공유해 달라고 부탁해줘. 링크: $url"
            else ->
                "공유받은 글이야. 핵심을 스위미 말투로 3문장 이내로 요약해줘. 아주 짧은 글이면 뜻을 풀어서 설명해줘.\n\n$t"
        }
    }

    // 그림을 줄여서 JPEG base64로
    private fun imageToBase64(uri: Uri): String? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return null
            val bos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, bos)
            bmp.recycle()
            Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    // 링크의 페이지를 받아서 글자만 뽑기 (실패하면 null)
    private fun fetchPage(url: String): String? {
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10000
            c.readTimeout = 15000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) SwimyApp")
            try {
                if (c.responseCode !in 200..299) return null
                val buf = ByteArray(400_000)
                var n = 0
                c.inputStream.use { ins ->
                    while (n < buf.size) {
                        val r = ins.read(buf, n, buf.size - n)
                        if (r < 0) break
                        n += r
                    }
                }
                val html = String(buf, 0, n, Charsets.UTF_8)
                val plain = html
                    .replace(Regex("(?is)<(script|style|noscript)[^>]*>.*?</\\1>"), " ")
                    .replace(Regex("(?s)<[^>]+>"), " ")
                    .replace("&nbsp;", " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                if (plain.length < 200) null else plain.take(6000)
            } finally {
                c.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }
}
