package com.minwoo.pacemaker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.minwoo.pacemaker.ui.HistoryScreen
import com.minwoo.pacemaker.ui.LiveUi
import com.minwoo.pacemaker.ui.P
import com.minwoo.pacemaker.ui.PacemakerTheme
import com.minwoo.pacemaker.ui.RunActions
import com.minwoo.pacemaker.ui.RunScreen
import com.minwoo.pacemaker.ui.SummaryScreen
import kotlinx.coroutines.delay
import org.osmdroid.config.Configuration
import java.io.File

class MainActivity : ComponentActivity() {

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true)

        // 지도(OpenStreetMap) 설정
        Configuration.getInstance().apply {
            load(applicationContext, getSharedPreferences("osmdroid", MODE_PRIVATE))
            userAgentValue = packageName
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        }

        askPermissions()
        setContent { PacemakerTheme { App() } }
    }

    fun hasLocationPermission() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun askPermissions() {
        val need = mutableListOf<String>()
        if (!hasLocationPermission()) {
            need += Manifest.permission.ACCESS_FINE_LOCATION
            need += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) need += Manifest.permission.POST_NOTIFICATIONS
        if (need.isNotEmpty()) permLauncher.launch(need.toTypedArray())
    }

    fun isIgnoringBattery(): Boolean =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    fun requestIgnoreBattery() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}

private sealed interface Screen {
    data object Run : Screen
    data object History : Screen
    data class Summary(val id: Long, val from: Screen) : Screen
}

fun snapshotLive(batteryOk: Boolean): LiveUi {
    val e = RunState.engine
    val active = RunState.phase == RunState.Phase.RUNNING || RunState.phase == RunState.Phase.FINISHED
    return LiveUi(
        phase = RunState.phase,
        targetKmh = RunState.targetKmh,
        goalKm = RunState.goalKm,
        quietMode = RunState.quietMode,
        gpsAccuracy = RunState.gpsAccuracy,
        distanceM = if (active) e?.distanceM ?: 0.0 else 0.0,
        elapsedS = if (active) e?.elapsedS ?: 0.0 else 0.0,
        currentKmh = if (active) e?.currentKmh else null,
        diffS = if (active) e?.diffS ?: 0.0 else 0.0,
        track = if (active) RunState.trackSnapshot() else emptyList(),
        lastMessage = RunState.lastMessage,
        lastLat = RunState.lastLat,
        lastLon = RunState.lastLon,
        batteryOk = batteryOk,
        lastSavedId = RunState.lastSavedId,
    )
}

@Composable
private fun App() {
    val act = LocalContext.current as MainActivity
    var screen by remember { mutableStateOf<Screen>(Screen.Run) }
    var tick by remember { mutableIntStateOf(0) }
    var historyVersion by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) { delay(500); tick++ }
    }

    BackHandler(enabled = screen !is Screen.Run) {
        screen = when (val s = screen) {
            is Screen.Summary -> s.from
            else -> Screen.Run
        }
    }

    Column(Modifier.fillMaxSize().background(P.Bg).statusBarsPadding().navigationBarsPadding()) {
        Box(Modifier.weight(1f)) {
            when (val s = screen) {
                Screen.Run -> {
                    val ui = remember(tick) { snapshotLive(act.isIgnoringBattery()) }
                    RunScreen(
                        ui,
                        RunActions(
                            onSpeed = { k ->
                                if (RunState.phase == RunState.Phase.IDLE || RunState.phase == RunState.Phase.FINISHED) {
                                    RunState.targetKmh = k
                                } else RunService.send(act, RunService.ACTION_SET_SPEED, k)
                                tick++
                            },
                            onMain = {
                                when (RunState.phase) {
                                    RunState.Phase.IDLE, RunState.Phase.FINISHED -> {
                                        if (!act.hasLocationPermission()) act.askPermissions()
                                        else RunService.send(act, RunService.ACTION_PREPARE)
                                    }
                                    RunState.Phase.PREPARING -> RunService.send(act, RunService.ACTION_START)
                                    RunState.Phase.RUNNING ->
                                        Toast.makeText(act, "정지는 길게 눌러요", Toast.LENGTH_SHORT).show()
                                }
                                tick++
                            },
                            onStop = {
                                if (RunState.phase != RunState.Phase.IDLE) {
                                    val wasRunning = RunState.phase == RunState.Phase.RUNNING
                                    RunService.send(act, RunService.ACTION_STOP)
                                    if (wasRunning) Toast.makeText(act, "정지했어요. 기록은 '기록' 탭에 저장돼요", Toast.LENGTH_SHORT).show()
                                }
                                tick++
                            },
                            onGoalStep = { d ->
                                RunState.goalKm = (RunState.goalKm + d).coerceIn(0.5, 42.0); tick++
                            },
                            onQuiet = { q -> RunState.quietMode = q; RunState.engine?.quietMode = q; tick++ },
                            onBattery = { act.requestIgnoreBattery() },
                            onOpenRecord = { id -> screen = Screen.Summary(id, Screen.Run) },
                        ),
                    )
                }
                Screen.History -> {
                    val records = remember(historyVersion) { RunRepository.list(act) }
                    HistoryScreen(records, onOpen = { screen = Screen.Summary(it, Screen.History) })
                }
                is Screen.Summary -> {
                    val rec = remember(s.id) { RunRepository.load(act, s.id) }
                    if (rec == null) {
                        LaunchedEffect(s.id) { screen = s.from }
                    } else {
                        SummaryScreen(
                            rec,
                            onBack = { screen = s.from },
                            onDelete = {
                                RunRepository.delete(act, rec.id)
                                if (RunState.lastSavedId == rec.id) RunState.lastSavedId = null
                                historyVersion++
                                screen = Screen.History
                            },
                        )
                    }
                }
            }
        }
        BottomTabs(
            current = if (screen is Screen.Run || (screen as? Screen.Summary)?.from == Screen.Run) 0 else 1,
            onSelect = { i ->
                if (i == 0) screen = Screen.Run else { historyVersion++; screen = Screen.History }
            },
        )
    }
}

@Composable
private fun BottomTabs(current: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(P.Bg).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf("🏃  달리기", "📊  기록").forEachIndexed { i, label ->
            val sel = i == current
            Box(
                Modifier.weight(1f).height(48.dp).clip(CircleShape)
                    .background(if (sel) P.Surface2 else P.Bg)
                    .clickable { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label, color = if (sel) P.Text else P.Muted,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                )
            }
        }
    }
}
