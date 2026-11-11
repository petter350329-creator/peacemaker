package com.minwoo.pacemaker.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.minwoo.pacemaker.PaceEngine
import com.minwoo.pacemaker.RunState.Phase
import com.minwoo.pacemaker.TrackPoint
import kotlin.math.abs
import kotlin.math.roundToInt

/** 달리기 화면이 그리는 데 필요한 값 묶음 (테스트에서 가짜 값 넣기 쉽게) */
data class LiveUi(
    val phase: Phase,
    val targetKmh: Double,
    val goalKm: Double,
    val quietMode: Boolean,
    val gpsAccuracy: Float?,
    val distanceM: Double,
    val elapsedS: Double,
    val currentKmh: Double?,
    val diffS: Double,
    val track: List<TrackPoint>,
    val lastMessage: String,
    val lastLat: Double?,
    val lastLon: Double?,
    val batteryOk: Boolean,
    val lastSavedId: Long?,
)

class RunActions(
    val onSpeed: (Double) -> Unit,
    val onMain: () -> Unit,
    val onStop: () -> Unit,
    val onGoalStep: (Double) -> Unit,
    val onQuiet: (Boolean) -> Unit,
    val onBattery: () -> Unit,
    val onOpenRecord: (Long) -> Unit,
)

val PRESETS = listOf(6.0, 9.0, 12.0, 15.0)

@Composable
fun RunScreen(ui: LiveUi, act: RunActions, modifier: Modifier = Modifier) {
    val active = ui.phase == Phase.RUNNING || ui.phase == Phase.FINISHED
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        TopBar(ui)
        Spacer(Modifier.height(12.dp))

        // 지도
        Box(
            Modifier
                .fillMaxWidth()
                .height(if (active) 210.dp else 170.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(P.Surface2),
        ) {
            RouteMap(ui.track, live = true, modifier = Modifier.fillMaxSize(), centerLat = ui.lastLat, centerLon = ui.lastLon)
            TargetChip(ui, Modifier.align(Alignment.TopStart).padding(12.dp))
            if (ui.lastLat == null && ui.track.isEmpty()) {
                Text(
                    if (ui.phase == Phase.IDLE) "GPS 준비를 누르면 지도에 내 위치가 떠요" else "GPS 신호 찾는 중…",
                    color = P.Muted, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        // 큰 거리 숫자
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Text(
                "%.2f".format(ui.distanceM / 1000),
                style = MaterialTheme.typography.displayLarge, color = P.Text,
            )
            Text(
                " km", style = MaterialTheme.typography.titleLarge, color = P.Muted,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }

        // 시간 / 현재 속도 / 목표 대비
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("시간", PaceEngine.fmtClock(ui.elapsedS), null, P.Text, Modifier.weight(1f))
            val cur = ui.currentKmh
            StatTile(
                "현재 시속",
                if (cur == null || !active) "—" else PaceEngine.fmtKmh(cur),
                if (cur == null || !active) null else PaceEngine.paceStr(cur) + "/km",
                if (cur == null || !active) P.Text else P.speedColor(cur, ui.targetKmh),
                Modifier.weight(1f),
            )
            val d = ui.diffS.roundToInt()
            StatTile(
                "목표 대비",
                if (!active) "—" else if (abs(d) <= 1) "±0" else if (d > 0) "+${d}초" else "−${-d}초",
                if (!active) null else if (d > 1) "늦음" else if (d < -1) "빠름" else "딱 맞음",
                if (!active) P.Text else P.diffColor(ui.diffS),
                Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(16.dp))
        GhostBar(ui)
        Spacer(Modifier.height(20.dp))

        // 속도 프리셋
        Text("목표 속도", style = MaterialTheme.typography.labelMedium, color = P.Muted)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (k in PRESETS) SpeedPill(k, k == ui.targetKmh, Modifier.weight(1f)) { act.onSpeed(k) }
        }

        Spacer(Modifier.height(20.dp))
        MainButton(ui, act)
        if (ui.phase == Phase.PREPARING || ui.phase == Phase.IDLE) {
            Text(
                statusLine(ui), color = P.Muted, style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            )
        }
        if (ui.phase == Phase.FINISHED && ui.lastSavedId != null) {
            Spacer(Modifier.height(10.dp))
            OutlineButton("기록 자세히 보기 →") { act.onOpenRecord(ui.lastSavedId) }
        }

        if (ui.lastMessage.isNotBlank() && active) {
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(P.Surface).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("🔊", fontSize = 16.sp)
                Spacer(Modifier.width(8.dp))
                Text(ui.lastMessage, color = P.Text, style = MaterialTheme.typography.bodyMedium)
            }
        }

        if (ui.phase == Phase.IDLE || ui.phase == Phase.FINISHED) {
            Spacer(Modifier.height(20.dp))
            SettingsCard(ui, act)
        }
        if (!ui.batteryOk) {
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(P.Slow.copy(alpha = 0.12f))
                    .clickable { act.onBattery() }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("🔋", fontSize = 18.sp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("배터리 최적화 제외하기", color = P.Text, style = MaterialTheme.typography.titleMedium)
                    Text("화면이 꺼져도 기록이 안 끊기게 해요", color = P.Muted, style = MaterialTheme.typography.bodyMedium)
                }
                Text("›", color = P.Muted, fontSize = 24.sp)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun TopBar(ui: LiveUi) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("PACEMAKER", color = P.Text, fontWeight = FontWeight.Black, fontSize = 18.sp, letterSpacing = 2.sp)
        Spacer(Modifier.weight(1f))
        val acc = ui.gpsAccuracy
        val (dot, label) = when {
            ui.phase == Phase.IDLE -> P.Muted to "GPS 꺼짐"
            acc == null -> P.Slow to "GPS 찾는 중"
            acc <= 15f -> P.Good to "GPS ${acc.toInt()}m"
            else -> Color(0xFFFFC14D) to "GPS ${acc.toInt()}m"
        }
        Row(
            Modifier.clip(CircleShape).background(P.Surface).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(6.dp))
            Text(label, color = P.Text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun TargetChip(ui: LiveUi, modifier: Modifier) {
    Row(
        modifier.clip(CircleShape).background(P.Bg.copy(alpha = 0.82f)).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("목표", color = P.Muted, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.width(6.dp))
        Text(
            "${PaceEngine.fmtKmh(ui.targetKmh)} km/h · ${PaceEngine.paceStr(ui.targetKmh)}/km",
            color = P.Text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
fun StatTile(label: String, value: String, sub: String?, valueColor: Color, modifier: Modifier = Modifier) {
    Column(
        modifier.height(96.dp).clip(RoundedCornerShape(18.dp)).background(P.Surface).padding(vertical = 12.dp, horizontal = 12.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = P.Muted, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        Text(value, color = valueColor, fontSize = 24.sp, lineHeight = 26.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp, maxLines = 1)
        Text(sub ?: " ", color = P.Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

/** 목표 거리까지 진행 막대 + 목표 페이스대로 달리는 가상 주자(고스트) 위치 */
@Composable
private fun GhostBar(ui: LiveUi) {
    val goalM = ui.goalKm * 1000
    val me = (ui.distanceM / goalM).coerceIn(0.0, 1.0).toFloat()
    val ghostM = ui.distanceM + ui.diffS * ui.targetKmh / 3.6
    val ghost = (ghostM / goalM).coerceIn(0.0, 1.0).toFloat()
    val active = ui.phase == Phase.RUNNING || ui.phase == Phase.FINISHED
    val aheadM = (ui.distanceM - ghostM).roundToInt()

    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(P.Surface).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("목표 ${PaceEngine.fmtKmh(ui.goalKm)} km", color = P.Muted, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            val txt = when {
                !active -> "예상 ${PaceEngine.fmtClock(ui.goalKm * 3600 / ui.targetKmh)}"
                abs(aheadM) < 3 -> "페이스메이커와 나란히"
                aheadM > 0 -> "페이스메이커보다 ${aheadM}m 앞"
                else -> "페이스메이커보다 ${-aheadM}m 뒤"
            }
            Text(txt, color = if (active) P.diffColor(ui.diffS) else P.Text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))
        BoxWithConstraints(Modifier.fillMaxWidth().height(22.dp)) {
            val w = maxWidth
            Box(Modifier.align(Alignment.CenterStart).fillMaxWidth().height(8.dp).clip(CircleShape).background(P.Surface2))
            Box(Modifier.align(Alignment.CenterStart).width(w * me).height(8.dp).clip(CircleShape).background(P.Accent))
            if (active) {
                // 고스트
                Box(
                    Modifier.align(Alignment.CenterStart).offset(x = w * ghost - 9.dp)
                        .size(18.dp).clip(CircleShape).background(P.Bg).border(2.dp, P.Text, CircleShape),
                )
            }
            Box(
                Modifier.align(Alignment.CenterStart).offset(x = w * me - 11.dp)
                    .size(22.dp).clip(CircleShape).background(P.Accent).border(3.dp, P.Bg, CircleShape),
            )
        }
        if (active) {
            Spacer(Modifier.height(6.dp))
            Row {
                Legend(P.Accent, "나", filled = true)
                Spacer(Modifier.width(14.dp))
                Legend(P.Text, "페이스메이커 (목표 속도)", filled = false)
            }
        }
    }
}

@Composable
private fun Legend(c: Color, label: String, filled: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(10.dp).clip(CircleShape)
                .background(if (filled) c else Color.Transparent)
                .border(if (filled) 0.dp else 1.5.dp, c, CircleShape),
        )
        Spacer(Modifier.width(5.dp))
        Text(label, color = P.Muted, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun SpeedPill(kmh: Double, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) P.Accent else P.Surface, label = "pill")
    Column(
        modifier
            .height(76.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .border(1.dp, if (selected) P.Accent else P.Line, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            PaceEngine.fmtKmh(kmh), fontSize = 28.sp, fontWeight = FontWeight.Black,
            color = if (selected) Color.Black else P.Text,
        )
        Text(
            PaceEngine.paceStr(kmh), style = MaterialTheme.typography.labelSmall,
            color = if (selected) Color.Black.copy(alpha = 0.7f) else P.Muted,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MainButton(ui: LiveUi, act: RunActions) {
    val (label, sub, color) = when (ui.phase) {
        Phase.IDLE -> Triple("GPS 준비", "바톤 받기 전에 미리 눌러두세요", P.Ready)
        Phase.PREPARING -> Triple("출발!", "누르는 순간부터 기록 시작", P.Good)
        Phase.RUNNING -> Triple("달리는 중", "정지하려면 길게 누르세요", P.Surface2)
        Phase.FINISHED -> Triple("완주 🎉", "길게 누르면 종료", P.Surface2)
    }
    val textColor = if (ui.phase == Phase.PREPARING) Color.Black else Color.White
    Column(
        Modifier
            .fillMaxWidth()
            .height(92.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(color)
            .combinedClickable(onClick = act.onMain, onLongClick = act.onStop),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(label, color = textColor, fontSize = 28.sp, fontWeight = FontWeight.Black)
        Text(sub, color = textColor.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun OutlineButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(RoundedCornerShape(18.dp))
            .border(1.5.dp, P.Accent, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = P.Accent, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun SettingsCard(ui: LiveUi, act: RunActions) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(P.Surface).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("목표 거리", color = P.Text, style = MaterialTheme.typography.titleMedium)
                Text("0.5km 단위", color = P.Muted, style = MaterialTheme.typography.labelSmall)
            }
            StepButton("−") { act.onGoalStep(-0.5) }
            Text(
                "${PaceEngine.fmtKmh(ui.goalKm)} km", color = P.Text, fontSize = 20.sp, fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center, modifier = Modifier.width(92.dp),
            )
            StepButton("+") { act.onGoalStep(0.5) }
        }
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(P.Line))
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("조용히 모드", color = P.Text, style = MaterialTheme.typography.titleMedium)
                Text(
                    "목표 ±3% 안이면 200m 안내 생략\n(1km·남은 200m·완주는 항상 안내)",
                    color = P.Muted, style = MaterialTheme.typography.labelSmall,
                )
            }
            Switch(
                checked = ui.quietMode, onCheckedChange = act.onQuiet,
                colors = SwitchDefaults.colors(checkedTrackColor = P.Accent, checkedThumbColor = Color.White),
            )
        }
    }
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(P.Surface2).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = P.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
}

private fun statusLine(ui: LiveUi): String {
    val acc = ui.gpsAccuracy
    return when (ui.phase) {
        Phase.IDLE -> "하늘이 트인 곳에서 GPS를 먼저 잡아두면 정확해요"
        Phase.PREPARING -> when {
            acc == null -> "GPS 신호 찾는 중… 잡히면 음성으로 알려줘요"
            acc <= 15f -> "준비 완료! 바톤 받으면 출발 누르세요"
            else -> "정확도 ${acc.toInt()}m — 조금만 더 기다려요"
        }
        else -> ""
    }
}
