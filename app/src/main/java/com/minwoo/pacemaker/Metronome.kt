package com.minwoo.pacemaker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

/** 메트로놈 설정·상태 (화면과 서비스가 같이 봄) */
object MetronomeState {
    const val MIN_BPM = 30
    const val MAX_BPM = 240

    @Volatile var playing = false
    @Volatile var bpm = 60
    /** 1.0 = 100%, 최대 2.0 (200% 부스트) */
    @Volatile var volume = 1.5f
    /** 메트로놈 켜져 있는 동안 음악 볼륨 낮추기 (덕킹 유지) */
    @Volatile var duckMusic = true
    /** 0 = 강세 없음, 2/3/4 = N박마다 첫 박 강세 */
    @Volatile var accentEvery = 0
    /** 시작 후 울린 박 수 */
    @Volatile var beatCount = 0L
    /** 마지막 박이 소리로 나간 대략적인 시각 (System.nanoTime) */
    @Volatile var lastBeatNanos = 0L
    @Volatile var startedAtMs = 0L

    fun clampBpm(v: Int) = v.coerceIn(MIN_BPM, MAX_BPM)

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences("metronome", Context.MODE_PRIVATE)
        bpm = clampBpm(p.getInt("bpm", 60))
        volume = p.getFloat("volume2", 1.5f)
        duckMusic = p.getBoolean("duck", true)
        accentEvery = p.getInt("accent", 0)
    }

    fun save(ctx: Context) {
        ctx.getSharedPreferences("metronome", Context.MODE_PRIVATE).edit()
            .putInt("bpm", bpm).putFloat("volume2", volume).putInt("accent", accentEvery)
            .putBoolean("duck", duckMusic).apply()
    }
}

/**
 * 샘플 단위로 박자를 계산해서 소리를 직접 만들어 내보냄 → 오래 틀어도 박자가 밀리지 않음.
 * 오디오 포커스를 요청하지 않으므로 스포티파이 음악과 그냥 같이 섞여서 나옴.
 */
class MetronomeEngine {
    private val sr = 48000
    @Volatile private var running = false
    private var thread: Thread? = null

    // 귀가 잘 듣는 2~3kHz 대역 + 짧은 '딱' 소리(노이즈)로 음악 위에서도 잘 들리게
    private val click = makeClick(2200.0, 0.75)
    private val accentClick = makeClick(3200.0, 0.9)

    private fun makeClick(freq: Double, gain: Double): FloatArray {
        val n = (sr * 0.06).toInt()
        val rnd = java.util.Random(1)
        val raw = DoubleArray(n) { i ->
            val t = i.toDouble() / sr
            val env = exp(-t * 50.0) * minOf(1.0, i / 12.0)
            val tone = sin(2 * PI * freq * t) * 0.7 + sin(2 * PI * freq * 1.5 * t) * 0.3
            val noise = (rnd.nextDouble() * 2 - 1) * exp(-t * 900.0) * 0.6
            tone * env + noise
        }
        val peak = raw.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1e-9)
        return FloatArray(n) { (raw[it] / peak * 0.9 * gain / 0.9).toFloat() }
    }

    /** 볼륨 100% 넘으면 부드럽게 눌러서(소프트 클리핑) 더 크게 들리게 */
    private fun shape(x: Float, vol: Float): Float {
        val y = x * vol
        return if (vol <= 1f) y else (kotlin.math.tanh(y * 1.5) / kotlin.math.tanh(1.5)).toFloat()
    }

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "metronome").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    private fun loop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val builder = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sr)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(max(minBuf, sr / 20 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
        if (Build.VERSION.SDK_INT >= 26) builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        val track = try { builder.build() } catch (e: Exception) { running = false; return }

        val chunk = sr / 100 // 10ms
        val buf = ShortArray(chunk)
        var untilBeat = 0.0
        var cur: FloatArray? = null
        var pos = 0
        var beatIndex = 0L
        try {
            track.play()
            while (running) {
                val vol = MetronomeState.volume
                for (i in 0 until chunk) {
                    if (untilBeat <= 0.0) {
                        val acc = MetronomeState.accentEvery
                        cur = if (acc > 1 && beatIndex % acc == 0L) accentClick else click
                        pos = 0
                        beatIndex++
                        untilBeat += sr * 60.0 / MetronomeState.bpm
                        // 버퍼 지연만큼 늦게 들리므로 화면 깜빡임도 맞춰서 기록
                        val delayNs = ((i + minBuf / 2).toLong() * 1_000_000_000L) / sr
                        MetronomeState.lastBeatNanos = System.nanoTime() + delayNs
                        MetronomeState.beatCount = beatIndex
                    }
                    val c = cur
                    val v = if (c != null && pos < c.size) shape(c[pos++], vol) else 0f
                    buf[i] = (v.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
                    untilBeat -= 1.0
                }
                track.write(buf, 0, chunk)
            }
        } finally {
            try { track.pause(); track.flush(); track.stop() } catch (_: Exception) {}
            track.release()
        }
    }
}

/** 화면이 꺼져도 메트로놈이 계속 울리도록 하는 서비스 (달리기와 따로 켜고 끌 수 있음) */
class MetronomeService : Service() {
    companion object {
        const val ACTION_START = "m_start"
        const val ACTION_STOP = "m_stop"
        const val ACTION_UP = "m_up"
        const val ACTION_DOWN = "m_down"
        const val ACTION_REFRESH = "m_refresh"
        private const val CHANNEL_ID = "metronome"
        private const val NOTIF_ID = 2

        fun send(ctx: Context, action: String) {
            val i = Intent(ctx, MetronomeService::class.java).setAction(action)
            if (action == ACTION_START) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }

    private val engine = MetronomeEngine()
    private var wakeLock: PowerManager.WakeLock? = null
    private var focusHeld = false
    private val focusRequest by lazy {
        android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { }
            .build()
    }

    /** 설정에 맞춰 음악 낮추기(덕킹) 유지/해제 */
    private fun syncDuck() {
        val am = getSystemService(android.media.AudioManager::class.java)
        val want = MetronomeState.playing && MetronomeState.duckMusic
        if (want && !focusHeld) {
            focusHeld = am.requestAudioFocus(focusRequest) == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else if (!want && focusHeld) {
            am.abandonAudioFocusRequest(focusRequest)
            focusHeld = false
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "메트로놈", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startMetronome()
            ACTION_STOP -> { stopMetronome(); return START_NOT_STICKY }
            ACTION_UP -> { MetronomeState.bpm = MetronomeState.clampBpm(MetronomeState.bpm + 1); MetronomeState.save(this) }
            ACTION_DOWN -> { MetronomeState.bpm = MetronomeState.clampBpm(MetronomeState.bpm - 1); MetronomeState.save(this) }
        }
        if (!MetronomeState.playing) {
            // 꺼진 상태에서 알림 버튼 등으로 깨어난 경우
            getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        syncDuck()
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        return START_NOT_STICKY
    }

    private fun startMetronome() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(NOTIF_ID, n)
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pacemaker:metronome")
                .apply { acquire(4 * 60 * 60 * 1000L) }
        }
        if (!MetronomeState.playing) {
            MetronomeState.beatCount = 0
            MetronomeState.startedAtMs = System.currentTimeMillis()
            MetronomeState.playing = true
            engine.start()
        }
    }

    private fun stopMetronome() {
        MetronomeState.playing = false
        engine.stop()
        syncDuck()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
        stopSelf()
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 10, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        fun action(act: String, label: String, req: Int) = Notification.Action.Builder(
            null, label,
            PendingIntent.getService(
                this, req, Intent(this, MetronomeService::class.java).setAction(act),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        ).build()
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentTitle("메트로놈 ${MetronomeState.bpm} BPM")
            .setContentText("%.2f초마다 1번".format(60.0 / MetronomeState.bpm))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action(ACTION_DOWN, "− 1", 11))
            .addAction(action(ACTION_UP, "+ 1", 12))
            .addAction(action(ACTION_STOP, "정지", 13))
            .build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 앱을 밀어서 닫으면 메트로놈도 끔 (달리기 중이 아닐 때)
        if (RunState.phase != RunState.Phase.RUNNING) stopMetronome()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        MetronomeState.playing = false
        engine.stop()
        syncDuck()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}
