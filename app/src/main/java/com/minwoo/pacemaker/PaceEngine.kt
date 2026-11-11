package com.minwoo.pacemaker

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 안드로이드 의존성 없는 순수 계산 로직.
 * - GPS 점을 받아 거리 누적
 * - 목표 속도(km/h)를 거리 기준으로 적분해서 "목표대로 뛰었으면 걸렸을 시간"을 계산
 *   → 중간에 속도를 바꿔도(6/9/12/15, 나중엔 인터벌) 자연스럽게 이어짐
 * - 200m마다 구간 속도 안내, 1km마다 랩 안내, 결승 200m 전 안내, 완주 안내
 */
class PaceEngine(
    initialTargetKmh: Double,
    val goalM: Double,
    val announceEveryM: Double = 200.0,
    /** 목표 속도 대비 허용 오차 비율 (0.03 = ±3%) */
    val tolerance: Double = 0.03,
    var quietMode: Boolean = true,
) {
    var targetKmh: Double = initialTargetKmh
        private set

    var started = false
        private set
    var finished = false
        private set

    var distanceM = 0.0
        private set
    private var startMs = 0L
    private var nowMs = 0L
    /** 지금까지 달린 거리를 목표 속도로 달렸다면 걸렸을 시간(초) */
    private var targetElapsedS = 0.0

    private var lastLat = 0.0
    private var lastLon = 0.0
    private var lastFixMs = 0L
    private var hasLast = false
    private var anchorLat = 0.0
    private var anchorLon = 0.0
    private var anchorMs = 0L
    private var lastSpeed: Float? = null

    private var nextMarkM = announceEveryM
    private var lastMarkM = 0.0
    private var lastMarkS = 0.0
    private var lastKmS = 0.0

    /** 최근 속도 계산용 (시간ms, 누적거리m) */
    private val recent = ArrayDeque<Pair<Long, Double>>()

    val elapsedS: Double get() = if (started) (nowMs - startMs) / 1000.0 else 0.0

    /** + 면 목표보다 늦음, - 면 목표보다 빠름 (초) */
    val diffS: Double get() = elapsedS - targetElapsedS

    /** 최근 약 15초 평균 속도 km/h (데이터 부족하면 null) */
    val currentKmh: Double?
        get() {
            if (recent.size < 2) return null
            val (t0, d0) = recent.first()
            val (t1, d1) = recent.last()
            val dt = (t1 - t0) / 1000.0
            if (dt < 4.0) return null
            return (d1 - d0) / dt * 3.6
        }

    fun start(tMs: Long) {
        started = true
        finished = false
        startMs = tMs
        nowMs = tMs
        distanceM = 0.0
        targetElapsedS = 0.0
        nextMarkM = announceEveryM
        lastMarkM = 0.0
        lastMarkS = 0.0
        lastKmS = 0.0
        recent.clear()
        recent.addLast(tMs to 0.0)
        // 출발 직전 위치를 기준점으로 쓰되, 너무 오래된 점이면 버림
        if (hasLast && tMs - lastFixMs > 5000) hasLast = false
        if (hasLast) {
            anchorLat = lastLat; anchorLon = lastLon; anchorMs = tMs
            lastFixMs = tMs
        }
        lastSpeed = null
    }

    fun tick(tMs: Long) {
        if (started) nowMs = tMs
    }

    fun setTarget(kmh: Double): String {
        targetKmh = kmh
        return "목표 시속 ${fmtKmh(kmh)}"
    }

    /**
     * GPS 점 입력. 안내할 문장 목록을 돌려줌.
     */
    /**
     * GPS 점 입력. 안내할 문장 목록을 돌려줌.
     * speedMps: GPS 도플러 속도(있으면). 위치 차이보다 훨씬 덜 흔들려서 우선 사용.
     */
    fun onLocation(lat: Double, lon: Double, accuracyM: Float, tMs: Long, speedMps: Float? = null): List<String> {
        if (accuracyM > 25f) return emptyList()  // 부정확한 점 버림
        if (!started || finished) {
            // 출발 전에는 기준점만 갱신 (GPS 예열)
            lastLat = lat; lastLon = lon; lastFixMs = tMs; hasLast = true
            anchorLat = lat; anchorLon = lon; anchorMs = tMs
            lastSpeed = null
            return emptyList()
        }
        nowMs = maxOf(nowMs, tMs)
        if (!hasLast) {
            lastLat = lat; lastLon = lon; lastFixMs = tMs; hasLast = true
            anchorLat = lat; anchorLon = lon; anchorMs = tMs
            lastSpeed = speedMps
            return emptyList()
        }
        var dt = (tMs - lastFixMs) / 1000.0
        if (dt <= 0) return emptyList()

        val d: Double
        if (speedMps != null && dt <= 10.0) {
            // 도플러 속도 적분 (사다리꼴)
            val v0 = (lastSpeed ?: speedMps).toDouble()
            d = (v0 + speedMps) / 2.0 * dt
            anchorLat = lat; anchorLon = lon; anchorMs = tMs
        } else {
            // 속도 정보 없음: 기준점에서 10m 이상 움직였을 때만 거리 인정 (제자리 흔들림 제거)
            val fromAnchor = haversineM(anchorLat, anchorLon, lat, lon)
            lastFixMs = tMs
            lastSpeed = null
            if (fromAnchor < 10.0) return emptyList()
            dt = ((tMs - anchorMs) / 1000.0).coerceAtLeast(0.001)
            d = fromAnchor
            anchorLat = lat; anchorLon = lon; anchorMs = tMs
        }
        if (d / dt > 8.5) {
            // 시속 30km 넘는 순간이동 = GPS 튐. 무시.
            lastFixMs = tMs
            return emptyList()
        }
        lastLat = lat; lastLon = lon; lastFixMs = tMs; lastSpeed = speedMps

        val prevDist = distanceM
        val prevS = (tMs - startMs) / 1000.0 - dt
        distanceM += d
        targetElapsedS += d * 3.6 / targetKmh
        recent.addLast(tMs to distanceM)
        while (recent.size > 2 && tMs - recent.first().first > 15000) recent.removeFirst()

        val out = mutableListOf<String>()
        val curS = (tMs - startMs) / 1000.0

        while (distanceM >= nextMarkM && !finished) {
            val mark = nextMarkM
            // 마크를 지난 시각을 선형 보간
            val frac = if (distanceM - prevDist > 0) (mark - prevDist) / (distanceM - prevDist) else 1.0
            val markS = prevS + dt * frac.coerceIn(0.0, 1.0)
            val msg = buildMarkMessage(mark, markS)
            if (msg.isNotBlank()) out += msg
            lastMarkM = mark
            lastMarkS = markS
            nextMarkM += announceEveryM
        }
        if (!finished && distanceM >= goalM) {
            finished = true
            val markS = curS
            out += "완주! ${fmtTime(markS)}. ${fmtDiff(markS - targetElapsedS)}"
        }
        return out
    }

    private fun buildMarkMessage(mark: Double, markS: Double): String {
        if (mark >= goalM) return "" // 완주 문장은 따로
        val segS = markS - lastMarkS
        val segKmh = if (segS > 0) (mark - lastMarkM) / segS * 3.6 else targetKmh
        val ratio = (segKmh - targetKmh) / targetKmh
        val advice = when {
            ratio > tolerance -> "빨라요, 조금 천천히"
            ratio < -tolerance -> "느려요, 조금 더"
            else -> null
        }
        val isKm = (mark % 1000.0) < 0.5
        val remaining = goalM - mark
        val parts = mutableListOf<String>()
        if (isKm) {
            val lap = markS - lastKmS
            lastKmS = markS
            parts += "${(mark / 1000).roundToInt()}킬로, 랩 ${fmtTime(lap)}"
            parts += fmtDiff(markS - targetAt(mark, markS))
            if (advice != null) parts += "시속 ${fmtKmh(segKmh)}, $advice"
        } else {
            if (advice == null && quietMode && !(remaining in 1.0..200.5)) return ""
            parts += "${meterWord(mark % 1000.0)}, 시속 ${fmtKmh(segKmh)}"
            parts += advice ?: "좋아요"
        }
        if (remaining in 1.0..200.5) parts += "${remaining.roundToInt()}미터 남았어요"
        return parts.joinToString(". ")
    }

    /** 마크 시점의 목표 경과시간 근사 (현재 누적값에서 마크 이후 거리만큼 뺌) */
    private fun targetAt(mark: Double, @Suppress("UNUSED_PARAMETER") markS: Double): Double =
        targetElapsedS - (distanceM - mark) * 3.6 / targetKmh

    companion object {
        fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6371000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
            return 2 * r * atan2(sqrt(a), sqrt(1 - a))
        }

        fun fmtKmh(v: Double): String {
            val r = (v * 10).roundToInt() / 10.0
            return if (r == r.toInt().toDouble()) r.toInt().toString() else r.toString()
        }

        fun fmtTime(s: Double): String {
            val t = s.roundToInt()
            val m = t / 60
            val sec = t % 60
            return when {
                m > 0 && sec == 0 -> "${m}분"
                m > 0 -> "${m}분 ${sec}초"
                else -> "${sec}초"
            }
        }

        fun fmtClock(s: Double): String {
            val t = s.roundToInt().coerceAtLeast(0)
            return "%d:%02d".format(t / 60, t % 60)
        }

        fun fmtDiff(diff: Double): String {
            val d = diff.roundToInt()
            return when {
                abs(d) <= 1 -> "목표와 같아요"
                d > 0 -> "목표보다 ${d}초 늦어요"
                else -> "목표보다 ${-d}초 빨라요"
            }
        }

        private fun meterWord(m: Double): String = when (m.roundToInt()) {
            200 -> "이백"
            400 -> "사백"
            600 -> "육백"
            800 -> "팔백"
            else -> "${m.roundToInt()}미터"
        }

        /** km/h → 분:초/km */
        fun paceStr(kmh: Double): String = fmtClock(3600.0 / kmh)
    }
}
