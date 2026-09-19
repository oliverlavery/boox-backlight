package dev.shadow.booxbacklight

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Phase 2: observe → learn → actuate (closed loop).
 *
 * Actuation: explicit broadcast to Gentle Glow's ChangeLightReceiver (proven path).
 * GG scale 0..100, native 0..32, mapping gg = native * 100/32 (calibrated).
 *
 * Override-fight prevention:
 *  - deadband (LightModel.DEADBAND_STEPS)
 *  - recency gate: no actuation within SETTLE_MS*4 of a user touch
 *  - echo suppression: expected values after our own actuation are ignored
 * The service loses every argument with the user, immediately and silently.
 */
class LearnService : Service(), SensorEventListener {

    private lateinit var sensors: SensorManager
    private val handler = Handler(Looper.getMainLooper())
    private val state = LightModel.State()

    @Volatile private var lastLux: Float? = null
    private var lastLuxLogMs = 0L
    private var lastLuxBucket = -1
    @Volatile private var lastUserTouchMs = 0L
    private var lastUserTouchElapsedMs = 0L
    private var autoEnabled = true

    // echo suppression
    private var expectedB = -1; private var expectedW = -1
    private var echoClearAt = 0L

    // drag settle debounce
    private var pendingB: Int? = null; private var pendingW: Int? = null
    private val settle = Runnable { commitPendingAdjustment() }

    private val logFile: File get() = File(filesDir, "observations.jsonl")

    private fun log(type: String, data: Map<String, Any?>) {
        val json = data.entries.joinToString(",", "\"data\":{", "}") { (k, v) ->
            val vs = if (v is String) "\"$v\"" else v.toString()
            "\"$k\":$vs"
        }
        val line = "{\"t\":\"${Instant.now()}\",\"type\":\"$type\",$json}\n"
        logFile.appendText(line)
        Log.i(TAG, line.trim())
    }

    private fun publish(event: String? = null) {
        LiveStatus.autoEnabled = autoEnabled
        LiveStatus.lastLux = lastLux
        LiveStatus.lastLuxBucket = lastLuxBucket
        LiveStatus.pendingBucket = pendingBucket
        LiveStatus.pendingSinceElapsedMs = pendingSinceMs
        LiveStatus.lastUserTouchElapsedMs = lastUserTouchElapsedMs
        LiveStatus.echoClearElapsedMs = echoClearAt
        LiveStatus.state = state
        LiveStatus.serviceStartedElapsedMs = startedElapsedMs
        if (event != null) { LiveStatus.lastEvent = event; LiveStatus.lastEventElapsedMs = android.os.SystemClock.elapsedRealtime() }
    }

    private fun ctm(key: String): Int = Settings.System.getInt(contentResolver, key, -1)

    // ---- GG actuation ----
    private val ggComponent = ComponentName(
        "com.onyx.darie.calin.gentleglowonyxboox",
        "com.onyx.darie.calin.gentleglowonyxboox.ChangeLightReceiver")
    private val ggAction = "com.onyx.darie.calin.gentleglowonyxboox.CHANGE_LIGHT"

    private fun actuate(nativeB: Int, nativeW: Int): Boolean {
        val ggB = Math.ceil(nativeB * 100.0 / LightModel.NATIVE_MAX).toInt().coerceIn(0, 100)
        val ggW = Math.ceil(nativeW * 100.0 / LightModel.NATIVE_MAX).toInt().coerceIn(0, 100)
        val i = Intent(ggAction).setComponent(ggComponent)
            .putExtra("BRIGHTNESS", ggB).putExtra("WARMTH", ggW)
        return try {
            sendBroadcast(i)
            expectedB = nativeB; expectedW = nativeW
            echoClearAt = System.currentTimeMillis() + ECHO_MS
            log("actuate", mapOf("nativeB" to nativeB, "nativeW" to nativeW, "ggB" to ggB, "ggW" to ggW))
            publish("actuate $nativeB/$nativeW (gg $ggB/$ggW)")
            true
        } catch (e: Exception) {
            log("actuate_failed", mapOf("error" to e.message))
            false
        }
    }

    private fun maybeActuate(reason: String) {
        if (!autoEnabled) return
        val lux = lastLux ?: return
        if (System.currentTimeMillis() - lastUserTouchMs < USER_QUIET_MS) {
            // Rare + useful when diagnosing "why didn't it move" — keep.
            log("debug", mapOf("at" to "quiet_gate", "touch_age_ms" to (System.currentTimeMillis() - lastUserTouchMs)))
            return
        }
        val (lb, lw) = LightModel.predict(state, lux, ZonedDateTime.now(ZoneId.systemDefault()).hour)
        val (cb, cw) = ctm("screen_ctm_brightness") to ctm("screen_ctm_temperature")
        if (Math.abs(lb - cb) > LightModel.DEADBAND_STEPS ||
            Math.abs(lw - cw) > LightModel.DEADBAND_STEPS) {
            log("apply", mapOf("reason" to reason, "target" to "$lb/$lw", "current" to "$cb/$cw"))
            actuate(lb, lw)
        } else {
            log("skip", mapOf("reason" to reason, "target" to "$lb/$lw", "current" to "$cb/$cw"))
        }
        publish()
    }

    // ---- observers ----
    private val ctmObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            val key = uri?.lastPathSegment ?: return
            val v = ctm(key)
            if (System.currentTimeMillis() < echoClearAt &&
                ((key == "screen_ctm_brightness" && v == expectedB) ||
                 (key == "screen_ctm_temperature" && v == expectedW))) {
                log("echo_ignored", mapOf("key" to key, "value" to v)); return
            }
            if (key == "screen_ctm_brightness") pendingB = v
            if (key == "screen_ctm_temperature") pendingW = v
            lastUserTouchMs = System.currentTimeMillis()
            lastUserTouchElapsedMs = android.os.SystemClock.elapsedRealtime()
            handler.removeCallbacks(settle)
            handler.postDelayed(settle, SETTLE_MS)
        }
    }

    /** A user drag/preset tap settled — learn it as authoritative. */
    private fun commitPendingAdjustment() {
        val b = pendingB ?: ctm("screen_ctm_brightness")
        val w = pendingW ?: ctm("screen_ctm_temperature")
        pendingB = null; pendingW = null
        val lux = lastLux
        val hour = ZonedDateTime.now(ZoneId.systemDefault()).hour
        LightModel.onUserAdjust(state, lux, hour, b.takeIf { it >= 0 }, w.takeIf { it >= 0 })
        persist()
        log("user_adjust", mapOf("brightness" to b, "warmth" to w, "lux" to lux, "hour" to hour))
        publish("user_adjust ${b}/${w}")
    }

    // ---- wake handling: flush pending bucket commit immediately on wake ----
    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_ON && pendingBucket != -1) {
                // Wake-up transitions are real, not flutter — commit now.
                val b = pendingBucket
                pendingBucket = -1
                lastLuxBucket = b
                maybeActuate("screen_on")
            }
        }
    }

    // ---- sensor ----
    @Volatile private var coldStart = true
    private var startedElapsedMs = 0L

    override fun onSensorChanged(event: SensorEvent) {
        val lux = event.values[0]
        val prevLux = lastLux
        lastLux = lux
        publish()
        if (coldStart) {
            // First reading after (re)start: adopt bucket and apply learned values
            // immediately if the environment doesn't match them (e.g. service was
            // killed in the bedroom and restarted in the living room).
            coldStart = false
            lastLuxBucket = LightModel.luxBucket(lux)
            maybeActuate("cold_start")
            return
        }
        // Log lux sparingly: bucket entry + 1/sec heartbeat max (sensor spams at ~15Hz).
        val now = android.os.SystemClock.elapsedRealtime()
        if (prevLux == null || LightModel.luxBucket(lux) != LightModel.luxBucket(prevLux)
            || now - lastLuxLogMs > 1000) {
            lastLuxLogMs = now
            log("lux", mapOf("lux" to lux))
        }
        val bucket = LightModel.luxBucket(lux)
        if (bucket != lastLuxBucket) {
            val nowMs = android.os.SystemClock.elapsedRealtime()
            if (bucket == pendingBucket) {
                // Candidate still held after BOUNCE_MS of realtime (sleep-safe)? Commit.
                if (nowMs - pendingSinceMs > BOUNCE_MS) {
                    lastLuxBucket = bucket
                    pendingBucket = -1
                    maybeActuate("lux_bucket=$bucket")
                }
            } else {
                pendingBucket = bucket
                pendingSinceMs = nowMs
                publish()
            }
        }
    }

    private var pendingBucket = -1
    private var pendingSinceMs = 0L

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // ---- persistence ----
    private val storeFile: File get() = File(filesDir, "light-model.json")
    private fun persist() {
        try { storeFile.writeText(LightModel.toJSON(state).toString()) } catch (e: Exception) { Log.e(TAG, "persist", e) }
    }
    private fun restore() {
        try { if (storeFile.exists()) LightModel.fromJSON(org.json.JSONObject(storeFile.readText()))?.let { copyInto(it) } } catch (e: Exception) { Log.e(TAG, "restore", e) }
    }
    private fun copyInto(from: LightModel.State) {
        for (i in state.brightness.indices) { state.brightness[i].value = from.brightness[i].value; state.brightness[i].count = from.brightness[i].count }
        for (i in state.warmth.indices) { state.warmth[i].value = from.warmth[i].value; state.warmth[i].count = from.warmth[i].count }
        state.warmthDarkAdj.value = from.warmthDarkAdj.value; state.warmthDarkAdj.count = from.warmthDarkAdj.count
    }

    // ---- lifecycle ----
    override fun onCreate() {
        super.onCreate()
        sensors = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        restore()
        autoEnabled = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("auto", true)

        val lux = sensors.getDefaultSensor(Sensor.TYPE_LIGHT, true)
            ?: sensors.getDefaultSensor(Sensor.TYPE_LIGHT)
        sensors.registerListener(this, lux, SensorManager.SENSOR_DELAY_NORMAL)
        lastLuxBucket = lastLux?.let { LightModel.luxBucket(it) } ?: -1

        listOf("screen_ctm_brightness", "screen_ctm_temperature").forEach {
            contentResolver.registerContentObserver(Settings.System.getUriFor(it), false, ctmObserver)
        }

        registerReceiver(screenReceiver, android.content.IntentFilter(Intent.ACTION_SCREEN_ON))

        startForeground(NOTIF_ID, buildNotification())
        startedElapsedMs = android.os.SystemClock.elapsedRealtime()
        log("service", mapOf("event" to "started", "auto" to autoEnabled, "lux_sensor" to (lux?.name ?: "MISSING")))
        publish("service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> {
                autoEnabled = !autoEnabled
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("auto", autoEnabled).apply()
                log("service", mapOf("event" to "auto_toggled", "auto" to autoEnabled))
                if (autoEnabled) maybeActuate("toggled_on")
                TileSync.refresh(this)
                publish("auto ${if (autoEnabled) "ON" else "OFF"}")
            }
            ACTION_ACTUATE -> { maybeActuate("manual_refresh"); publish("manual refresh") }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        sensors.unregisterListener(this)
        contentResolver.unregisterContentObserver(ctmObserver)
        unregisterReceiver(screenReceiver)
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val ch = NotificationChannel(CHANNEL, "Backlight learner", NotificationManager.IMPORTANCE_MIN)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Adaptive light ${if (autoEnabled) "ON" else "OFF"}")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "BacklightLearn"
        private const val CHANNEL = "learn"
        private const val NOTIF_ID = 1
        private const val PREFS = "light"
        const val ACTION_TOGGLE = "dev.shadow.booxbacklight.TOGGLE"
        const val ACTION_ACTUATE = "dev.shadow.booxbacklight.ACTUATE"
        const val SETTLE_MS = 1500L      // drag settle debounce
        const val USER_QUIET_MS = 4 * 60 * 1000L   // no actuation within 4 min of a touch
        const val ECHO_MS = 6000L        // ignore mirror echo for 6s after actuation
        const val BOUNCE_MS = 3000L      // bucket must hold this long before actuation (hysteresis)
        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, LearnService::class.java))
    }
}
