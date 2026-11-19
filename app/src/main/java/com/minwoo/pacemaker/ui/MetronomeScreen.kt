package com.minwoo.pacemaker.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.minwoo.pacemaker.MetronomeState
import com.minwoo.pacemaker.PaceEngine

data class MetroUi(
    val playing: Boolean,
    val bpm: Int,
    val volume: Float,
    val accentEvery: Int,
    val beatCount: Long,
    val elapsedS: Double,
)

class MetroActions(
    val onToggle: () -> Unit,
    val onBpm: (Int) -> Unit,
    val onVolume: (Float) -> Unit,
    val onAccent: (Int) -> Unit,
)

private val BPM_PRESETS = listOf(60, 90, 120, 150, 170, 180)

/**
 * pulse: 0~1, 박이 울린 직후 1에서 빠르게 0으로 줄어드는 값 (깜빡임용)
 */
@Composable
fun MetronomeScreen(ui: MetroUi, act: MetroActions, pulse: Float, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("METRONOME", color = P.Text, fontWeight = FontWeight.Black, fontSize = 18.sp, letterSpacing = 2.sp)
            Spacer(Modifier.weight(1f))
            if (ui.playing) {
                Text(
                    "${ui.beatCount}박 · ${PaceEngine.fmtClock(ui.elapsedS)}",
                    color = P.Muted, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.clip(CircleShape).background(P.Surface).padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }

        Spacer(Modifier.height(28.dp))

        // 박자 원
        val accentNow = ui.accentEvery > 1 && ui.beatCount > 0 && (ui.beatCount - 1) % ui.accentEvery == 0L
        val ringColor = if (accentNow) P.Fast else P.Accent
        Box(Modifier.size(250.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(250.dp).scale(1f + 0.06f * pulse).clip(CircleShape)
                    .background(ringColor.copy(alpha = 0.10f + 0.25f * pulse)),
            )
            Box(
                Modifier.size(208.dp).clip(CircleShape).background(P.Surface)
                    .border(3.dp, if (ui.playing) ringColor.copy(alpha = 0.4f + 0.6f * pulse) else P.Line, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${ui.bpm}", color = P.Text, fontSize = 76.sp, fontWeight = FontWeight.Black, letterSpacing = (-3).sp)
                    Text("BPM", color = P.Muted, style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "%.2f초마다 1번".format(60.0 / ui.bpm),
                        color = P.Accent, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        // 강세 박 표시 점
        if (ui.accentEvery > 1) {
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val idx = if (ui.beatCount > 0) ((ui.beatCount - 1) % ui.accentEvery).toInt() else -1
                for (i in 0 until ui.accentEvery) {
                    val on = ui.playing && i == idx
                    Box(
                        Modifier.size(if (i == 0) 14.dp else 10.dp).clip(CircleShape)
                            .background(if (on) (if (i == 0) P.Fast else P.Accent) else P.Surface2),
                    )
                }
            }
        }

        Spacer(Modifier.height(22.dp))

        // ± 조절
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RoundStep("−5", 52) { act.onBpm(ui.bpm - 5) }
            RoundStep("−", 64, big = true) { act.onBpm(ui.bpm - 1) }
            Spacer(Modifier.width(8.dp))
            RoundStep("+", 64, big = true) { act.onBpm(ui.bpm + 1) }
            RoundStep("+5", 52) { act.onBpm(ui.bpm + 5) }
        }

        Spacer(Modifier.height(12.dp))
        Slider(
            value = ui.bpm.toFloat(),
            onValueChange = { act.onBpm(it.toInt()) },
            valueRange = MetronomeState.MIN_BPM.toFloat()..MetronomeState.MAX_BPM.toFloat(),
            colors = SliderDefaults.colors(thumbColor = P.Accent, activeTrackColor = P.Accent, inactiveTrackColor = P.Surface2),
            modifier = Modifier.fillMaxWidth(),
        )

        // 프리셋
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (b in BPM_PRESETS) {
                val sel = b == ui.bpm
                Box(
                    Modifier.weight(1f).height(40.dp).clip(CircleShape)
                        .background(if (sel) P.Accent else P.Surface)
                        .clickable { act.onBpm(b) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("$b", color = if (sel) Color.Black else P.Text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
        }
        Text(
            "참고: 달리기 케이던스는 보통 분당 160~180보를 많이 권해요 (사람마다 달라요)",
            color = P.Muted, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )

        Spacer(Modifier.height(20.dp))

        // 재생 버튼
        Column(
            Modifier.fillMaxWidth().height(84.dp).clip(RoundedCornerShape(28.dp))
                .background(if (ui.playing) P.Surface2 else P.Accent)
                .clickable(onClick = act.onToggle),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                if (ui.playing) "■  정지" else "▶  시작",
                color = if (ui.playing) Color.White else Color.Black, fontSize = 26.sp, fontWeight = FontWeight.Black,
            )
            Text(
                if (ui.playing) "화면 꺼져도 계속 울려요 · 알림창에서도 조절" else "음악이랑 같이 들려요 (음악 볼륨 안 줄어듦)",
                color = (if (ui.playing) Color.White else Color.Black).copy(alpha = 0.65f),
                style = MaterialTheme.typography.labelMedium,
            )
        }

        Spacer(Modifier.height(20.dp))

        // 설정
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(P.Surface).padding(16.dp)) {
            Text("강세", color = P.Text, style = MaterialTheme.typography.titleMedium)
            Text("N박마다 첫 박을 높은 소리로 (근력운동 박자 셀 때)", color = P.Muted, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((v, label) in listOf(0 to "없음", 2 to "2박", 3 to "3박", 4 to "4박")) {
                    val sel = v == ui.accentEvery
                    Box(
                        Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(12.dp))
                            .background(if (sel) P.Surface2 else P.Bg)
                            .border(1.dp, if (sel) P.Accent else P.Line, RoundedCornerShape(12.dp))
                            .clickable { act.onAccent(v) },
                        contentAlignment = Alignment.Center,
                    ) { Text(label, color = if (sel) P.Accent else P.Text, fontWeight = FontWeight.Bold) }
                }
            }
            Spacer(Modifier.height(16.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(P.Line))
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("소리 크기", color = P.Text, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Text("${(ui.volume * 100).toInt()}%", color = P.Muted, style = MaterialTheme.typography.labelMedium)
            }
            Slider(
                value = ui.volume, onValueChange = act.onVolume, valueRange = 0.05f..1f,
                colors = SliderDefaults.colors(thumbColor = P.Text, activeTrackColor = P.Text, inactiveTrackColor = P.Surface2),
            )
            Text("폰 미디어 볼륨을 따라가요", color = P.Muted, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RoundStep(label: String, sizeDp: Int, big: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(sizeDp.dp).clip(CircleShape)
            .background(if (big) P.Surface2 else P.Surface)
            .border(1.dp, P.Line, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = P.Text, fontSize = if (big) 30.sp else 16.sp, fontWeight = FontWeight.Bold)
    }
}

/** 달리기 화면에 들어가는 작은 메트로놈 카드 */
@Composable
fun MetronomeMini(ui: MetroUi, act: MetroActions, pulse: Float, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(P.Surface).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(14.dp).scale(1f + 0.5f * pulse).clip(CircleShape)
                .background(if (ui.playing) P.Accent.copy(alpha = 0.4f + 0.6f * pulse) else P.Surface2),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("메트로놈", color = P.Muted, style = MaterialTheme.typography.labelSmall)
            Text("${ui.bpm} BPM", color = P.Text, fontSize = 18.sp, fontWeight = FontWeight.Black)
        }
        RoundStep("−", 40) { act.onBpm(ui.bpm - 1) }
        Spacer(Modifier.width(6.dp))
        RoundStep("+", 40) { act.onBpm(ui.bpm + 1) }
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier.height(40.dp).clip(CircleShape)
                .background(if (ui.playing) P.Surface2 else P.Accent)
                .clickable(onClick = act.onToggle)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (ui.playing) "끄기" else "켜기", color = if (ui.playing) P.Text else Color.Black, fontWeight = FontWeight.Bold)
        }
    }
}
