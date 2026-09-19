package dev.shadow.booxbacklight

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Live status dashboard. Polls LiveStatus (written by LearnService) every 500ms.
 * Shows: lux + bucket, current vs learned brightness/warmth, gate statuses
 * (user-quiet cooldown, echo suppression, bounce hysteresis), last event,
 * model confidence, and Auto toggle / Apply-now buttons.
 */
class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            statusView.text = render()
            handler.postDelayed(this, 500)
        }
    }

    private fun ctm(key: String): Int = Settings.System.getInt(contentResolver, key, -1)

    private fun fmtSecs(ms: Long): String {
        val s = ms / 1000
        return if (s >= 60) "${s / 60}m ${s % 60}s" else "${s}s"
    }
    private fun fmtAge(elapsedMs: Long): String {
        val age = LiveStatus.age(elapsedMs)
        return if (age < 0) "never" else "${fmtSecs(age)} ago"
    }

    private fun bucketLabel(idx: Int): String {
        if (idx < 0) return "?"
        val lo = if (idx == 0) "0" else LightModel.LUX_BOUNDS[idx - 1].toInt().toString()
        val hi = if (idx >= LightModel.LUX_BOUNDS.size) "∞" else LightModel.LUX_BOUNDS[idx].toInt().toString()
        return "B$idx ($lo–$hi lx)"
    }
    private fun hourLabel(idx: Int): String = "H$idx (${idx * 4}–${idx * 4 + 3}h)"

    private fun render(): String {
        val sb = StringBuilder()
        val lux = LiveStatus.lastLux
        val bucket = LiveStatus.lastLuxBucket
        val nowHour = ZonedDateTime.now(ZoneId.systemDefault()).hour

        sb.append("Auto: ${if (LiveStatus.autoEnabled) "ON ✅" else "OFF ⏸"}\n")
        sb.append("Uptime: ${fmtAge(LiveStatus.serviceStartedElapsedMs).removeSuffix(" ago")}\n\n")

        // --- light ---
        sb.append("── LIGHT ──────────────\n")
        sb.append("Lux: ${lux?.let { String.format("%.0f", it) } ?: "—"}")
        if (bucket >= 0) sb.append("  →  ${bucketLabel(bucket)}")
        sb.append("\n")
        val cb = ctm("screen_ctm_brightness")
        val cw = ctm("screen_ctm_temperature")
        fun gg(n: Int) = Math.ceil(n * 100.0 / LightModel.NATIVE_MAX).toInt()
        sb.append("Current: B $cb/32 (gg ${gg(cb)}), W $cw/32 (gg ${gg(cw)})\n\n")

        // --- learned targets ---
        sb.append("── LEARNED ────────────\n")
        val st = LiveStatus.state
        if (st != null && lux != null) {
            val (lb, lw) = LightModel.predict(st, lux, nowHour)
            sb.append("Target: B $lb, W $lw   (deadband ±${LightModel.DEADBAND_STEPS})\n")
            val bDelta = Math.abs(lb - cb); val wDelta = Math.abs(lw - cw)
            sb.append("Δ from current: B $bDelta, W $wDelta ${if (bDelta > LightModel.DEADBAND_STEPS || wDelta > LightModel.DEADBAND_STEPS) "→ would apply" else "→ in deadband"}\n")
            val bCell = st.brightness[bucket.coerceIn(0, 8)]
            val wCell = st.warmth[LightModel.hourBucket(nowHour)]
            sb.append("Confidence: lux ${bucketLabel(bucket)} n=${bCell.count}" +
                      (if (bCell.count == 0) " (prior)" else "") + "\n")
            sb.append("            hour ${hourLabel(LightModel.hourBucket(nowHour))} n=${wCell.count}" +
                      (if (wCell.count == 0) " (prior)" else "") + "\n")
        } else {
            sb.append("Waiting for lux reading / model…\n")
        }
        sb.append("\n")

        // --- gates ---
        sb.append("── GATES ──────────────\n")
        val touchAge = LiveStatus.age(LiveStatus.lastUserTouchElapsedMs)
        if (touchAge in 0 until LearnService.USER_QUIET_MS) {
            sb.append("⏳ User-quiet cooldown: ${fmtSecs(LearnService.USER_QUIET_MS - touchAge)} left (touch ${fmtAge(LiveStatus.lastUserTouchElapsedMs)})\n")
        } else {
            sb.append("User-quiet: clear ${if (touchAge >= 0) "(${fmtAge(LiveStatus.lastUserTouchElapsedMs)})" else ""}\n")
        }
        val echoAge = LiveStatus.age(LiveStatus.echoClearElapsedMs)
        sb.append(if (echoAge in 0 until LearnService.ECHO_MS)
            "Echo suppression: ACTIVE (${fmtSecs(echoAge)} / ${fmtSecs(LearnService.ECHO_MS)})\n"
        else "Echo suppression: idle\n")
        val pb = LiveStatus.pendingBucket
        sb.append(if (pb >= 0 && pb != bucket)
            "Bounce hold: bucket ${bucketLabel(pb)} (${fmtSecs(LiveStatus.age(LiveStatus.pendingSinceElapsedMs).coerceAtLeast(0))} / ${fmtSecs(LearnService.BOUNCE_MS)})\n"
        else "Bounce hold: none\n")
        sb.append("\n")

        // --- last event ---
        sb.append("── LAST EVENT ─────────\n")
        sb.append("${LiveStatus.lastEvent}  (${fmtAge(LiveStatus.lastEventElapsedMs)})\n")
        sb.append("Last user touch: ${fmtAge(LiveStatus.lastUserTouchElapsedMs)}\n")
        return sb.toString()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LearnService.start(this)

        val pad = (resources.displayMetrics.density * 16).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        val title = TextView(this).apply {
            text = "🥷 BacklightLearn"
            textSize = 20f; setTypeface(null, Typeface.BOLD)
        }
        statusView = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#CCCCCC"))
            typeface = Typeface.MONOSPACE
        }

        fun button(label: String, action: String): Button = Button(this).apply {
            text = label
            setOnClickListener { startService(Intent(this@MainActivity, LearnService::class.java).setAction(action)) }
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        row.addView(button("Auto ON/OFF", LearnService.ACTION_TOGGLE))
        row.addView(button("Apply now", LearnService.ACTION_ACTUATE))
        row.addView(button("Force", LearnService.ACTION_FORCE))

        root.addView(title)
        root.addView(statusView)
        root.addView(row)
        setContentView(root)
    }

    override fun onResume() { super.onResume(); handler.post(tick) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(tick) }
}
