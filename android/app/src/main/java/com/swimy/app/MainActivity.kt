package com.swimy.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
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
        root.addView(info)
        root.addView(start)
        root.addView(stop)
        setContentView(root)

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
