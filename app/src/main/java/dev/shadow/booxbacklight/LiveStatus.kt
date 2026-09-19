package dev.shadow.booxbacklight

/**
 * Process-local live status bus. LearnService writes; MainActivity polls.
 * Times use SystemClock.elapsedRealtime() so sleep doesn't inflate ages.
 */
object LiveStatus {
    @Volatile var autoEnabled: Boolean = true
    @Volatile var lastLux: Float? = null
    @Volatile var lastLuxBucket: Int = -1
    @Volatile var pendingBucket: Int = -1          // bounce-hysteresis candidate
    @Volatile var pendingSinceElapsedMs: Long = 0L
    @Volatile var lastUserTouchElapsedMs: Long = 0L   // 0 = never
    @Volatile var echoClearElapsedMs: Long = 0L       // 0 = none
    @Volatile var lastEvent: String = "—"
    @Volatile var lastEventElapsedMs: Long = 0L
    @Volatile var serviceStartedElapsedMs: Long = 0L
    @Volatile var state: LightModel.State? = null     // shared reference, same process

    fun age(ms: Long): Long = if (ms <= 0L) -1 else android.os.SystemClock.elapsedRealtime() - ms
}
