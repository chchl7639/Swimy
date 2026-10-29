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
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

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

        // 대화용 API 키 (이 폰의 앱 전용 저장소에만 저장돼요)
        val keyLabel = TextView(this).apply {
            text = "대화용 API 키 (이 폰에만 저장돼요)"
            textSize = 14f
            setPadding(0, pad, 0, 0)
        }
        val keyInput = EditText(this).apply {
            hint = "API 키 붙여넣기"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        val status = TextView(this).apply {
            text = if (ChatClient.apiKey(this@MainActivity).isBlank()) "아직 저장된 키가 없어요" else "키가 저장돼 있어요 ✓"
            textSize = 13f
        }
        val save = Button(this).apply {
            text = "키 저장"
            setOnClickListener {
                val k = keyInput.text.toString().trim()
                if (k.isNotEmpty()) {
                    ChatClient.saveKey(this@MainActivity, k)
                    keyInput.setText("")
                    status.text = "키가 저장됐어요 ✓"
                }
            }
        }

        root.addView(info)
        root.addView(start)
        root.addView(stop)
        root.addView(keyLabel)
        root.addView(keyInput)
        root.addView(save)
        root.addView(status)
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
