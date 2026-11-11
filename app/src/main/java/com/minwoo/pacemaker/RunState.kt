package com.minwoo.pacemaker

/** 화면과 서비스가 같이 보는 상태 */
object RunState {
    enum class Phase { IDLE, PREPARING, RUNNING, FINISHED }

    @Volatile var phase = Phase.IDLE
    @Volatile var targetKmh = 9.0
    @Volatile var goalKm = 4.0
    @Volatile var quietMode = true
    @Volatile var gpsAccuracy: Float? = null
    @Volatile var engine: PaceEngine? = null
    @Volatile var lastMessage: String = ""
    @Volatile var startMs: Long = 0L
    @Volatile var initialTargetKmh: Double = 9.0
    /** 현재 위치 (출발 전 지도 중심용) */
    @Volatile var lastLat: Double? = null
    @Volatile var lastLon: Double? = null
    /** 방금 저장된 기록 id (완주/정지 후 요약 화면으로 보내기용) */
    @Volatile var lastSavedId: Long? = null

    private val trackLock = Any()
    private val track = ArrayList<TrackPoint>()

    fun addPoint(p: TrackPoint) = synchronized(trackLock) { track += p }
    fun trackSnapshot(): List<TrackPoint> = synchronized(trackLock) { ArrayList(track) }
    fun trackSize(): Int = synchronized(trackLock) { track.size }
    fun clearTrack() = synchronized(trackLock) { track.clear() }

    val PRESETS = doubleArrayOf(6.0, 9.0, 12.0, 15.0)
}
