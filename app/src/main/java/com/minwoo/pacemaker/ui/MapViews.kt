package com.minwoo.pacemaker.ui

import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import com.minwoo.pacemaker.TrackPoint
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay
import kotlin.math.cos
import kotlin.math.max

/** 테스트(스크린샷)에서는 지도 타일 대신 경로만 그림 */
object UiFlags {
    @Volatile var mapTiles = true
}

/**
 * 경로 지도.
 * live = true: 달리는 중 — 현재 위치를 따라감
 * live = false: 기록 보기 — 경로 전체가 보이게 맞춤, 속도별 색
 */
@Composable
fun RouteMap(
    points: List<TrackPoint>,
    live: Boolean,
    modifier: Modifier = Modifier,
    centerLat: Double? = null,
    centerLon: Double? = null,
) {
    if (!UiFlags.mapTiles) {
        RouteSketch(points, colored = !live, modifier = modifier.background(P.Surface2))
        return
    }
    val state = remember { MapHolder() }
    DisposableEffect(Unit) {
        onDispose { state.map?.onDetach() }
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                overlayManager.tilesOverlay.setColorFilter(TilesOverlay.INVERT_COLORS)
                overlayManager.tilesOverlay.loadingBackgroundColor = P.Surface2.toArgb()
                overlayManager.tilesOverlay.loadingLineColor = P.Surface2.toArgb()
                controller.setZoom(17.0)
                isTilesScaledToDpi = true
                state.map = this
            }
        },
        update = { map -> state.render(map, points, live, centerLat, centerLon) },
    )
}

private class MapHolder {
    var map: MapView? = null
    var renderedSize = -1
    var fitted = false
    var marker: Marker? = null

    fun render(map: MapView, points: List<TrackPoint>, live: Boolean, cLat: Double?, cLon: Double?) {
        if (points.size == renderedSize && points.isNotEmpty()) return
        renderedSize = points.size
        map.overlays.removeAll { it is Polyline || it is Marker }

        if (points.isEmpty()) {
            if (cLat != null && cLon != null) {
                map.controller.setCenter(GeoPoint(cLat, cLon))
                addDot(map, GeoPoint(cLat, cLon))
            }
            map.invalidate()
            return
        }
        val geo = points.map { GeoPoint(it.lat, it.lon) }
        if (live) {
            map.overlays += Polyline(map).apply {
                setPoints(geo)
                outlinePaint.color = P.Accent.toArgb()
                outlinePaint.strokeWidth = 14f
                outlinePaint.strokeCap = Paint.Cap.ROUND
                outlinePaint.strokeJoin = Paint.Join.ROUND
                isGeodesic = false
            }
            addDot(map, geo.last())
            map.controller.setCenter(geo.last())
        } else {
            // 속도별 색 구간
            var start = 0
            for (i in 1..points.size) {
                val c0 = P.speedColor(points[start].kmh, points[start].targetKmh)
                val end = i == points.size ||
                    P.speedColor(points[i].kmh, points[i].targetKmh) != c0
                if (end) {
                    val seg = geo.subList(start, minOf(i + 1, geo.size))
                    if (seg.size >= 2) {
                        map.overlays += Polyline(map).apply {
                            setPoints(seg)
                            outlinePaint.color = c0.toArgb()
                            outlinePaint.strokeWidth = 12f
                            outlinePaint.strokeCap = Paint.Cap.ROUND
                            outlinePaint.strokeJoin = Paint.Join.ROUND
                        }
                    }
                    start = i
                }
            }
            if (!fitted) {
                fitted = true
                val box = BoundingBox.fromGeoPoints(geo)
                val padded = box.increaseByScale(1.25f)
                if (map.width > 0) map.zoomToBoundingBox(padded, false)
                else map.addOnFirstLayoutListener { _, _, _, _, _ -> map.zoomToBoundingBox(padded, false) }
            }
        }
        map.invalidate()
    }

    private fun addDot(map: MapView, p: GeoPoint) {
        val m = Marker(map)
        m.position = p
        m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        m.icon = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(P.Accent.toArgb())
            setStroke(6, android.graphics.Color.WHITE)
            setSize(44, 44)
        }
        m.setInfoWindow(null)
        map.overlays += m
        marker = m
    }
}

/** 지도 없이 경로 모양만 그림 (기록 목록 썸네일, 테스트용) */
@Composable
fun RouteSketch(points: List<TrackPoint>, colored: Boolean, modifier: Modifier = Modifier, strokeDp: Float = 4f) {
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            if (points.size < 2) return@Canvas
            val lat0 = points.first().lat
            val kx = cos(Math.toRadians(lat0))
            val xs = points.map { it.lon * kx }
            val ys = points.map { it.lat }
            val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
            val pad = size.minDimension * 0.12f
            val w = size.width - pad * 2
            val h = size.height - pad * 2
            val span = max(maxX - minX, maxY - minY).takeIf { it > 0 } ?: 1e-9
            val scale = minOf(w / span, h / span).toFloat()
            val offX = pad + (w - ((maxX - minX) * scale).toFloat()) / 2
            val offY = pad + (h - ((maxY - minY) * scale).toFloat()) / 2
            fun pt(i: Int) = Offset(
                offX + ((xs[i] - minX) * scale).toFloat(),
                offY + ((maxY - ys[i]) * scale).toFloat(),
            )
            val sw = strokeDp * density
            for (i in 1 until points.size) {
                val c = if (colored) P.speedColor(points[i].kmh, points[i].targetKmh) else P.Accent
                drawLine(c, pt(i - 1), pt(i), strokeWidth = sw, cap = StrokeCap.Round)
            }
            drawCircle(P.Text, radius = sw * 1.4f, center = pt(0))
            drawCircle(P.Accent, radius = sw * 1.6f, center = pt(points.size - 1))
            drawCircle(P.Bg, radius = sw * 1.6f, center = pt(points.size - 1), style = Stroke(sw * 0.6f))
        }
    }
}
