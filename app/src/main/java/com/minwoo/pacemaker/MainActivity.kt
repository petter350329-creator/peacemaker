package com.minwoo.pacemaker

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var tvTarget: TextView
    private lateinit var tvDistance: TextView
    private lateinit var tvTime: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvDiff: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvMsg: TextView
    private lateinit var btnMain: Button
    private lateinit var etGoal: EditText
    private lateinit var cbQuiet: CheckBox
    private lateinit var btnBattery: Button
    private val speedButtons = mutableListOf<Pair<Double, Button>>()

    private val bg = Color.parseColor("#111418")
    private val fg = Color.parseColor("#F2F2F2")
    private val accent = Color.parseColor("#FF7A45")
    private val muted = Color.parseColor("#8A929C")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true)
        setContentView(buildUi())
        askPermissions()
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresher)
        btnBattery.visibility = if (isIgnoringBattery()) View.GONE else View.VISIBLE
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresher)
    }

    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 500)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(size: Float, color: Int = fg, bold: Boolean = false) = TextView(this).apply {
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(16), dp(24), dp(16), dp(24))
        }

        tvTarget = label(18f, accent, true)
        tvDistance = label(64f, fg, true)
        tvTime = label(36f, fg)
        tvSpeed = label(22f, fg)
        tvDiff = label(22f, fg, true)
        tvStatus = label(14f, muted)
        tvMsg = label(14f, muted)

        root.addView(tvTarget)
        root.addView(tvDistance)
        root.addView(tvTime)
        root.addView(tvSpeed)
        root.addView(tvDiff)

        // 속도 프리셋 버튼 6 / 9 / 12 / 15
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(16), 0, dp(8))
        }
        for (kmh in RunState.PRESETS) {
            val b = Button(this).apply {
                text = PaceEngine.fmtKmh(kmh)
                textSize = 24f
                setOnClickListener { onSpeed(kmh) }
            }
            row.addView(b, LinearLayout.LayoutParams(0, dp(72), 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
            speedButtons += kmh to b
        }
        root.addView(row)
        root.addView(label(12f, muted).apply { text = "시속(km/h) · 뛰는 중에도 누르면 바로 바뀌어요" })

        btnMain = Button(this).apply {
            textSize = 26f
            setOnClickListener { onMainClick() }
            setOnLongClickListener { onMainLongClick(); true }
        }
        root.addView(btnMain, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(96)).apply {
            topMargin = dp(16)
        })
        root.addView(tvStatus)
        root.addView(tvMsg)

        // 설정
        val settings = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(16), 0, 0)
        }
        settings.addView(TextView(this).apply { text = "목표 거리(km) "; setTextColor(fg); textSize = 16f })
        etGoal = EditText(this).apply {
            setText(PaceEngine.fmtKmh(RunState.goalKm))
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setTextColor(fg)
            textSize = 18f
            minEms = 3
        }
        settings.addView(etGoal)
        cbQuiet = CheckBox(this).apply {
            text = "조용히 모드"
            setTextColor(fg)
            isChecked = RunState.quietMode
            setOnCheckedChangeListener { _, c ->
                RunState.quietMode = c
                RunState.engine?.quietMode = c
            }
        }
        settings.addView(cbQuiet, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(16)
        })
        root.addView(settings)
        root.addView(label(12f, muted).apply {
            text = "조용히 모드: 목표 속도 ±3% 안이면 200m 안내 생략 (1km·남은 200m·완주는 항상 안내)"
            gravity = Gravity.START
        })

        btnBattery = Button(this).apply {
            text = "배터리 최적화 제외하기 (화면 꺼져도 안 끊기게)"
            textSize = 14f
            setOnClickListener { requestIgnoreBattery() }
        }
        root.addView(btnBattery, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(16)
        })

        return ScrollView(this).apply {
            setBackgroundColor(bg)
            addView(root)
        }
    }

    private fun onSpeed(kmh: Double) {
        if (RunState.phase == RunState.Phase.IDLE || RunState.phase == RunState.Phase.FINISHED) {
            RunState.targetKmh = kmh
        } else {
            RunService.send(this, RunService.ACTION_SET_SPEED, kmh)
        }
        refresh()
    }

    private fun onMainClick() {
        when (RunState.phase) {
            RunState.Phase.IDLE, RunState.Phase.FINISHED -> {
                if (!hasLocationPermission()) { askPermissions(); return }
                val g = etGoal.text.toString().toDoubleOrNull()
                if (g == null || g <= 0.1) {
                    Toast.makeText(this, "목표 거리를 확인해줘요", Toast.LENGTH_SHORT).show(); return
                }
                RunState.goalKm = g
                RunService.send(this, RunService.ACTION_PREPARE)
            }
            RunState.Phase.PREPARING -> RunService.send(this, RunService.ACTION_START)
            RunState.Phase.RUNNING -> Toast.makeText(this, "정지는 길게 눌러요", Toast.LENGTH_SHORT).show()
        }
        handler.postDelayed({ refresh() }, 150)
    }

    private fun onMainLongClick() {
        if (RunState.phase != RunState.Phase.IDLE) {
            RunService.send(this, RunService.ACTION_STOP)
            handler.postDelayed({ refresh() }, 150)
        }
    }

    private fun refresh() {
        val e = RunState.engine
        val phase = RunState.phase
        tvTarget.text = "목표 시속 ${PaceEngine.fmtKmh(RunState.targetKmh)} · ${PaceEngine.paceStr(RunState.targetKmh)}/km · " +
            "${PaceEngine.fmtKmh(RunState.goalKm)}km ${PaceEngine.fmtClock(RunState.goalKm * 3600 / RunState.targetKmh)}"

        for ((kmh, b) in speedButtons) {
            val sel = kmh == RunState.targetKmh
            b.setBackgroundColor(if (sel) accent else Color.parseColor("#2A2F36"))
            b.setTextColor(if (sel) Color.BLACK else fg)
        }

        val running = phase == RunState.Phase.RUNNING || phase == RunState.Phase.FINISHED
        if (e != null && running) {
            tvDistance.text = "%.2f km".format(e.distanceM / 1000)
            tvTime.text = PaceEngine.fmtClock(e.elapsedS)
            val cur = e.currentKmh
            tvSpeed.text = if (cur == null) "현재 시속 —" else "현재 시속 ${PaceEngine.fmtKmh(cur)}"
            val diff = e.diffS
            tvDiff.text = PaceEngine.fmtDiff(diff)
            tvDiff.setTextColor(
                when {
                    diff > 5 -> Color.parseColor("#FF5C5C")
                    diff < -5 -> Color.parseColor("#4FC3F7")
                    else -> Color.parseColor("#7CD992")
                }
            )
        } else {
            tvDistance.text = "0.00 km"
            tvTime.text = "0:00"
            tvSpeed.text = ""
            tvDiff.text = ""
        }

        val acc = RunState.gpsAccuracy
        when (phase) {
            RunState.Phase.IDLE -> {
                btnMain.text = "GPS 준비"
                btnMain.setBackgroundColor(Color.parseColor("#3A6EA5"))
                tvStatus.text = "바톤 받기 전에 미리 눌러서 GPS를 잡아둬요"
            }
            RunState.Phase.PREPARING -> {
                btnMain.text = "출발!"
                btnMain.setBackgroundColor(Color.parseColor("#2E9E5B"))
                tvStatus.text = when {
                    acc == null -> "GPS 잡는 중… (하늘이 보이는 곳에서)"
                    acc <= 15f -> "GPS 준비 완료 · 정확도 ${acc.toInt()}m"
                    else -> "GPS 정확도 ${acc.toInt()}m · 조금만 기다려요"
                }
            }
            RunState.Phase.RUNNING -> {
                btnMain.text = "달리는 중 (길게 눌러 정지)"
                btnMain.setBackgroundColor(Color.parseColor("#444B55"))
                tvStatus.text = if (acc == null) "" else "GPS 정확도 ${acc.toInt()}m"
            }
            RunState.Phase.FINISHED -> {
                btnMain.text = "완주! (길게 눌러 종료)"
                btnMain.setBackgroundColor(Color.parseColor("#444B55"))
                tvStatus.text = ""
            }
        }
        btnMain.setTextColor(Color.WHITE)
        tvMsg.text = RunState.lastMessage
        etGoal.isEnabled = phase == RunState.Phase.IDLE || phase == RunState.Phase.FINISHED
    }

    private fun hasLocationPermission() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun askPermissions() {
        val need = mutableListOf<String>()
        if (!hasLocationPermission()) {
            need += Manifest.permission.ACCESS_FINE_LOCATION
            need += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) need += Manifest.permission.POST_NOTIFICATIONS
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
    }

    private fun isIgnoringBattery(): Boolean =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun requestIgnoreBattery() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}
