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
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

class OverlayService : Service() {

    // ───── 문구 ─────
    private val phrases = listOf("안녕! 나 여기 있어~", "간질간질해, 헤헤", "오늘도 같이 힘내자!")
    // 0.5초 안에 3번 이상 연속 터치했을 때 (떼쓰기)
    private val whinePhrases = listOf("아앙~ 그만해~ 어지럽단 말이야!", "으아앙~ 어지러워어~ 그만~!", "싫어싫어~ 간지러워서 어지러워!")
    // 집은 채 빠르게 흔들었을 때
    private val tiredPhrases = listOf("으으… 어지러워 😵", "그만~ 멀미 나…", "으아… 힘들어…")
    // 집은 채 한 바퀴 돌렸을 때 (또 해달라고 조르기)
    private val spinPhrases = listOf("우와~ 신난다! 또 해줘, 또또!", "한 번만 더~ 제발~ 응?", "빙글빙글 재밌어! 한 번 더~")
    // 그네 태우듯 약하게 흔들어 줬을 때
    private val swingPhrases = listOf("우와~ 그네 타는 것 같아! 씽씽~ 😆", "높이높이~ 너무 좋아!", "헤헤, 살랑살랑~ 기분 좋아 🥰")
    // 그 이상 돌리거나 너무 많이 흔들었을 때
    private val sickPhrase = "우웩… 🤮"
    // 내려놓았을 때
    private val reliefPhrase = "휴… 살았다"
    private val staggerPhrase = "으으… 세상이 빙글빙글…"
    private val againPhrase = "한 번만 더 돌려줘~ 응? 응?"        // 한 바퀴 돌리고 내려놓았을 때
    private val happyEndPhrase = "재밌었다~ 또 태워줘!"            // 그네 탄 뒤 내려놓았을 때

    // ───── 설정 (여기 값만 바꿔도 느낌이 달라져요) ─────
    private val swimiColor = Color.parseColor("#6C8CFF")
    private val sizeDp = 64
    private val speedDpPerSec = 70f
    private val bubbleMs = 2000L

    private val pickMoveDp = 8f         // 이만큼 움직이면(또는 0.25초 꾹 누르면) 집은 걸로 처리
    private val shadowDropDp = 14f      // 집었을 때 그림자가 아래로 멀어지는 최대 거리
    private val ropeDp = 40f            // 진자 줄 길이
    private val gravityDp = 1800f       // 클수록 묵직하고 덜 흔들려요
    private val dampingPerSec = 1.6f    // 클수록 빨리 잦아들어요
    private val swingGain = 0.9f        // 손 움직임이 흔들림에 반영되는 정도
    private val stretchMax = 0.32f      // 빠르게 움직일 때 늘어나는 최대 비율
    private val shakeDistDp = 500f      // 0.5초 동안 손가락이 움직인 거리가 이 값을 넘으면 '힘들어함'
    private val sickShakeTotalDp = 3500f // 집은 채 흔든 총 거리가 이 값을 넘으면 '토'
    private val spinLightTurns = 1f     // 이 바퀴 수부터 또 해달라고 조름
    private val spinSickTurns = 2.5f    // 이 바퀴 수부터 토
    private val tapWindowMs = 500L      // 마지막 터치 후 이 시간이 지나면 터치 횟수로 반응 결정
    private val swingMinRad = 0.2f      // 그네 감지: 좌우로 이 각도 이상(약 11°)
    private val swingMaxRad = 1.3f      // 그네 감지: 이 각도 이하(약 75°)여야 '약하게'
    private val swingNeed = 4           // 그네 감지: 반대편으로 이 횟수만큼 왕복(편도 기준)

    // ───── 내부 상태 ─────
    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var petRoot: FrameLayout? = null
    private var bubble: TextView? = null
    private lateinit var petLp: WindowManager.LayoutParams
    private lateinit var bubbleLp: WindowManager.LayoutParams
    private lateinit var walkLayer: FrameLayout     // 걷기 / 처짐 / 휘청
    private lateinit var stretchLayer: FrameLayout  // 움직이는 방향으로 늘어남
    private lateinit var sphere: View               // 통통 / 파르르
    private lateinit var shadow: View
    private var walkAnim: ValueAnimator? = null
    private var moveAnim: ValueAnimator? = null
    private var lastPhrase = -1
    private var lastShy = -1
    private var alive = true
    private var screenW = 0
    private var screenH = 0

    // 터치 / 드래그
    private var touching = false
    private var dragging = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var lastMx = 0f
    private var lastMy = 0f
    private var fx = 0f              // 손가락 위치(화면 좌표)
    private var fy = 0f
    private var pendingTaps = 0
    private val shakeLog = mutableListOf<Pair<Long, Float>>()
    private var shakeTotal = 0f
    private var tired = false
    private var dizzyLevel = 0       // 0 없음 / 1 한~두 바퀴 / 2 심함
    private var lastTiredAt = 0L
    private var lastHappyAt = 0L
    private var happy = false
    private var prevOmega = 0f       // 그네 감지용
    private var swingCount = 0
    private var lastTurnSign = 0
    private var lastTurnMs = 0L

    // 진자 물리
    private var px = 0f              // 받침점 위치
    private var py = 0f
    private var pvx = 0f             // 받침점 속도
    private var pvy = 0f
    private var pax = 0f             // 받침점 가속도 (부드럽게 다듬은 값)
    private var pay = 0f
    private var theta = 0f           // 줄 각도 (0 = 바로 아래, 제한 없이 계속 누적)
    private var omega = 0f           // 각속도
    private var holdT = 0f           // 손가락 아래로 자리 잡는 진행도 0..1
    private var o0x = 0f
    private var o0y = 0f
    private var lastFrameNs = 0L

    // 빙글빙글 감지
    private var spinRef = 0f
    private var calmTime = 0f

    // 늘어남 연출
    private var lastBx = 0f
    private var lastBy = 0f
    private var svx = 0f
    private var svy = 0f
    private var stretch = 0f
    private var stretchAngle = 0f

    private val density by lazy { resources.displayMetrics.density }
    private fun dp(v: Float): Float = v * density
    private fun dpi(v: Int): Int = (v * density).toInt()
    private val topRoom: Int get() = dpi(32)     // 튀어오를 때 쓰는 위쪽 여유 공간
    private val bottomRoom: Int get() = dpi(24)  // 그림자가 내려갈 공간
    private val sidePad: Int get() = dpi(16)     // 좌우 여유 (늘어남/휘청 공간)
    private val winW: Int get() = dpi(sizeDp) + sidePad * 2
    private val winH: Int get() = dpi(sizeDp) + topRoom + bottomRoom
    private fun rand(a: Float, b: Float): Float = a + Random.nextFloat() * (b - a)

    private val walkRunnable = Runnable { walkOnce() }
    private val hideBubble = Runnable { bubble?.visibility = View.INVISIBLE }
    private val holdRunnable = Runnable { if (touching && !dragging) beginDrag() }
    private val tapDecideRunnable = Runnable { decideTaps() }
    private val frameCb = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!dragging || !alive) return
            stepPhysics(frameTimeNanos)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

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
        Choreographer.getInstance().removeFrameCallback(frameCb)
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
        clampRange(x, 0f, (screenW - winW).toFloat())

    // 위쪽은 상태바+말풍선 공간, 아래쪽은 내비게이션 바 공간을 비워 둠
    private fun clampY(y: Float): Float =
        clampRange(y, dp(110f), screenH - winH - dp(120f))

    private fun ovalBg(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun createViews() {
        updateScreen()
        val size = dpi(sizeDp)
        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

        // 바닥 그림자
        shadow = View(this).apply { background = ovalBg(Color.parseColor("#33000000")) }
        // 구 (단색 원)
        sphere = View(this).apply {
            background = ovalBg(swimiColor)
            pivotX = size / 2f
            pivotY = size.toFloat()
        }
        // 구 → 늘어남 레이어 → 걷기/처짐 레이어
        stretchLayer = FrameLayout(this).apply {
            addView(sphere, FrameLayout.LayoutParams(size, size))
            pivotX = size / 2f
            pivotY = size / 2f
            clipChildren = false
        }
        walkLayer = FrameLayout(this).apply {
            addView(stretchLayer, FrameLayout.LayoutParams(size, size))
            pivotX = size / 2f
            pivotY = size.toFloat()
            clipChildren = false
        }
        val center = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        val root = FrameLayout(this).apply {
            clipChildren = false
            addView(
                shadow,
                FrameLayout.LayoutParams((size * 0.75f).toInt(), dpi(10), center).apply {
                    bottomMargin = bottomRoom - dpi(5)
                }
            )
            addView(
                walkLayer,
                FrameLayout.LayoutParams(size, size, center).apply { bottomMargin = bottomRoom }
            )
            setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> onTouchDown(e)
                    MotionEvent.ACTION_MOVE -> onTouchMove(e)
                    MotionEvent.ACTION_UP -> onTouchUp()
                    MotionEvent.ACTION_CANCEL -> onTouchCancel()
                }
                true
            }
        }
        petRoot = root

        petLp = WindowManager.LayoutParams(winW, winH, type, flags, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenW - winW) / 2
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
        if (!alive) return
        handler.removeCallbacks(walkRunnable)
        handler.postDelayed(walkRunnable, Random.nextLong(2000L, 5000L))
    }

    private fun walkOnce() {
        if (dragging || touching) {
            scheduleMove()
            return
        }
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

    // ───── 터치 처리 ─────
    private fun onTouchDown(e: MotionEvent) {
        moveAnim?.cancel()                        // 걷던 중이면 멈춤
        handler.removeCallbacks(walkRunnable)     // 만지는 동안은 자동 이동 보류
        fx = e.rawX
        fy = e.rawY
        downRawX = e.rawX
        downRawY = e.rawY
        lastMx = e.rawX
        lastMy = e.rawY
        shakeLog.clear()
        touching = true
        dragging = false
        handler.postDelayed(holdRunnable, 250L)   // 꾹 누르면 집어올림
    }

    private fun onTouchMove(e: MotionEvent) {
        fx = e.rawX
        fy = e.rawY
        if (!dragging && hypot(fx - downRawX, fy - downRawY) > dp(pickMoveDp)) beginDrag()
        if (dragging) trackShake(e)
        lastMx = fx
        lastMy = fy
    }

    private fun onTouchUp() {
        touching = false
        handler.removeCallbacks(holdRunnable)
        if (dragging) endDrag() else onTap()
        scheduleMove()
    }

    private fun onTouchCancel() {
        touching = false
        handler.removeCallbacks(holdRunnable)
        if (dragging) endDrag()
        scheduleMove()
    }

    // ───── 집어올리기 + 진자운동 ─────
    private fun beginDrag() {
        if (dragging) return
        dragging = true
        handler.removeCallbacks(holdRunnable)
        updateScreen()

        // 지금 구의 중심에서 시작해 부드럽게 손가락 아래로 자리 잡도록 초기값 계산
        val rope = dp(ropeDp)
        val cx = petLp.x + winW / 2f
        val cy = petLp.y + topRoom + dpi(sizeDp) / 2f
        o0x = cx - fx
        o0y = cy - fy - rope
        px = cx
        py = cy - rope
        pvx = 0f
        pvy = 0f
        pax = 0f
        pay = 0f
        theta = 0f
        omega = 0f
        holdT = 0f
        spinRef = 0f
        calmTime = 0f
        lastBx = cx
        lastBy = cy
        svx = 0f
        svy = 0f
        stretch = 0f
        shakeTotal = 0f
        dizzyLevel = 0
        tired = false
        happy = false
        prevOmega = 0f
        swingCount = 0
        lastTurnSign = 0
        pendingTaps = 0
        handler.removeCallbacks(tapDecideRunnable)
        lastFrameNs = System.nanoTime()

        // 구는 손가락 아래에 그대로, 그림자만 일정 거리 아래로 멀어짐
        shadow.animate().translationY(dp(shadowDropDp)).alpha(0.5f).scaleX(0.8f)
            .setDuration(150).start()

        Choreographer.getInstance().postFrameCallback(frameCb)
    }

    private fun stepPhysics(nowNs: Long) {
        var dt = (nowNs - lastFrameNs) / 1_000_000_000f
        lastFrameNs = nowNs
        if (dt <= 0f) return
        if (dt > 0.033f) dt = 0.033f

        // 받침점 목표 = 손가락 + 오프셋 (처음엔 구 위치에서 시작해 손가락 아래로 이동)
        val rope = dp(ropeDp)
        holdT = (holdT + dt / 0.15f).coerceAtMost(1f)
        val e = holdT * holdT * (3f - 2f * holdT)
        val tx = fx + o0x * (1f - e)
        val ty = fy + o0y + (-rope - o0y) * e

        // 받침점을 살짝 부드럽게 따라오게 해서 속도/가속도를 얻음
        val follow = 1f - exp(-dt * 24f)
        val nx = px + (tx - px) * follow
        val ny = py + (ty - py) * follow
        val vx = (nx - px) / dt
        val vy = (ny - py) / dt
        val ax = (vx - pvx) / dt
        val ay = (vy - pvy) / dt
        px = nx
        py = ny
        pvx = vx
        pvy = vy
        val maxA = dp(9000f)
        val smooth = 1f - exp(-dt * 20f)
        pax += (ax.coerceIn(-maxA, maxA) - pax) * smooth
        pay += (ay.coerceIn(-maxA, maxA) - pay) * smooth

        // 움직이는 받침점에 매달린 진자: θ'' = -(g·sinθ + gain·(ax·cosθ - ay·sinθ))/L - c·θ'
        // 각도 제한이 없어서 빙글빙글 여러 바퀴도 돌 수 있어요
        val g = dp(gravityDp)
        val h = dt / 2f
        repeat(2) {
            val acc = -(g * sin(theta) + swingGain * (pax * cos(theta) - pay * sin(theta))) / rope -
                dampingPerSec * omega
            omega += acc * h
            theta += omega * h
        }

        // 빙글빙글 감지: 거의 멈춘 시점부터 한 방향으로 돈 각도
        if (abs(omega) < 2f) {
            calmTime += dt
            if (calmTime > 0.4f) spinRef = theta
        } else {
            calmTime = 0f
        }
        val turns = abs(theta - spinRef) / (2f * Math.PI.toFloat())
        if (turns >= spinSickTurns) raiseDizzy(2) else if (turns >= spinLightTurns) raiseDizzy(1)
        detectSwing(SystemClock.uptimeMillis())

        // 구(추)의 위치 → 창 위치
        val bx = px + rope * sin(theta)
        val by = py + rope * cos(theta)
        updateStretch(bx, by, dt)
        petLp.x = (bx - winW / 2f).coerceIn(0f, (screenW - winW).toFloat()).toInt()
        petLp.y = (by - topRoom - dpi(sizeDp) / 2f).coerceIn(0f, (screenH - winH).toFloat()).toInt()
        petRoot?.let { wm.updateViewLayout(it, petLp) }
        placeBubble()
    }

    // 빠르게 움직일수록 움직이는 방향으로 길쭉하게 (부피는 비슷하게)
    private fun updateStretch(bx: Float, by: Float, dt: Float) {
        val vx = (bx - lastBx) / dt
        val vy = (by - lastBy) / dt
        lastBx = bx
        lastBy = by
        val k = 1f - exp(-dt * 18f)
        svx += (vx - svx) * k
        svy += (vy - svy) * k
        val speed = hypot(svx, svy)
        val target = (speed / dp(2200f)).coerceIn(0f, 1f) * stretchMax
        stretch += (target - stretch) * (1f - exp(-dt * 16f))
        if (speed > dp(120f)) stretchAngle = atan2(svy, svx) * (180f / Math.PI.toFloat())
        stretchLayer.rotation = stretchAngle
        stretchLayer.scaleX = 1f + stretch
        stretchLayer.scaleY = 1f - stretch * 0.5f
    }

    private fun endDrag() {
        dragging = false
        Choreographer.getInstance().removeFrameCallback(frameCb)

        // 모양/그림자 원래대로
        stretchLayer.animate().scaleX(1f).scaleY(1f).setDuration(120)
            .withEndAction { stretchLayer.rotation = 0f }.start()
        shadow.animate().translationY(0f).alpha(1f).scaleX(1f).setDuration(120).start()

        val level = dizzyLevel
        val wasTired = tired
        val wasHappy = happy
        tired = false
        dizzyLevel = 0
        happy = false

        if (wasTired || level > 0) {
            // 심했으면 잠깐 처진 채로 있다가 천천히 회복
            val delay = if (level >= 2) 700L else 0L
            ObjectAnimator.ofPropertyValuesHolder(
                walkLayer,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f)
            ).apply {
                startDelay = delay
                duration = 300
            }.start()
        }

        when {
            level >= 2 -> {                       // 많이 돌렸거나 흔들었을 때: 휘청휘청
                say(staggerPhrase)
                stagger()
            }
            level == 1 -> {                       // 한 바퀴쯤: 또 해달라고 조름
                say(againPhrase)
                bounce()
            }
            wasTired -> {                         // 빠르게 흔들었을 때: 휴 살았다
                say(reliefPhrase)
                bounce()
            }
            wasHappy -> {                         // 그네 탄 뒤: 또 태워달라고
                say(happyEndPhrase)
                bounce()
            }
            else -> bounce()                      // 그냥 내려놓음
        }
    }

    // ───── 흔들기 / 빙글빙글 반응 ─────
    private fun trackShake(e: MotionEvent) {
        val now = SystemClock.uptimeMillis()
        val step = hypot(e.rawX - lastMx, e.rawY - lastMy)
        shakeTotal += step
        shakeLog.add(Pair(now, step))
        shakeLog.removeAll { now - it.first > 500L }
        val recent = shakeLog.sumOf { it.second.toDouble() }
        if (shakeTotal >= dp(sickShakeTotalDp)) {
            raiseDizzy(2)
        } else if (recent >= dp(shakeDistDp) && now - lastTiredAt > 2500L) {
            onTired(now)
        }
    }

    private fun onTired(now: Long) {
        lastTiredAt = now
        tired = true
        say(tiredPhrases[Random.nextInt(tiredPhrases.size)])
        droop(false)
        shake()
    }

    private fun raiseDizzy(level: Int) {
        if (level <= dizzyLevel) return
        dizzyLevel = level
        if (level == 1) {
            say(spinPhrases[Random.nextInt(spinPhrases.size)])
        } else {
            say(sickPhrase)
            droop(true)
            shake()
        }
    }

    // 그네 감지: 약하게 좌우로 몇 번 왕복하면 좋아함
    private fun detectSwing(nowMs: Long) {
        if (prevOmega * omega < 0f) {            // 방향이 바뀌는 순간 = 가장 멀리 간 순간
            val twoPi = (2 * Math.PI).toFloat()
            val w = theta - twoPi * Math.round(theta / twoPi).toFloat()  // 여러 바퀴 돈 뒤에도 -π..π로 환산
            val sign = if (w > 0f) 1 else -1
            val ok = abs(w) in swingMinRad..swingMaxRad
            if (ok && sign != lastTurnSign && nowMs - lastTurnMs < 1300L) {
                swingCount++
            } else {
                swingCount = if (ok) 1 else 0
            }
            lastTurnSign = if (ok) sign else 0
            lastTurnMs = nowMs
            if (swingCount >= swingNeed && dizzyLevel == 0 && !tired && nowMs - lastHappyAt > 5000L) {
                swingCount = 0
                onHappySwing(nowMs)
            }
        }
        prevOmega = omega
    }

    private fun onHappySwing(now: Long) {
        lastHappyAt = now
        happy = true
        say(swingPhrases[Random.nextInt(swingPhrases.size)])
        joyHop()
    }

    // 신나서 콩콩
    private fun joyHop() {
        val up = -dp(9f)
        val ty = PropertyValuesHolder.ofKeyframe(
            View.TRANSLATION_Y,
            Keyframe.ofFloat(0f, 0f), Keyframe.ofFloat(0.25f, up), Keyframe.ofFloat(0.5f, 0f),
            Keyframe.ofFloat(0.75f, up), Keyframe.ofFloat(1f, 0f)
        )
        ObjectAnimator.ofPropertyValuesHolder(sphere, ty).apply {
            duration = 700
        }.start()
    }

    // 몸이 납작하게 처짐
    private fun droop(heavy: Boolean) {
        val sx = if (heavy) 1.16f else 1.1f
        val sy = if (heavy) 0.78f else 0.85f
        ObjectAnimator.ofPropertyValuesHolder(
            walkLayer,
            PropertyValuesHolder.ofFloat(View.SCALE_X, sx),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, sy)
        ).apply { duration = 200 }.start()
    }

    // 휘청휘청: 좌우로 점점 작아지며 흔들림
    private fun kf(t: Float, dpValue: Float): Keyframe =
        Keyframe.ofFloat(t, dp(dpValue)).apply { interpolator = AccelerateDecelerateInterpolator() }

    private fun stagger() {
        val tx = PropertyValuesHolder.ofKeyframe(
            View.TRANSLATION_X,
            kf(0f, 0f), kf(0.12f, -12f), kf(0.30f, 10f), kf(0.48f, -8f),
            kf(0.64f, 6f), kf(0.80f, -3f), kf(1f, 0f)
        )
        ObjectAnimator.ofPropertyValuesHolder(walkLayer, tx).apply {
            duration = 1500
        }.start()
    }

    // ───── 짧게 눌렀을 때 ─────
    // 누를 때마다 살짝 통통, 마지막 터치 후 0.5초 동안 더 안 누르면 횟수로 반응 결정
    private fun onTap() {
        pendingTaps++
        bounce()
        handler.removeCallbacks(tapDecideRunnable)
        handler.postDelayed(tapDecideRunnable, tapWindowMs)
    }

    private fun decideTaps() {
        val n = pendingTaps
        pendingTaps = 0
        when {
            n >= 3 -> {                           // 3번 이상: 어지럽다고 떼쓰기
                lastShy = pickIndex(whinePhrases.size, lastShy)
                say(whinePhrases[lastShy])
                shake()
            }
            n == 2 -> openChat()                  // 2번: 대화창 열기
            else -> {                             // 1번: 기본 문구
                lastPhrase = pickIndex(phrases.size, lastPhrase)
                say(phrases[lastPhrase])
            }
        }
    }

    private fun openChat() {
        try {
            startActivity(Intent(this, ChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            say("앗, 대화창이 안 열려… 앱에서 다시 해줘!")
        }
    }

    private fun pickIndex(n: Int, last: Int): Int {
        var i: Int
        do {
            i = Random.nextInt(n)
        } while (n > 1 && i == last)
        return i
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

    // 좌우로 파르르
    private fun shake() {
        val a = dp(6f)
        val b = dp(4f)
        val c = dp(2f)
        val tx = PropertyValuesHolder.ofKeyframe(
            View.TRANSLATION_X,
            Keyframe.ofFloat(0f, 0f), Keyframe.ofFloat(0.15f, -a), Keyframe.ofFloat(0.3f, a),
            Keyframe.ofFloat(0.45f, -a), Keyframe.ofFloat(0.6f, b), Keyframe.ofFloat(0.8f, -c),
            Keyframe.ofFloat(1f, 0f)
        )
        ObjectAnimator.ofPropertyValuesHolder(sphere, tx).apply {
            duration = 450
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
        val cx = petLp.x + winW / 2
        val minX = dpi(8)
        val maxX = maxOf(minX, screenW - w - dpi(8))
        bubbleLp.x = (cx - w / 2).coerceIn(minX, maxX)
        bubbleLp.y = (petLp.y + topRoom - h - dpi(20)).coerceAtLeast(0)
        wm.updateViewLayout(b, bubbleLp)
    }
}
