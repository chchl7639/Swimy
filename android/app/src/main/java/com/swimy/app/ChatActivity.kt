package com.swimy.app

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast

// 화면 아래쪽에 작게 뜨는 입력줄. 스위미의 답은 스위미 말풍선으로 나와요.
class ChatActivity : Activity() {

    private lateinit var input: EditText
    private lateinit var sendBtn: Button
    private var waiting = false

    private val density by lazy { resources.displayMetrics.density }
    private fun dp(v: Int): Int = (v * density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        )
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)

        input = EditText(this).apply {
            hint = "스위미에게 말하기…"
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) send()
                true
            }
        }
        sendBtn = Button(this).apply {
            text = "보내기"
            setOnClickListener { send() }
        }
        val closeBtn = Button(this).apply {
            text = "닫기"
            setOnClickListener { finish() }
        }

        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(sendBtn)
            addView(closeBtn)
        }
        setContentView(row)
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        window.setGravity(Gravity.BOTTOM)

        OverlayService.instance?.onChatOpened()
    }

    // 다른 앱으로 나가거나 화면이 꺼지면 입력줄은 닫아요
    override fun onStop() {
        super.onStop()
        if (!isFinishing) finish()
    }

    override fun onDestroy() {
        OverlayService.instance?.onChatClosed()
        super.onDestroy()
    }

    // 스위미 말풍선으로 보여주기 (스위미가 없으면 토스트)
    private fun reply(text: String) {
        val svc = OverlayService.instance
        if (svc != null) svc.replyBubble(text) else Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    private fun send() {
        if (waiting) return
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")

        val provider = ChatClient.provider(this)
        val key = ChatClient.apiKey(this, provider)
        if (key.isBlank()) {
            reply("아직 API 키가 없어! 스위미 앱 첫 화면에서 키를 저장해 줘~")
            return
        }

        ChatClient.history.add(Msg("user", text))
        ChatClient.trimHistory()
        waiting = true
        sendBtn.isEnabled = false
        OverlayService.instance?.thinkingBubble()
        val snapshot = ChatClient.history.toList()

        Thread {
            val result = runCatching { ChatClient.ask(provider, key, snapshot) }
            runOnUiThread {
                waiting = false
                sendBtn.isEnabled = true
                result.onSuccess { answer ->
                    ChatClient.history.add(Msg("assistant", answer))
                    ChatClient.trimHistory()
                    reply(answer)
                }.onFailure { err ->
                    // 답을 못 받았으니 방금 보낸 말은 기억에서 뺌 (역할이 번갈아야 해서)
                    if (ChatClient.history.isNotEmpty() && ChatClient.history.last().role == "user") {
                        ChatClient.history.removeAt(ChatClient.history.lastIndex)
                    }
                    reply("앗, 연결이 안 돼… (${err.message})")
                }
            }
        }.start()
    }
}
