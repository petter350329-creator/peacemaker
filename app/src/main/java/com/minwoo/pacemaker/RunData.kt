package com.minwoo.pacemaker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 경로 한 점. s = 출발 후 경과 초 */
data class TrackPoint(
    val s: Double,
    val lat: Double,
    val lon: Double,
    val distM: Double,
    val kmh: Double,
    val targetKmh: Double,
)

data class Split(
    val index: Int,        // 1부터
    val distM: Double,     // 이 구간 거리 (마지막 구간은 1000 미만일 수 있음)
    val timeS: Double,
    val targetKmh: Double, // 구간 중간 시점의 목표 속도
) {
    val kmh: Double get() = if (timeS > 0) distM / timeS * 3.6 else 0.0
}

data class RunRecord(
    val id: Long,              // 출발 시각 epoch ms
    val goalM: Double,
    val distanceM: Double,
    val durationS: Double,
    val diffS: Double,         // + 늦음 / - 빠름
    val initialTargetKmh: Double,
    val finished: Boolean,
    val points: List<TrackPoint>,
) {
    val avgKmh: Double get() = if (durationS > 0) distanceM / durationS * 3.6 else 0.0
    val maxKmh: Double get() = points.maxOfOrNull { it.kmh } ?: 0.0

    fun splits(): List<Split> {
        val out = mutableListOf<Split>()
        if (points.size < 2) return out
        var nextKm = 1000.0
        var lastS = 0.0
        var lastD = 0.0
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            while (b.distM >= nextKm && nextKm <= distanceM + 0.5) {
                val f = if (b.distM > a.distM) (nextKm - a.distM) / (b.distM - a.distM) else 1.0
                val t = a.s + (b.s - a.s) * f
                out += Split(out.size + 1, nextKm - lastD, t - lastS, b.targetKmh)
                lastS = t
                lastD = nextKm
                nextKm += 1000.0
            }
        }
        val rest = distanceM - lastD
        if (rest > 30) out += Split(out.size + 1, rest, durationS - lastS, points.last().targetKmh)
        return out
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("v", 1)
        put("id", id)
        put("goalM", goalM)
        put("distanceM", distanceM)
        put("durationS", durationS)
        put("diffS", diffS)
        put("initialTargetKmh", initialTargetKmh)
        put("finished", finished)
        val arr = JSONArray()
        for (p in points) {
            arr.put(JSONArray().apply {
                put(r(p.s, 1)); put(r(p.lat, 6)); put(r(p.lon, 6)); put(r(p.distM, 1)); put(r(p.kmh, 2)); put(p.targetKmh)
            })
        }
        put("points", arr)
    }

    companion object {
        private fun r(v: Double, digits: Int): Double {
            var m = 1.0
            repeat(digits) { m *= 10 }
            return Math.round(v * m) / m
        }

        fun fromJson(o: JSONObject): RunRecord {
            val arr = o.getJSONArray("points")
            val pts = ArrayList<TrackPoint>(arr.length())
            for (i in 0 until arr.length()) {
                val p = arr.getJSONArray(i)
                pts += TrackPoint(p.getDouble(0), p.getDouble(1), p.getDouble(2), p.getDouble(3), p.getDouble(4), p.getDouble(5))
            }
            return RunRecord(
                id = o.getLong("id"),
                goalM = o.getDouble("goalM"),
                distanceM = o.getDouble("distanceM"),
                durationS = o.getDouble("durationS"),
                diffS = o.getDouble("diffS"),
                initialTargetKmh = o.getDouble("initialTargetKmh"),
                finished = o.getBoolean("finished"),
                points = pts,
            )
        }
    }
}

/** 기록은 앱 내부 저장소 runs/ 폴더에 1회 = 1파일(JSON)로 저장 */
object RunRepository {
    private fun dir(ctx: Context) = File(ctx.filesDir, "runs").apply { mkdirs() }

    fun save(ctx: Context, r: RunRecord) {
        val f = File(dir(ctx), "${r.id}.json")
        val tmp = File(dir(ctx), "${r.id}.json.tmp")
        tmp.writeText(r.toJson().toString())
        tmp.renameTo(f)
    }

    fun list(ctx: Context): List<RunRecord> =
        dir(ctx).listFiles { f -> f.name.endsWith(".json") }
            ?.mapNotNull { runCatching { RunRecord.fromJson(JSONObject(it.readText())) }.getOrNull() }
            ?.sortedByDescending { it.id }
            ?: emptyList()

    fun load(ctx: Context, id: Long): RunRecord? {
        val f = File(dir(ctx), "$id.json")
        if (!f.exists()) return null
        return runCatching { RunRecord.fromJson(JSONObject(f.readText())) }.getOrNull()
    }

    fun delete(ctx: Context, id: Long) {
        File(dir(ctx), "$id.json").delete()
    }
}
