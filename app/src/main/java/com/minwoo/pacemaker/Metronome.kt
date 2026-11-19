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
    @Volatile var volume = 0.8f
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
        volume = p.getFloat("volume", 0.8f)
        accentEvery = p.getInt("accent", 0)
    }

    fun save(ctx: Context) {
        ctx.getSharedPreferences("metronome", Context.MODE_PRIVATE).edit()
            .putInt("bpm", bpm).putFloat("volume", volume).putInt("accent", accentEvery).apply()
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

    private val click = makeClick(1500.0, 0.55)
    private val accentClick = makeClick(2400.0, 0.9)

    private fun makeClick(freq: Double, gain: Double): FloatArray {
        val n = (sr * 0.035).toInt()
        return FloatArray(n) { i ->
            val t = i.toDouble() / sr
            val env = exp(-t * 120.0) * minOf(1.0, i / 24.0)
            (sin(2 * PI * freq * t) * 0.8 + sin(2 * PI * freq * 2.01 * t) * 0.2).times(env * gain).toFloat()
        }
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
                    val v = if (c != null && pos < c.size) c[pos++] * vol else 0f
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
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}
