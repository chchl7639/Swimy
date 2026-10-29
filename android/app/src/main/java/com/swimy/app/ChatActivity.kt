package com.swimy.app

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class ChatActivity : Activity() {

    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var sendBtn: Button
    private var waiting = false

    private val density by lazy { resources.displayMetrics.density }
    private fun dp(v: Int): Int = (v * density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        // 제목줄
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(
            TextView(this).apply { text = "스위미와 대화"; textSize = 16f },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        header.addView(Button(this).apply {
            text = "닫기"
            setOnClickListener { finish() }
        })
        root.addView(header)

        // 대화 목록
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply { addView(list) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // 입력줄
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
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
        row.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(sendBtn)
        root.addView(row)

        setContentView(root)
        window.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.heightPixels * 0.62f).toInt()
        )
        window.setGravity(Gravity.BOTTOM)

        // 이전 대화 복원 (없으면 인사)
        if (ChatClient.history.isEmpty()) {
            addBubble("assistant", "응? 나 불렀어? 무슨 얘기할까~")
        } else {
            ChatClient.history.forEach { addBubble(it.role, it.text) }
        }
    }

    private fun addBubble(role: String, text: String): TextView {
        val mine = role == "user"
        val tv = TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(if (mine) Color.WHITE else Color.parseColor("#2B2F3A"))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            maxWidth = (resources.displayMetrics.widthPixels * 0.75f).toInt()
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor(if (mine) "#6C8CFF" else "#EEF0F6"))
            }
        }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(6)
            gravity = if (mine) Gravity.END else Gravity.START
        }
        list.addView(tv, lp)
        scrollToBottom()
        return tv
    }

    private fun scrollToBottom() {
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun send() {
        if (waiting) return
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        addBubble("user", text)

        val key = ChatClient.apiKey(this)
        if (key.isBlank()) {
            addBubble("assistant", "아직 API 키가 없어! 스위미 앱 첫 화면에서 키를 저장해 줘~")
            return
        }

        ChatClient.history.add(Msg("user", text))
        ChatClient.trimHistory()
        val typing = addBubble("assistant", "…")
        waiting = true
        sendBtn.isEnabled = false
        val snapshot = ChatClient.history.toList()

        Thread {
            val result = runCatching { ChatClient.ask(key, snapshot) }
            runOnUiThread {
                waiting = false
                sendBtn.isEnabled = true
                result.onSuccess { reply ->
                    typing.text = reply
                    ChatClient.history.add(Msg("assistant", reply))
                    ChatClient.trimHistory()
                }.onFailure { err ->
                    typing.text = "앗, 연결이 안 돼… (${err.message})"
                    // 답을 못 받았으니 방금 보낸 말은 기억에서 뺌 (역할이 번갈아야 해서)
                    if (ChatClient.history.isNotEmpty() && ChatClient.history.last().role == "user") {
                        ChatClient.history.removeAt(ChatClient.history.lastIndex)
                    }
                }
                scrollToBottom()
            }
        }.start()
    }
}
