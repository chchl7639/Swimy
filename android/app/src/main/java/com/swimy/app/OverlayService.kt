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
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
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
import java.util.Calendar
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
    // 그네를 크게(약 75°~140°) 태웠을 때
    private val swingHighPhrases = listOf("더 높이! 더 높이!", "우와~ 나는 거 같아!", "하늘까지 닿겠다~!")
    // 그네를 아주 크게(약 140°~180°) 태웠을 때
    private val swingScaryPhrases = listOf("약간 무서운데…?", "으아~ 너무 높아! 살살~", "떨어지겠어… 꽉 잡아줘!")
    private val scaryEndPhrase = "아까 진짜 무서웠어… 그래도 재밌었다!"

    // 혼자서 가끔 하는 말 (이모지 없이 부드럽게. 마음대로 추가/삭제하세요)
    private val idlePhrases = listOf(
        "심심하다~", "음... 뭐 재밌는 거 없나~", "누가 그네 안 태워주나~",
        "오늘 뭐 했어~?", "오늘 기분 어때?", "으쌰~ 스트레칭 한번 할까?",
        "데굴데굴...", "너 지금 뭐 해~?", "나 좀 눌러봐~ 간질간질할 거야",
        "자는 건 아니지~?"
    )
    private val morningPhrases = listOf("좋은 아침~ 잘 잤어?", "일어났어~? 오늘도 힘내자!", "아침이다~ 물부터 한 잔 마시자~")
    private val lunchPhrases = listOf("밥 먹었어~? 배고프다~", "점심 뭐 먹을 거야~?", "밥은 챙겨 먹어야지~")
    private val nightPhrases = listOf("아직 안 자~? 늦었는데~", "슬슬 자야 하는 거 아니야~?", "밤이 깊었다~ 눈 좀 쉬게 하자")

    // 답을 기다리는 동안 말풍선에 뜨는 소리 (뒤에 ... 이 점점 늘어나요. 가끔 ? 도 붙어요)
    private val thinkingBases = listOf("음", "으음", "으이이", "흐으음", "으으음", "어디 보자")

    // 열려 있는 앱에 반응 (앱 이름만 확인해요. 화면 내용은 읽지 않아요)
    private val appReactions: Map<String, List<String>> = mapOf(
        "com.google.android.youtube" to listOf("유튜브 보는 거야~? 뭐 보는데~", "나도 같이 볼래~"),
        "com.kakao.talk" to listOf("누구랑 얘기해~?", "카톡 답장 잘 해~"),
        "com.instagram.android" to listOf("인스타 구경 중이네~", "예쁜 거 많이 봤어~?"),
        "com.android.chrome" to listOf("뭐 검색하는 거야~?", "오~ 인터넷 서핑이다~"),
        "com.sec.android.app.sbrowser" to listOf("뭐 검색하는 거야~?", "오~ 인터넷 서핑이다~"),
        "com.netflix.mediaclient" to listOf("넷플릭스다~ 뭐 볼 건데~", "오늘은 어떤 걸 볼까~"),
        "com.nhn.android.search" to listOf("뭐 찾아보는 거야~?", "검색 열심히 하네~"),
        "com.youtube.music" to listOf("노래 듣는 거야~? 신난다~", "어떤 노래야~? 나도 들려줘~"),
        "com.sec.android.app.camera" to listOf("사진 찍는 거야~? 나도 찍어줘~", "치즈~!"),
        "com.sec.android.gallery3d" to listOf("사진 구경 중이야~?", "추억 여행이다~"),
        "com.google.android.apps.maps" to listOf("어디 가는 거야~?", "길 잃으면 안 돼~"),
        "com.android.vending" to listOf("새 앱 구경 중이야~?", "뭐 설치하려고~?"),
        "com.github.android" to listOf("오~ 코딩하는 거야~? 멋있다~", "내가 만들어진 곳이다~"),
        "com.anthropic.claude" to listOf("Claude 만나러 갔네~", "나 말고 다른 AI랑 얘기하는 거야~? 질투난다~"),
        "com.zhiliaoapp.musically" to listOf("틱톡 보는 거야~? 시간 순삭이다~", "그만 보고 눈 좀 쉬자~"),
        "com.twitter.android" to listOf("트윗 구경 중이야~?", "오늘은 무슨 일 있대~?"),
        "com.samsung.android.app.notes" to listOf("메모하는 거야~? 열심히 하네~", "뭐 적는데~?"),
        "com.android.settings" to listOf("뭐 바꾸려고~? 나 지우면 안 돼~", "설정 만지는 거야~?")
    )

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
    private val swingMinRad = 0.2f      // 그네: 좌우로 이 각도 이상(약 11°)이면 반응
    private val swingMaxRad = 1.3f      // 이 각도(약 75°)를 넘으면 '크게 흔든 것'으로 반응
    private val swingScaryRad = 2.4f    // 이 각도(약 140°)를 넘으면 '아주 크게'(무서워함)
    private val swingNeed = 4           // 약한 그네: 반대편으로 이 횟수만큼 왕복(편도 기준). 큰 그네는 2번이면 반응
    private val idleMinSec = 90          // 혼잣말 간격(초) 최소
    private val idleMaxSec = 240         // 혼잣말 간격(초) 최대
    private val appPollMs = 3000L        // 열린 앱을 확인하는 주기
    private val appReactCooldownMs = 120000L // 앱 반응 사이 최소 간격
    private val appReactChance = 55      // 앱이 바뀔 때 반응할 확률(%)

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
    private var swingPeak = 0f       // 이번 왕복에서 가장 크게 간 각도
    private var swingTierShown = 0   // 이번에 집은 동안 보여 준 그네 반응 단계(0~3)
    private var lastPkg: String? = null
    private var firstPoll = true
    private var lastAppReactAt = 0L
    private var thinkingActive = false
    private var thinkDots = 0
    private var thinkBase = ""
    private var thinkQ = false
    private var thinkStartMs = 0L

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
    private val idleRunnable = Runnable { idleTalk() }
    private val appPollRunnable = Runnable { pollApp() }
    private val thinkRunnable = object : Runnable {
        override fun run() {
            if (!thinkingActive || !alive) return
            if (SystemClock.uptimeMillis() - thinkStartMs > 70000L) {
                thinkingActive = false
                return
            }
            thinkDots = thinkDots % 3 + 1
            val q = if (thinkQ && thinkDots == 3) "?" else ""
            say(thinkBase + ".".repeat(thinkDots) + q, 5000L)
            handler.postDelayed(this, 450L)
        }
    }
    private val frameCb = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!dragging || !alive) return
            stepPhysics(frameTimeNanos)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    companion object {
        // 대화창(ChatActivity)이 스위미의 말풍선을 쓰기 위한 연결
        @Volatile var instance: OverlayService? = null
    }

    var chatOpen = false   // 대화창이 열려 있는 동안은 돌아다니지 않아요

    fun onChatOpened() {
        chatOpen = true
        say("응? 나 불렀어? 무슨 얘기할까~")
    }

    fun onChatClosed() {
        chatOpen = false
        scheduleMove()
    }

    // 답을 기다리는 동안: "음.", "음..", "음..." 처럼 점이 늘어나는 로딩 표시 (소리는 매번 조금씩 달라요)
    fun thinkingBubble() {
        thinkingActive = true
        thinkBase = thinkingBases[Random.nextInt(thinkingBases.size)]
        thinkQ = Random.nextInt(3) == 0
        thinkDots = 0
        thinkStartMs = SystemClock.uptimeMillis()
        handler.removeCallbacks(thinkRunnable)
        thinkRunnable.run()
    }

    fun replyBubble(text: String, isLong: Boolean = false) {
        thinkingActive = false
        handler.removeCallbacks(thinkRunnable)
        val cap = if (isLong) 30000L else 15000L
        say(text, (1800L + text.length * 90L).coerceAtMost(cap))
    }

    // ───── 혼자서 가끔 말 걸기 ─────
    private fun scheduleIdle() {
        if (!alive) return
        handler.removeCallbacks(idleRunnable)
        handler.postDelayed(idleRunnable, Random.nextLong(idleMinSec * 1000L, idleMaxSec * 1000L))
    }

    private fun idleTalk() {
        val busy = dragging || touching || chatOpen || thinkingActive || bubble?.visibility == View.VISIBLE
        if (!busy && ChatClient.flag(this, "idle_on")) {
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val timePhrases: List<String> = when (hour) {
                in 5..9 -> morningPhrases
                in 11..13 -> lunchPhrases
                in 0..4 -> nightPhrases
                else -> emptyList()
            }
            val list = if (timePhrases.isNotEmpty() && Random.nextInt(100) < 40) timePhrases else idlePhrases
            say(list[Random.nextInt(list.size)], 3500L)
            if (Random.nextInt(100) < 25) bounce()
        }
        scheduleIdle()
    }

    // ───── 열려 있는 앱에 반응 (앱 이름만 확인) ─────
    private fun pollApp() {
        var next = appPollMs
        if (ChatClient.flag(this, "react_on") && Perm.hasUsageAccess(this)) {
            val pkg = foregroundPackage()
            if (pkg != null && pkg != lastPkg) {
                lastPkg = pkg
                if (!firstPoll) maybeReactToApp(pkg)
            }
            firstPoll = false
        } else {
            next = 15000L
            firstPoll = true
        }
        if (alive) handler.postDelayed(appPollRunnable, next)
    }

    @Suppress("DEPRECATION")
    private fun foregroundPackage(): String? {
        return try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 15000L, now)
            val e = UsageEvents.Event()
            var last: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) last = e.packageName
            }
            last
        } catch (ex: Exception) {
            null
        }
    }

    private fun maybeReactToApp(pkg: String) {
        val list = appReactions[pkg] ?: return
        val now = SystemClock.uptimeMillis()
        if (now - lastAppReactAt < appReactCooldownMs) return
        if (Random.nextInt(100) >= appReactChance) return
        if (dragging || touching || chatOpen || thinkingActive) return
        lastAppReactAt = now
        handler.postDelayed({
            if (alive && !dragging && !touching && !chatOpen) say(list[Random.nextInt(list.size)], 3500L)
        }, 1500L)
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
        instance = this
        scheduleMove()
        scheduleIdle()
        handler.postDelayed(appPollRunnable, appPollMs)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNow()
        return START_STICKY
    }

    override fun onDestroy() {
        alive = false
        instance = null
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
        if (dragging || touching || chatOpen) {
            scheduleMove()
            return
        }
        updateScreen()
        val x0 = petLp.x.toFloat()
        val y0 = petLp.y.toFloat()
        val angle = rand(0f, (2 * Math.PI).toFloat())   // 무작위 방향
        val dist = dp(rand(60f, 160f))                  // 무작위 거리
        walkTo(clampX(x0 + cos(angle) * dist), clampY(y0 + sin(angle) * dist))
    }

    // (x1, y1)까지 걸어가기
    private fun walkTo(x1: Float, y1: Float) {
        val x0 = petLp.x.toFloat()
        val y0 = petLp.y.toFloat()
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
        swingPeak = 0f
        swingTierShown = 0
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
        val wasTier = swingTierShown
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
                say(if (wasTier >= 3) scaryEndPhrase else happyEndPhrase)
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

    // 그네 감지: 좌우로 왕복하면 각도에 따라 반응 (약하게=좋아함, 크게=신남, 거의 180°=무서워함)
    private fun detectSwing(nowMs: Long) {
        if (prevOmega * omega < 0f) {            // 방향이 바뀌는 순간 = 가장 멀리 간 순간
            val twoPi = (2 * Math.PI).toFloat()
            val w = theta - twoPi * Math.round(theta / twoPi).toFloat()  // 여러 바퀴 돈 뒤에도 -π..π로 환산
            val sign = if (w > 0f) 1 else -1
            val amp = abs(w)
            val ok = amp >= swingMinRad
            if (ok && sign != lastTurnSign && nowMs - lastTurnMs < 1300L) {
                swingCount++
                swingPeak = maxOf(swingPeak, amp)
            } else {
                swingCount = if (ok) 1 else 0
                swingPeak = if (ok) amp else 0f
            }
            lastTurnSign = if (ok) sign else 0
            lastTurnMs = nowMs

            val tier = when {
                swingPeak > swingScaryRad -> 3
                swingPeak > swingMaxRad -> 2
                else -> 1
            }
            val need = if (tier >= 2) 2 else swingNeed
            val allowed = dizzyLevel == 0 && (tier >= 2 || !tired)
            if (swingCount >= need && allowed && (tier > swingTierShown || nowMs - lastHappyAt > 5000L)) {
                swingCount = 0
                swingPeak = 0f
                onSwingReaction(tier, nowMs)
            }
        }
        prevOmega = omega
    }

    private fun onSwingReaction(tier: Int, now: Long) {
        lastHappyAt = now
        swingTierShown = maxOf(swingTierShown, tier)
        happy = true
        when (tier) {
            1 -> {
                say(swingPhrases[Random.nextInt(swingPhrases.size)])
                joyHop()
            }
            2 -> {
                say(swingHighPhrases[Random.nextInt(swingHighPhrases.size)])
                joyHop(16f)
            }
            else -> {
                say(swingScaryPhrases[Random.nextInt(swingScaryPhrases.size)])
                shake()
            }
        }
    }

    // 신나서 콩콩
    private fun joyHop(heightDp: Float = 9f) {
        val up = -dp(heightDp)
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
        // 입력줄과 키보드에 가리지 않게 스위미를 화면 위쪽으로 올려 둠
        updateScreen()
        val target = clampY(screenH * 0.3f)
        if (petLp.y > target) walkTo(petLp.x.toFloat(), target)
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
    private fun say(text: String, ms: Long = bubbleMs) {
        val b = bubble ?: return
        updateScreen()
        b.text = text
        val maxW = (screenW * 0.7f).toInt()
        b.maxWidth = maxW
        b.measure(
            View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        b.visibility = View.VISIBLE
        placeBubble()
        handler.removeCallbacks(hideBubble)
        handler.postDelayed(hideBubble, ms)
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
