package com.swimy.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.Keyframe
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

class OverlayService : Service() {

    // ───── 설정 (여기 값만 바꿔도 느낌이 달라져요) ─────
    private val phrases = listOf("안녕! 나 여기 있어~", "간질간질해, 헤헤", "오늘도 같이 힘내자!")
    private val swimiColor = Color.parseColor("#6C8CFF")
    private val sizeDp = 64
    private val speedDpPerSec = 70f
    private val bubbleMs = 2000L

    // ───── 내부 상태 ─────
    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var petRoot: FrameLayout? = null
    private var bubble: TextView? = null
    private lateinit var petLp: WindowManager.LayoutParams
    private lateinit var bubbleLp: WindowManager.LayoutParams
    private lateinit var walkLayer: FrameLayout
    private lateinit var sphere: View
    private var walkAnim: ValueAnimator? = null
    private var moveAnim: ValueAnimator? = null
    private var lastPhrase = -1
    private var alive = true
    private var screenW = 0
    private var screenH = 0

    private val density by lazy { resources.displayMetrics.density }
    private fun dp(v: Float): Float = v * density
    private fun dpi(v: Int): Int = (v * density).toInt()
    private val topRoom: Int get() = dpi(24) // 튀어오를 때 쓰는 위쪽 여유 공간
    private fun rand(a: Float, b: Float): Float = a + Random.nextFloat() * (b - a)

    private val walkRunnable = Runnable { walkOnce() }
    private val hideBubble = Runnable { bubble?.visibility = View.INVISIBLE }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNow()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        createViews()
        scheduleMove()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNow()
        return START_STICKY
    }

    override fun onDestroy() {
        alive = false
        handler.removeCallbacksAndMessages(null)
        moveAnim?.cancel()
        walkAnim?.cancel()
        petRoot?.let { wm.removeView(it) }
        bubble?.let { wm.removeView(it) }
        petRoot = null
        bubble = null
        super.onDestroy()
    }

    // ───── 알림 (안드로이드가 서비스를 유지하려면 필요) ─────
    private fun startForegroundNow() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("swimy", "스위미", NotificationManager.IMPORTANCE_MIN)
        )
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, "swimy")
            .setContentTitle("스위미가 화면에 있어요")
            .setContentText("눌러서 앱을 열고 숨길 수 있어요")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    // ───── 화면 위 창 만들기 ─────
    @Suppress("DEPRECATION")
    private fun updateScreen() {
        val dm = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        screenW = dm.widthPixels
        screenH = dm.heightPixels
    }

    private fun clampRange(v: Float, lo: Float, hi: Float): Float =
        if (hi <= lo) lo else v.coerceIn(lo, hi)

    private fun clampX(x: Float): Float =
        clampRange(x, dp(8f), screenW - dpi(sizeDp) - dp(8f))

    // 위쪽은 상태바+말풍선 공간, 아래쪽은 내비게이션 바 공간을 비워 둠
    private fun clampY(y: Float): Float =
        clampRange(y, dp(110f), screenH - dpi(sizeDp) - topRoom - dp(120f))

    private fun createViews() {
        updateScreen()
        val size = dpi(sizeDp)
        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

        // 구 (단색 원)
        sphere = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(swimiColor)
            }
            pivotX = size / 2f
            pivotY = size.toFloat()
        }
        // 걷기 애니메이션은 이 레이어, 터치 반응은 구 자체에 적용
        walkLayer = FrameLayout(this).apply {
            addView(sphere, FrameLayout.LayoutParams(size, size))
            pivotX = size / 2f
            pivotY = size / 2f
        }
        val root = FrameLayout(this).apply {
            addView(walkLayer, FrameLayout.LayoutParams(size, size, Gravity.BOTTOM))
            setOnTouchListener { _, e ->
                if (e.action == MotionEvent.ACTION_DOWN) onPetTouched()
                true
            }
        }
        petRoot = root

        petLp = WindowManager.LayoutParams(size, size + topRoom, type, flags, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenW - size) / 2
            y = clampY(screenH / 2f).toInt()
        }
        wm.addView(root, petLp)

        // 말풍선 (터치는 통과시키는 별도 창)
        val b = TextView(this).apply {
            setTextColor(Color.parseColor("#2B2F3A"))
            textSize = 14f
            setPadding(dpi(12), dpi(8), dpi(12), dpi(8))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(12f)
            }
            visibility = View.INVISIBLE
        }
        bubble = b
        bubbleLp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        wm.addView(b, bubbleLp)
    }

    // ───── 랜덤 이동 ─────
    private fun scheduleMove() {
        if (alive) handler.postDelayed(walkRunnable, Random.nextLong(2000L, 5000L))
    }

    private fun walkOnce() {
        updateScreen()
        val x0 = petLp.x.toFloat()
        val y0 = petLp.y.toFloat()
        val angle = rand(0f, (2 * Math.PI).toFloat())   // 무작위 방향
        val dist = dp(rand(60f, 160f))                  // 무작위 거리
        val x1 = clampX(x0 + cos(angle) * dist)
        val y1 = clampY(y0 + sin(angle) * dist)
        val real = hypot(x1 - x0, y1 - y0)
        if (real < dp(20f)) {
            scheduleMove()
            return
        }
        val ms = (real / dp(speedDpPerSec) * 1000f).toLong()

        startWalkAnim()
        moveAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                petLp.x = (x0 + (x1 - x0) * f).toInt()
                petLp.y = (y0 + (y1 - y0) * f).toInt()
                petRoot?.let { v -> wm.updateViewLayout(v, petLp) }
                placeBubble()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    stopWalkAnim()
                    scheduleMove()
                }
            })
            start()
        }
    }

    // 걷기: 살짝 위아래로 튀면서 좌우로 기우뚱
    private fun startWalkAnim() {
        walkAnim?.cancel()
        walkAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 500
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val t = (it.animatedFraction * 2 * Math.PI).toFloat()
                walkLayer.rotation = -4f * cos(t)
                walkLayer.translationY = -dp(5f) * abs(sin(t))
            }
            start()
        }
    }

    private fun stopWalkAnim() {
        walkAnim?.cancel()
        walkAnim = null
        walkLayer.rotation = 0f
        walkLayer.translationY = 0f
    }

    // ───── 터치 반응 ─────
    private fun onPetTouched() {
        var i: Int
        do {
            i = Random.nextInt(phrases.size)
        } while (i == lastPhrase)
        lastPhrase = i
        say(phrases[i])
        bounce()
    }

    private fun bounce() {
        val sx = PropertyValuesHolder.ofKeyframe(
            View.SCALE_X,
            Keyframe.ofFloat(0f, 1f), Keyframe.ofFloat(0.2f, 1.08f), Keyframe.ofFloat(0.5f, 0.96f),
            Keyframe.ofFloat(0.75f, 1.05f), Keyframe.ofFloat(1f, 1f)
        )
        val sy = PropertyValuesHolder.ofKeyframe(
            View.SCALE_Y,
            Keyframe.ofFloat(0f, 1f), Keyframe.ofFloat(0.2f, 0.9f), Keyframe.ofFloat(0.5f, 1.05f),
            Keyframe.ofFloat(0.75f, 0.95f), Keyframe.ofFloat(1f, 1f)
        )
        val ty = PropertyValuesHolder.ofKeyframe(
            View.TRANSLATION_Y,
            Keyframe.ofFloat(0f, 0f), Keyframe.ofFloat(0.2f, 0f), Keyframe.ofFloat(0.5f, -dp(18f)),
            Keyframe.ofFloat(0.75f, 0f), Keyframe.ofFloat(1f, 0f)
        )
        ObjectAnimator.ofPropertyValuesHolder(sphere, sx, sy, ty).apply {
            duration = 550
        }.start()
    }

    // ───── 말풍선 ─────
    private fun say(text: String) {
        val b = bubble ?: return
        updateScreen()
        b.text = text
        b.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        b.visibility = View.VISIBLE
        placeBubble()
        handler.removeCallbacks(hideBubble)
        handler.postDelayed(hideBubble, bubbleMs)
    }

    private fun placeBubble() {
        val b = bubble ?: return
        if (b.visibility != View.VISIBLE) return
        val w = b.measuredWidth
        val h = b.measuredHeight
        val cx = petLp.x + dpi(sizeDp) / 2
        val minX = dpi(8)
        val maxX = maxOf(minX, screenW - w - dpi(8))
        bubbleLp.x = (cx - w / 2).coerceIn(minX, maxX)
        bubbleLp.y = petLp.y + topRoom - h - dpi(14)
        wm.updateViewLayout(b, bubbleLp)
    }
}
