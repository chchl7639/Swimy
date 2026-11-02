package com.swimy.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var usageStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
        }
        val info = TextView(this).apply {
            text = "스위미를 화면 위에 띄워요.\n처음엔 '다른 앱 위에 표시' 권한이 필요해요."
            textSize = 16f
            gravity = Gravity.CENTER
        }
        val start = Button(this).apply {
            text = "스위미 불러오기"
            setOnClickListener { startSwimy() }
        }
        val stop = Button(this).apply {
            text = "스위미 숨기기"
            setOnClickListener { stopService(Intent(this@MainActivity, OverlayService::class.java)) }
        }

        // ───── 혼잣말 / 앱 반응 ─────
        val cbIdle = CheckBox(this).apply {
            text = "혼자서 가끔 말 걸기·혼잣말"
            isChecked = ChatClient.flag(this@MainActivity, "idle_on")
            setOnCheckedChangeListener { _, c -> ChatClient.setFlag(this@MainActivity, "idle_on", c) }
        }
        val cbReact = CheckBox(this).apply {
            text = "열려 있는 앱에 반응하기"
            isChecked = ChatClient.flag(this@MainActivity, "react_on")
            setOnCheckedChangeListener { _, c -> ChatClient.setFlag(this@MainActivity, "react_on", c) }
        }
        val usageBtn = Button(this).apply {
            text = "앱 반응 권한 열기 (사용 정보 접근)"
            setOnClickListener { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        }
        usageStatus = TextView(this).apply { textSize = 13f }

        // ───── 대화용 AI 선택 + API 키 (이 폰의 앱 전용 저장소에만 저장돼요) ─────
        val aiLabel = TextView(this).apply {
            text = "대화에 쓸 AI"
            textSize = 14f
            setPadding(0, pad, 0, 0)
        }
        val rbGemini = RadioButton(this).apply {
            text = "Gemini (무료 키 사용 가능)"
            id = View.generateViewId()
        }
        val rbClaude = RadioButton(this).apply {
            text = "Claude (유료 API)"
            id = View.generateViewId()
        }
        val group = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            addView(rbGemini)
            addView(rbClaude)
        }
        val keyInput = EditText(this).apply {
            hint = "API 키 붙여넣기"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        val status = TextView(this).apply { textSize = 13f }

        fun current() = if (rbClaude.isChecked) ChatClient.CLAUDE else ChatClient.GEMINI
        fun refresh() {
            status.text = if (ChatClient.apiKey(this, current()).isBlank()) {
                "선택한 AI의 키가 아직 없어요"
            } else {
                "선택한 AI의 키가 저장돼 있어요 ✓"
            }
        }

        if (ChatClient.provider(this) == ChatClient.CLAUDE) rbClaude.isChecked = true else rbGemini.isChecked = true
        refresh()
        group.setOnCheckedChangeListener { _, _ ->
            ChatClient.saveProvider(this, current())
            refresh()
        }

        val save = Button(this).apply {
            text = "키 저장"
            setOnClickListener {
                val k = keyInput.text.toString().trim()
                if (k.isNotEmpty()) {
                    ChatClient.saveKey(this@MainActivity, current(), k)
                    keyInput.setText("")
                    refresh()
                }
            }
        }
        val shareHint = TextView(this).apply {
            text = "팁: 다른 앱에서 글이나 그림을 '공유 → 스위미'로 보내면 스위미가 읽고 요약·설명해 줘요."
            textSize = 13f
            setPadding(0, pad, 0, 0)
        }

        root.addView(info)
        root.addView(start)
        root.addView(stop)
        root.addView(cbIdle)
        root.addView(cbReact)
        root.addView(usageBtn)
        root.addView(usageStatus)
        root.addView(aiLabel)
        root.addView(group)
        root.addView(keyInput)
        root.addView(save)
        root.addView(status)
        root.addView(shareHint)
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        usageStatus.text = if (Perm.hasUsageAccess(this)) {
            "앱 반응 권한: 켜짐 ✓ (앱 이름만 확인하고, 화면 내용은 읽지 않아요)"
        } else {
            "앱 반응 권한: 꺼짐"
        }
    }

    private fun startSwimy() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        startForegroundService(Intent(this, OverlayService::class.java))
    }
}
