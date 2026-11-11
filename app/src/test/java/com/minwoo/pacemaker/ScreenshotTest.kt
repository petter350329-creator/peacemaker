package com.minwoo.pacemaker

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import android.graphics.Canvas
import com.minwoo.pacemaker.ui.HistoryScreen
import com.minwoo.pacemaker.ui.LiveUi
import com.minwoo.pacemaker.ui.P
import com.minwoo.pacemaker.ui.PacemakerTheme
import com.minwoo.pacemaker.ui.RunActions
import com.minwoo.pacemaker.ui.RunScreen
import com.minwoo.pacemaker.ui.SummaryScreen
import com.minwoo.pacemaker.ui.UiFlags
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h880dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Before fun setup() { UiFlags.mapTiles = false }

    private val noop = RunActions({}, {}, {}, {}, {}, {}, {})

    /** 400m 트랙 비슷한 타원 코스를 4km 달린 가짜 기록 */
    private fun fakeRecord(): RunRecord {
        val pts = ArrayList<TrackPoint>()
        var d = 0.0; var s = 0.0
        val lat0 = 37.6; val lon0 = 127.14
        while (d < 4000) {
            val kmh = when { d < 400 -> 10.4; d < 2000 -> 9.0 + sin(d / 90) * 0.2; d < 2600 -> 8.3; d < 3800 -> 9.1; else -> 10.2 }
            d += kmh / 3.6; s += 1.0
            val a = d / 400 * 2 * Math.PI
            val x = cos(a) * 70; val y = sin(a) * 40
            pts += TrackPoint(s, lat0 + y / 111320, lon0 + x / (111320 * cos(Math.toRadians(lat0))), d, kmh, 9.0)
        }
        return RunRecord(1762934400000, 4000.0, d, s, s - 1600.0, 9.0, true, pts)
    }

    private fun shot(name: String, content: @Composable () -> Unit) {
        rule.setContent { PacemakerTheme { Box(Modifier.fillMaxSize().background(P.Bg)) { content() } } }
        rule.mainClock.advanceTimeBy(1000)
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun live(phase: RunState.Phase, rec: RunRecord?) = LiveUi(
        phase = phase, targetKmh = 9.0, goalKm = 4.0, quietMode = true,
        gpsAccuracy = if (phase == RunState.Phase.IDLE) null else 5f,
        distanceM = rec?.points?.get(1500)?.distM ?: 0.0,
        elapsedS = rec?.points?.get(1500)?.s ?: 0.0,
        currentKmh = if (rec != null) 9.4 else null,
        diffS = if (rec != null) -12.0 else 0.0,
        track = rec?.points?.take(1500) ?: emptyList(),
        lastMessage = if (rec != null) "팔백, 시속 9.4. 빨라요, 조금 천천히" else "",
        lastLat = 37.6, lastLon = 127.14, batteryOk = phase != RunState.Phase.IDLE, lastSavedId = null,
    )

    @Test fun runIdle() = shot("1_run_idle") { RunScreen(live(RunState.Phase.IDLE, null), noop) }
    @Test fun runRunning() = shot("2_run_running") { RunScreen(live(RunState.Phase.RUNNING, fakeRecord()), noop) }
    @Test fun summary() = shot("3_summary") { SummaryScreen(fakeRecord(), {}, {}) }
    @Test fun history() = shot("4_history") {
        val r = fakeRecord()
        HistoryScreen(listOf(r, r.copy(id = r.id - 86400000L * 2, finished = false, distanceM = 2310.0, durationS = 900.0, diffS = 23.0)), {})
    }
}
