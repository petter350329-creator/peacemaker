package com.minwoo.pacemaker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.minwoo.pacemaker.PaceEngine
import com.minwoo.pacemaker.RunRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val dateFmt = SimpleDateFormat("M월 d일 (E) a h:mm", Locale.KOREAN)

private fun diffText(d: Double): String {
    val r = d.roundToInt()
    return when {
        abs(r) <= 1 -> "목표와 같음"
        r > 0 -> "목표보다 ${r}초 늦음"
        else -> "목표보다 ${-r}초 빠름"
    }
}

// ───────────────────────── 기록 목록 ─────────────────────────

@Composable
fun HistoryScreen(records: List<RunRecord>, onOpen: (Long) -> Unit, modifier: Modifier = Modifier) {
    if (records.isEmpty()) {
        Column(modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🏃", fontSize = 48.sp)
            Spacer(Modifier.height(12.dp))
            Text("아직 기록이 없어요", color = P.Text, style = MaterialTheme.typography.titleLarge)
            Text("달리고 나면 여기에 쌓여요", color = P.Muted, style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    val totalKm = records.sumOf { it.distanceM } / 1000
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("기록", color = P.Text, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(4.dp))
            Text("총 ${records.size}회 · ${"%.1f".format(totalKm)} km", color = P.Muted, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
        }
        items(records, key = { it.id }) { r -> RecordCard(r) { onOpen(r.id) } }
    }
}

@Composable
private fun RecordCard(r: RunRecord, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(P.Surface).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RouteSketch(
            r.points, colored = true,
            modifier = Modifier.size(76.dp).clip(RoundedCornerShape(14.dp)).background(P.Surface2),
            strokeDp = 2.5f,
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(dateFmt.format(Date(r.id)), color = P.Muted, style = MaterialTheme.typography.labelMedium)
            Row(verticalAlignment = Alignment.Bottom) {
                Text("%.2f".format(r.distanceM / 1000), color = P.Text, fontSize = 28.sp, fontWeight = FontWeight.Black)
                Text(" km", color = P.Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 4.dp))
                Spacer(Modifier.width(12.dp))
                Text(PaceEngine.fmtClock(r.durationS), color = P.Text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 3.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("평균 ${PaceEngine.fmtKmh(r.avgKmh)} km/h", color = P.Muted, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(6.dp).clip(CircleShape).background(P.diffColor(r.diffS)))
                Spacer(Modifier.width(4.dp))
                Text(diffText(r.diffS), color = P.diffColor(r.diffS), style = MaterialTheme.typography.labelMedium)
            }
        }
        if (!r.finished) {
            Text("중단", color = P.Muted, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.clip(CircleShape).background(P.Surface2).padding(horizontal = 8.dp, vertical = 3.dp))
        }
    }
}

// ───────────────────────── 운동 후 요약 ─────────────────────────

@Composable
fun SummaryScreen(r: RunRecord, onBack: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    var confirm by remember { mutableStateOf(false) }
    val splits = r.splits()
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // 상단 바
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                Text("‹", color = P.Text, fontSize = 32.sp)
            }
            Text(dateFmt.format(Date(r.id)), color = P.Text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { confirm = true }) { Text("삭제", color = P.Muted) }
        }

        // 지도
        Box(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(260.dp)
                .clip(RoundedCornerShape(24.dp)).background(P.Surface2),
        ) {
            RouteMap(r.points, live = false, modifier = Modifier.fillMaxSize())
            Row(
                Modifier.align(Alignment.BottomStart).padding(10.dp).clip(CircleShape)
                    .background(P.Bg.copy(alpha = 0.82f)).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Legend2(P.Fast, "빠름"); Spacer(Modifier.width(10.dp))
                Legend2(P.Good, "목표"); Spacer(Modifier.width(10.dp))
                Legend2(P.Slow, "느림")
            }
        }

        Column(Modifier.padding(16.dp)) {
            // 히어로 숫자
            Row(verticalAlignment = Alignment.Bottom) {
                Text("%.2f".format(r.distanceM / 1000), style = MaterialTheme.typography.displayMedium, color = P.Text)
                Text(" km", color = P.Muted, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp))
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Text(PaceEngine.fmtClock(r.durationS), color = P.Text, fontSize = 32.sp, fontWeight = FontWeight.Black)
                    Text(
                        if (r.finished) "완주" else "중단 (${PaceEngine.fmtKmh(r.goalM / 1000)}km 중)",
                        color = P.Muted, style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(P.diffColor(r.diffS).copy(alpha = 0.14f)).padding(12.dp),
            ) {
                Text(
                    "🎯 " + diffText(r.diffS) + "  ·  목표 시속 ${PaceEngine.fmtKmh(r.initialTargetKmh)}",
                    color = P.diffColor(r.diffS), style = MaterialTheme.typography.titleMedium,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("평균 시속", PaceEngine.fmtKmh(r.avgKmh), "km/h", P.Text, Modifier.weight(1f))
                StatTile("평균 페이스", if (r.avgKmh > 0) PaceEngine.paceStr(r.avgKmh) else "—", "/km", P.Text, Modifier.weight(1f))
                StatTile("최고 시속", PaceEngine.fmtKmh(r.maxKmh), "15초 평균", P.Text, Modifier.weight(1f))
            }

            Spacer(Modifier.height(24.dp))
            SectionTitle("속도 변화", "점선 = 목표 속도")
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(P.Surface).padding(14.dp)) {
                SpeedChart(r.points, Modifier.fillMaxWidth().height(150.dp))
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)) {
                Text("0", color = P.Muted, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.weight(1f))
                Text("%.1f km".format(r.distanceM / 1000), color = P.Muted, style = MaterialTheme.typography.labelSmall)
            }

            Spacer(Modifier.height(20.dp))
            SectionTitle("구간 기록", "1km마다")
            Spacer(Modifier.height(10.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(P.Surface).padding(vertical = 6.dp)) {
                val best = splits.filter { it.distM >= 999 }.maxByOrNull { it.kmh }
                val maxK = splits.maxOfOrNull { it.kmh } ?: 1.0
                for (s in splits) SplitRow(s, maxK, s == best)
                if (splits.isEmpty()) Text("구간 기록이 없어요", color = P.Muted, modifier = Modifier.padding(14.dp))
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            confirmButton = { TextButton(onClick = { confirm = false; onDelete() }) { Text("삭제", color = P.Slow) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("취소", color = P.Text) } },
            title = { Text("이 기록을 삭제할까요?") },
            text = { Text("삭제하면 되돌릴 수 없어요.") },
            containerColor = P.Surface,
        )
    }
}

@Composable
private fun SectionTitle(t: String, sub: String) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(t, color = P.Text, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.width(8.dp))
        Text(sub, color = P.Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 3.dp))
    }
}

@Composable
private fun Legend2(c: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 12.dp, height = 4.dp).clip(CircleShape).background(c))
        Spacer(Modifier.width(4.dp))
        Text(label, color = P.Text, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun SplitRow(s: com.minwoo.pacemaker.Split, maxKmh: Double, best: Boolean) {
    val c = P.speedColor(s.kmh, s.targetKmh)
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (s.distM >= 999) "${s.index}km" else "+${s.distM.roundToInt()}m",
            color = P.Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(52.dp),
        )
        Text(
            PaceEngine.fmtClock(if (s.distM >= 999) s.timeS else s.timeS),
            color = P.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(58.dp),
        )
        BoxWithConstraints(Modifier.weight(1f).height(10.dp)) {
            val frac = (s.kmh / maxKmh).coerceIn(0.05, 1.0).toFloat()
            Box(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(P.Surface2))
            Box(Modifier.width(maxWidth * frac).height(10.dp).clip(CircleShape).background(c))
        }
        Spacer(Modifier.width(10.dp))
        Text(
            "${PaceEngine.fmtKmh(s.kmh)}" + if (best) " ⚡" else "",
            color = c, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
            modifier = Modifier.width(52.dp),
        )
    }
}
