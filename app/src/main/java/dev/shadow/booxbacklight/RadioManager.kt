package dev.shadow.booxbacklight

import android.content.Context
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.security.KeyPairGenerator
import java.security.SecureRandom

/**
 * Cellular radio automation via self-ADB (Tasker's "ADB WiFi" trick):
 *
 *   Wi-Fi connected  -> airplane mode ON  (cell radio off, Wi-Fi unaffected)
 *   Wi-Fi lost       -> airplane mode OFF (cell radio back)
 *
 * Mechanism: the device's own adbd listens on localhost:5555 (persist.adb.tcp.port=5555).
 * We speak the ADB protocol to it (dadb) and run `cmd connectivity airplane-mode`
 * as the shell user. First connection triggers the standard RSA authorization
 * dialog; after approval the key is stored in /data/misc/adb/adb_keys.
 *
 * User authority: any airplane-mode change we didn't make (e.g. enabling it
 * manually on a flight) pauses automation for MANUAL_OVERRIDE_MS. The service
 * loses every argument with the user, here too.
 */
class RadioManager(
    private val context: Context,
    private val handler: Handler
) {
    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE)
        as ConnectivityManager
    private val prefs = context.getSharedPreferences("radio", Context.MODE_PRIVATE)

    private var ourWriteUntil = 0L          // echo suppression for our own airplane writes
    private var pausedUntil = 0L            // manual-override pause
    private var lastKnownAirplane = -1

    private val airplaneObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            val v = Settings.Global.getInt(context.contentResolver,
                Settings.Global.AIRPLANE_MODE_ON, -1)
            if (v == lastKnownAirplane) return
            val wasUs = System.currentTimeMillis() < ourWriteUntil
            lastKnownAirplane = v
            if (wasUs) {
                Log.i(TAG, "airplane echo ignored (ours)")
                return
            }
            // User flipped it manually — respect that for a while.
            pausedUntil = System.currentTimeMillis() + MANUAL_OVERRIDE_MS
            Log.i(TAG, "airplane manually set to $v — automation paused ${MANUAL_OVERRIDE_MS / 60000}min")
            RadioManagerLog.append(context, "manual_override airplane=$v")
        }
    }

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.i(TAG, "Wi-Fi available -> airplane ON")
            RadioManagerLog.append(context, "wifi_available -> airplane_on")
            setAirplane(true)
        }

        override fun onLost(network: Network) {
            // Only react if no other Wi-Fi network took over
            handler.postDelayed({
                val caps = connectivity.getNetworkCapabilities(
                    connectivity.activeNetwork)
                val wifiStillUp = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                if (!wifiStillUp) {
                    Log.i(TAG, "Wi-Fi lost -> airplane OFF")
                    RadioManagerLog.append(context, "wifi_lost -> airplane_off")
                    setAirplane(false)
                }
            }, WIFI_LOST_GRACE_MS)
        }
    }

    fun start() {
        lastKnownAirplane = Settings.Global.getInt(context.contentResolver,
            Settings.Global.AIRPLANE_MODE_ON, 0)
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.AIRPLANE_MODE_ON),
            false, airplaneObserver)

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        connectivity.registerNetworkCallback(request, wifiCallback)
        RadioManagerLog.append(context, "radio_manager_started")
        Log.i(TAG, "RadioManager started")
    }

    fun stop() {
        try { connectivity.unregisterNetworkCallback(wifiCallback) } catch (_: Exception) {}
        try { context.contentResolver.unregisterContentObserver(airplaneObserver) } catch (_: Exception) {}
    }

    private fun setAirplane(on: Boolean) {
        if (System.currentTimeMillis() < pausedUntil) {
            Log.i(TAG, "automation paused (manual override) — skipping airplane=${if (on) "on" else "off"}")
            RadioManagerLog.append(context, "skipped_manual_pause target=$on")
            return
        }
        val target = if (on) 1 else 0
        if (lastKnownAirplane == target) return   // already there

        GlobalScope.launch(Dispatchers.IO) {
            try {
                val dadb = Dadb.create("127.0.0.1", 5555, keyPair())
                dadb.use {
                    // Keep Wi-Fi out of airplane mode's kill list (persisted global setting)
                    it.shell("settings put global airplane_mode_radios cell,bluetooth,nfc,wimax")
                    it.shell("settings put global airplane_mode_toggleable_radios wifi,cell,bluetooth")
                    val resp = it.shell("cmd connectivity airplane-mode ${if (on) "enable" else "disable"}")
                    Log.i(TAG, "airplane -> $on: exit=${resp.exitCode} out=${resp.output}")
                }
                ourWriteUntil = System.currentTimeMillis() + ECHO_MS
                lastKnownAirplane = target
                RadioManagerLog.append(context, "airplane_set on=$on")
            } catch (e: Exception) {
                Log.e(TAG, "airplane toggle failed: ${e.message}")
                RadioManagerLog.append(context, "airplane_failed: ${e.message}")
            }
        }
    }

    /** Persistent ADB keypair — generate once, reuse so adbd authorization is one-time. */
    private fun keyPair(): dadb.AdbKeyPair {
        val priv = java.io.File(context.filesDir, "adb_priv.key")
        val pub = java.io.File(context.filesDir, "adb_pub.key")
        if (!priv.exists()) dadb.AdbKeyPair.generate(priv, pub)
        return dadb.AdbKeyPair.read(priv, pub)
    }

    companion object {
        private const val TAG = "BacklightLearn"
        private const val MANUAL_OVERRIDE_MS = 4 * 60 * 60 * 1000L  // 4h
        private const val ECHO_MS = 8000L
        private const val WIFI_LOST_GRACE_MS = 5000L  // tolerate Wi-Fi handoffs
    }
}

/** Append-only radio decision log alongside the backlight observations. */
object RadioManagerLog {
    fun append(context: Context, msg: String) {
        try {
            java.io.File(context.filesDir, "radio.jsonl").appendText(
                "{\"t\":\"${java.time.Instant.now()}\",\"msg\":\"$msg\"}\n")
        } catch (_: Exception) {}
    }
}
