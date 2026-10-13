package dev.shadow.booxbacklight

import org.json.JSONObject

/**
 * Factored learning model (agreed design, 2026-09-08):
 *  - Brightness ≈ f(lux) only          → per-lux-bucket EWMA (~9 log buckets)
 *  - Warmth ≈ f(hour) + small lux term → per-hour-bucket EWMA (6 × 4h) + lux modifier
 *  - Recency authority: user's deliberate set = immediate bucket value (α=1)
 *  - Fallback: exact bucket → neighbouring bucket blend → prior
 *  - Brightness lookups INTERPOLATE between bucket anchors in log-lux space
 *    (2026-10-13): bucket cells are curve control points, not step values —
 *    fixes visible "cliffs" when light drifts across a bucket boundary.
 * Native scale: 0..32 for both channels (calibrated 2026-09-18).
 */
object LightModel {

    const val NATIVE_MAX = 32
    const val DEADBAND_STEPS = 1          // never apply unless |learned-current| > this (32-step scale)

    // Log-scale lux bucket upper bounds (9 buckets: <2, <5, <10, <25, <60, <150, <400, <1000, rest)
    val LUX_BOUNDS = floatArrayOf(2f, 5f, 10f, 25f, 60f, 150f, 400f, 1000f)

    fun luxBucket(lux: Float): Int {
        for (i in LUX_BOUNDS.indices) if (lux < LUX_BOUNDS[i]) return i
        return LUX_BOUNDS.size
    }

    /**
     * Interpolation anchor lux per bucket = geometric centre of the bucket range
     * (buckets: <2, <5, <10, <25, <60, <150, <400, <1000, rest → 9 anchors).
     * Curve is sampled linearly in log(lux) between anchors; clamped outside the ends.
     */
    val BRIGHT_ANCHORS = floatArrayOf(1f, 3.16f, 7.07f, 15.8f, 38.7f, 94.9f, 245f, 632f, 1400f)

    fun hourBucket(epochHour: Int): Int = (epochHour / 4) % 6

    // Cold-start priors (native 0..32). Conservative indoor defaults; α decays fast
    // once real observations land.
    private const val PRIOR_BRIGHTNESS = 20f
    private const val PRIOR_WARMTH = 20f

    /** One channel's learned value + observation count for a cell. */
    class Cell(var value: Float, var count: Int)

    class State {
        val brightness = Array(9) { Cell(PRIOR_BRIGHTNESS, 0) }
        val warmth = Array(6) { Cell(PRIOR_WARMTH, 0) }
        // Lux modifier for warmth: warmth seen in dark rooms tends higher (circadian-ish)
        val warmthDarkAdj = Cell(0f, 0)   // applied when lux < 10

        fun learn(cell: Cell, observation: Float, alpha: Float) {
            cell.value = if (cell.count == 0) observation
                         else cell.value + alpha * (observation - cell.value)
            cell.count++
        }
    }

    /** Record a deliberate user adjustment (recency authority — this IS the target now). */
    fun onUserAdjust(state: State, lux: Float?, hour: Int, nativeBrightness: Int?, nativeWarmth: Int?) {
        nativeBrightness?.let {
            state.learn(state.brightness[lux?.let { luxBucket(it) } ?: 4], it.toFloat(), 1f)
        }
        nativeWarmth?.let {
            val cell = state.warmth[hourBucket(hour)]
            state.learn(cell, it.toFloat(), 1f)
            if (lux != null && lux < 10f) state.learn(state.warmthDarkAdj, it.toFloat(), 0.5f)
        }
    }

    /** Predict target values. Deadband applied by caller against current values. */
    fun predict(state: State, lux: Float, hour: Int): Pair<Int, Int> {
        val b = interpolatedBrightness(state.brightness, lux)
        val wBase = bucketValue(state.warmth, hourBucket(hour))
        val darkAdj = if (lux < 10f) state.warmthDarkAdj.value else 0f
        val w = (wBase + darkAdj * 0.3f).coerceIn(0f, NATIVE_MAX.toFloat())
        return b.toInt().coerceIn(0, NATIVE_MAX) to w.toInt().coerceIn(0, NATIVE_MAX)
    }

    private fun bucketValue(cells: Array<Cell>, idx: Int): Float {
        val c = cells[idx]
        if (c.count > 0) return c.value
        // neighbour blend
        val left = cells.getOrNull(idx - 1)
        val right = cells.getOrNull(idx + 1)
        return when {
            left != null && left.count > 0 && right != null && right.count > 0 -> (left.value + right.value) / 2f
            left != null && left.count > 0 -> left.value
            right != null && right.count > 0 -> right.value
            else -> c.value // prior
        }
    }

    /**
     * Continuous brightness lookup: piecewise-linear in log(lux) between the anchor
     * cells. Cold cells fall back via bucketValue (neighbour blend), so the curve
     * stays finite even early in learning. A truly big light change still lands
     * immediately on the right curve point — we only removed the boundary cliffs.
     */
    fun interpolatedBrightness(cells: Array<Cell>, lux: Float): Float {
        val a = BRIGHT_ANCHORS
        if (lux <= a.first()) return bucketValue(cells, 0)
        if (lux >= a.last()) return bucketValue(cells, a.size - 1)
        var i = 0
        while (i < a.size - 1 && a[i + 1] < lux) i++
        val lo = a[i]; val hi = a[i + 1]
        val t = (Math.log(lux.toDouble()) - Math.log(lo.toDouble())) /
                (Math.log(hi.toDouble()) - Math.log(lo.toDouble()))
        val v0 = bucketValue(cells, i)
        val v1 = bucketValue(cells, i + 1)
        return v0 + (t.toFloat() * (v1 - v0))
    }

    // --- persistence ---
    fun toJSON(s: State): JSONObject {
        val o = JSONObject()
        o.put("brightness", JSONArray(s.brightness) { it.value to it.count })
        o.put("warmth", JSONArray(s.warmth) { it.value to it.count })
        o.put("warmthDarkAdj", JSONArray(arrayOf(s.warmthDarkAdj)) { it.value to it.count })
        return o
    }

    private fun JSONArray(cells: Array<Cell>, f: (Cell) -> Pair<Float, Int>): org.json.JSONArray {
        val a = org.json.JSONArray()
        for (c in cells) a.put(JSONObject().put("v", f(c).first).put("n", f(c).second))
        return a
    }

    fun fromJSON(o: JSONObject?): State {
        val s = State()
        if (o == null) return s
        fun read(name: String, cells: Array<Cell>) {
            val a = o.optJSONArray(name) ?: return
            for (i in 0 until minOf(a.length(), cells.size)) {
                cells[i].value = a.getJSONObject(i).optDouble("v", cells[i].value.toDouble()).toFloat()
                cells[i].count = a.getJSONObject(i).optInt("n", 0)
            }
        }
        read("brightness", s.brightness)
        read("warmth", s.warmth)
        read("warmthDarkAdj", arrayOf(s.warmthDarkAdj))
        return s
    }
}
