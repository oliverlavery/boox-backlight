package dev.shadow.booxbacklight

import android.util.Log
import java.lang.reflect.Method

/**
 * Direct actuation via the hidden firmware class android.onyx.hardware.DeviceController
 * (no Gentle Glow dependency). Discovered via SDK decompilation (onyxsdk-device 1.3.5):
 * SDK's SDMDevice.setLightValue reflects into this class. Types: 7=CTM brightness,
 * 6=CTM temperature (see SDK constants); Palma 2 Pro is a CTM (SDM/Qualcomm) device.
 *
 * Strategy: enumerate the class's declared static methods on first use, log them,
 * then try plausible (name, arity) shapes until one works. The result of each
 * attempt is cached.
 */
object NativeLight {

    private const val TAG = "BacklightLearn"
    private var setBMethod: Method? = null
    private var setWMethod: Method? = null
    private var probed = false

    val available: Boolean get() = probed && setBMethod != null

    /**
     * Hidden-API exemption bypass (same trick as GG's ReflectUtil):
     * meta-reflection into VMRuntime.setHiddenApiExemptions("L"). Without this,
     * Android 9+ serves an EMPTY STUB of android.onyx.hardware.DeviceController
     * (declared=0, verified on Palma 2 Pro) and reflection finds nothing.
     */
    private fun exemptHiddenApi() {
        // Meta-reflection exemption is patched on Android 15 — use LSPosed HiddenApiBypass
        // to clear the hidden-API restriction for our whole process instead.
        try {
            org.lsposed.hiddenapibypass.HiddenApiBypass.setHiddenApiExemptions("L")
            Log.i(TAG, "hidden-API exemptions applied (HiddenApiBypass)")
        } catch (e: Throwable) {
            Log.e(TAG, "hidden-API exemption failed: ${e}")
        }
    }

    private fun probe() {
        if (probed) return
        probed = true
        exemptHiddenApi()
        try {
            val clazz = Class.forName("android.onyx.hardware.DeviceController")
            val all = clazz.methods
            val declared = clazz.declaredMethods
            Log.i(TAG, "probe: class=$clazz public=${all.size} declared=${declared.size}")
            for (m in declared) {
                Log.i(TAG, "DC.${m.name}(${m.parameterTypes.joinToString { it.simpleName }}) static=${java.lang.reflect.Modifier.isStatic(m.modifiers)}")
            }
            // Preferred: setLightValue(type: Int, value: Int) [static or instance]
            val cand = clazz.declaredMethods.filter {
                it.name == "setLightValue" && it.parameterTypes.size >= 2 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType
            }
            setBMethod = cand.firstOrNull { it.parameterTypes.size == 2 }
            setWMethod = setBMethod
            // Some firmwares take (type, value, flag) — remember as fallback
            if (setBMethod == null) {
                setBMethod = cand.firstOrNull { it.parameterTypes.size == 3 }
                setWMethod = setBMethod
            }
            Log.i(TAG, "NativeLight probe: setMethod=${setBMethod?.name} arity=${setBMethod?.parameterTypes?.size}")
        } catch (e: Throwable) {
            Log.e(TAG, "NativeLight probe failed: ${e.message}")
        }
    }

    /** Try native set. Returns true if a method invoked without throwing. */
    fun set(brightness: Int, warmth: Int): Boolean {
        probe()
        val m = setBMethod ?: return false
        return try {
            val instance = if (java.lang.reflect.Modifier.isStatic(m.modifiers)) null
                           else m.declaringClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            val args = if (m.parameterTypes.size == 2)
                arrayOf(7, brightness)      // 7 = CTM brightness
            else arrayOf(7, brightness, 0)
            m.invoke(instance, *args)
            val argsW = if (m.parameterTypes.size == 2)
                arrayOf(6, warmth)          // 6 = CTM temperature
            else arrayOf(6, warmth, 0)
            m.invoke(instance, *argsW)
            Log.i(TAG, "NativeLight.set ok: B=$brightness W=$warmth")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "NativeLight.set failed: ${e.message}")
            false
        }
    }
}
