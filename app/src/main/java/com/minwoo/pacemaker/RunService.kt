package com.minwoo.pacemaker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

class RunService : Service() {

    companion object {
        const val ACTION_PREPARE = "prepare"
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_SET_SPEED = "set_speed"
        const val ACTION_SPEED_UP = "speed_up"
        const val ACTION_SPEED_DOWN = "speed_down"
        const val EXTRA_KMH = "kmh"

        private const val CHANNEL_ID = "run"
        private const val NOTIF_ID = 1

        fun send(ctx: Context, action: String, kmh: Double? = null) {
            val i = Intent(ctx, RunService::class.java).setAction(action)
            if (kmh != null) i.putExtra(EXTRA_KMH, kmh)
            if (action == ACTION_PREPARE) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }

    private lateinit var locationManager: LocationManager
    private lateinit var audioManager: AudioManager
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var pendingUtterances = 0
    private var gpsReadyAnnounced = false
    private var utteranceSeq = 0

    private val speechAttrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    // 덕킹: 음악(스포티파이 등)은 끄지 않고 잠깐 소리만 줄이도록 요청
    private val focusRequest: AudioFocusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(speechAttrs)
            .setWillPauseWhenDucked(false)
            .build()
    }

    private val listener = object : LocationListener {
        override fun onLocationChanged(loc: Location) = handleLocation(loc)
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) { speak("GPS가 꺼졌어요") }
        @Deprecated("old API")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private val ticker = object : Runnable {
        override fun run() {
            RunState.engine?.tick(System.currentTimeMillis())
            updateNotification()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LocationManager::class.java)
        audioManager = getSystemService(AudioManager::class.java)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "달리기", NotificationManager.IMPORTANCE_LOW)
        )
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.KOREAN
                tts?.setAudioAttributes(speechAttrs)
                tts?.setSpeechRate(1.1f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) = utteranceFinished()
                    @Deprecated("old API")
                    override fun onError(id: String?) = utteranceFinished()
                })
                ttsReady = true
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PREPARE -> prepare()
            ACTION_START -> startRun()
            ACTION_STOP -> stopAll()
            ACTION_SET_SPEED -> setSpeed(intent.getDoubleExtra(EXTRA_KMH, RunState.targetKmh))
            ACTION_SPEED_UP -> stepSpeed(+1)
            ACTION_SPEED_DOWN -> stepSpeed(-1)
        }
        return START_NOT_STICKY
    }

    private fun prepare() {
        val notif = buildNotification("GPS 잡는 중…")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pacemaker:run")
                .apply { acquire(3 * 60 * 60 * 1000L) }
        }
        RunState.engine = PaceEngine(RunState.targetKmh, RunState.goalKm * 1000.0, quietMode = RunState.quietMode)
        RunState.phase = RunState.Phase.PREPARING
        RunState.gpsAccuracy = null
        gpsReadyAnnounced = false
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
        } catch (e: SecurityException) {
            RunState.lastMessage = "위치 권한이 없어요"
            stopAll()
            return
        }
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    private fun startRun() {
        if (RunState.phase != RunState.Phase.PREPARING) return
        val e = RunState.engine ?: return
        e.quietMode = RunState.quietMode
        val now = System.currentTimeMillis()
        e.start(now)
        RunState.startMs = now
        RunState.initialTargetKmh = RunState.targetKmh
        RunState.lastSavedId = null
        RunState.clearTrack()
        val la = RunState.lastLat; val lo = RunState.lastLon
        if (la != null && lo != null) RunState.addPoint(TrackPoint(0.0, la, lo, 0.0, 0.0, RunState.targetKmh))
        RunState.phase = RunState.Phase.RUNNING
        speak("출발! 목표 시속 ${PaceEngine.fmtKmh(RunState.targetKmh)}")
    }

    private fun setSpeed(kmh: Double) {
        RunState.targetKmh = kmh
        val e = RunState.engine
        val msg = e?.setTarget(kmh) ?: "목표 시속 ${PaceEngine.fmtKmh(kmh)}"
        if (RunState.phase == RunState.Phase.RUNNING || RunState.phase == RunState.Phase.PREPARING) speak(msg)
        updateNotification()
    }

    private fun stepSpeed(dir: Int) {
        val p = RunState.PRESETS
        val idx = p.indexOfFirst { it == RunState.targetKmh }.let { if (it < 0) 1 else it }
        val next = (idx + dir).coerceIn(0, p.size - 1)
        setSpeed(p[next])
    }

    private fun handleLocation(loc: Location) {
        RunState.gpsAccuracy = loc.accuracy
        if (loc.accuracy <= 25f) { RunState.lastLat = loc.latitude; RunState.lastLon = loc.longitude }
        if (!gpsReadyAnnounced && loc.accuracy <= 15f && RunState.phase == RunState.Phase.PREPARING) {
            gpsReadyAnnounced = true
            speak("GPS 준비됐어요")
        }
        var speed: Float? = if (loc.hasSpeed()) loc.speed else null
        if (speed != null && Build.VERSION.SDK_INT >= 26 && loc.hasSpeedAccuracy() && loc.speedAccuracyMetersPerSecond > 1.5f) {
            speed = null
        }
        val e = RunState.engine ?: return
        val wasFinished = e.finished
        val prevDist = e.distanceM
        val msgs = e.onLocation(loc.latitude, loc.longitude, loc.accuracy, System.currentTimeMillis(), speed)
        for (m in msgs) speak(m)
        if (RunState.phase == RunState.Phase.RUNNING && e.distanceM > prevDist) {
            RunState.addPoint(
                TrackPoint(e.elapsedS, loc.latitude, loc.longitude, e.distanceM, e.currentKmh ?: 0.0, e.targetKmh)
            )
        }
        if (!wasFinished && e.finished) {
            RunState.phase = RunState.Phase.FINISHED
            saveRecord(finished = true)
        }
    }

    /** 완주했거나, 50m 이상 뛰고 정지한 경우 기록 저장 */
    private fun saveRecord(finished: Boolean) {
        val e = RunState.engine ?: return
        if (e.distanceM < 50) return
        val rec = RunRecord(
            id = RunState.startMs,
            goalM = e.goalM,
            distanceM = e.distanceM,
            durationS = e.elapsedS,
            diffS = e.diffS,
            initialTargetKmh = RunState.initialTargetKmh,
            finished = finished,
            points = RunState.trackSnapshot(),
        )
        try {
            RunRepository.save(this, rec)
            RunState.lastSavedId = rec.id
        } catch (ex: Exception) {
            RunState.lastMessage = "기록 저장 실패: ${ex.message}"
        }
    }

    private fun speak(text: String) {
        RunState.lastMessage = text
        val t = tts ?: return
        if (!ttsReady) return
        if (pendingUtterances == 0) audioManager.requestAudioFocus(focusRequest)
        pendingUtterances++
        val params = Bundle()
        t.speak(text, TextToSpeech.QUEUE_ADD, params, "u${utteranceSeq++}")
    }

    private fun utteranceFinished() {
        handler.post {
            pendingUtterances = (pendingUtterances - 1).coerceAtLeast(0)
            if (pendingUtterances == 0) {
                // 말 끝나고 살짝 쉬었다가 음악 볼륨 복구
                handler.postDelayed({
                    if (pendingUtterances == 0) audioManager.abandonAudioFocusRequest(focusRequest)
                }, 300)
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        fun action(act: String, label: String, req: Int): Notification.Action {
            val pi = PendingIntent.getService(
                this, req, Intent(this, RunService::class.java).setAction(act),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            return Notification.Action.Builder(null, label, pi).build()
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("페이스메이커 · 목표 시속 ${PaceEngine.fmtKmh(RunState.targetKmh)}")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action(ACTION_SPEED_DOWN, "◀ 느리게", 1))
            .addAction(action(ACTION_SPEED_UP, "빠르게 ▶", 2))
            .build()
    }

    private fun updateNotification() {
        if (RunState.phase == RunState.Phase.IDLE) return
        val e = RunState.engine
        val text = when (RunState.phase) {
            RunState.Phase.PREPARING -> {
                val a = RunState.gpsAccuracy
                if (a == null) "GPS 잡는 중…" else "GPS 정확도 ${a.toInt()}m · 출발 대기"
            }
            else -> if (e == null) "" else
                "%.2fkm · %s · %s".format(e.distanceM / 1000, PaceEngine.fmtClock(e.elapsedS), PaceEngine.fmtDiff(e.diffS))
        }
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    private fun stopAll() {
        if (RunState.phase == RunState.Phase.RUNNING) {
            RunState.engine?.tick(System.currentTimeMillis())
            saveRecord(finished = false)
        }
        handler.removeCallbacks(ticker)
        try { locationManager.removeUpdates(listener) } catch (_: Exception) {}
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        if (RunState.phase != RunState.Phase.FINISHED) RunState.phase = RunState.Phase.IDLE
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try { locationManager.removeUpdates(listener) } catch (_: Exception) {}
        wakeLock?.let { if (it.isHeld) it.release() }
        audioManager.abandonAudioFocusRequest(focusRequest)
        tts?.shutdown()
        super.onDestroy()
    }
}
