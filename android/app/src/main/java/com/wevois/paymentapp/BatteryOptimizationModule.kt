package com.wevois.paymentapp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod

/**
 * Helps keep the location foreground service alive on aggressive OEMs.
 *
 * Two separate concerns:
 *  1. Battery Optimization  -> standard Android, can be CHECKED + requested via a system dialog.
 *  2. Auto Start / Auto Launch -> OEM-only (Xiaomi/Oppo/Vivo/Realme/...). NO public API to check;
 *     we can only best-effort OPEN the OEM screen and let the user enable it.
 */
class BatteryOptimizationModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    override fun getName(): String = "BatteryOptimizationModule"

    @ReactMethod
    fun getManufacturer(promise: Promise) {
        promise.resolve(Build.MANUFACTURER ?: "")
    }

    /**
     * true = device abhi POWER SAVING / Battery Saver mode me hai. Is mode me OEM background apps ko
     * aggressive kill/throttle karta hai aur location ruk sakti hai — isliye JS side user ko warn karta hai.
     */
    @ReactMethod
    fun isPowerSaveMode(promise: Promise) {
        try {
            val pm = reactContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            promise.resolve(pm.isPowerSaveMode)
        } catch (e: Exception) {
            promise.resolve(false)
        }
    }

    /** true = app battery optimization se exempt hai (service kill hone ka chance kam). */
    @ReactMethod
    fun isIgnoringBatteryOptimizations(promise: Promise) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                promise.resolve(true)
                return
            }
            val pm = reactContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            promise.resolve(pm.isIgnoringBatteryOptimizations(reactContext.packageName))
        } catch (e: Exception) {
            promise.reject("BATTERY_OPT_CHECK_FAILED", e)
        }
    }

    /** Standard system dialog: "Allow app to run in background / Don't optimize". */
    @ReactMethod
    fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${reactContext.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startSafely(intent, batteryOptSettingsIntent())
    }

    /** Best-effort: open the OEM Auto Start / startup-manager screen; falls back to app settings. */
    @ReactMethod
    fun openAutoStartSettings() {
        for (intent in autoStartIntents()) {
            if (tryStart(intent)) return
        }
        // No OEM screen matched -> at least open this app's settings page.
        startSafely(appDetailsIntent(), null)
    }

    /**
     * Battery settings screen kholta hai taaki user "High background power consumption" tak pahunch
     * sake. Vivo abe wali high-power screen permission-locked hoti hai (seedhe nahi khul sakti), isliye
     * top-level Battery screen kholte hain jahan se user manually navigate kare. Fallback: app details.
     */
    @ReactMethod
    fun openBatterySettings() {
        val batteryUsage = Intent(Intent.ACTION_POWER_USAGE_SUMMARY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            reactContext.startActivity(batteryUsage)
        } catch (e: Exception) {
            startSafely(appDetailsIntent(), null)
        }
    }

    // ---------- helpers ----------

    private fun batteryOptSettingsIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun appDetailsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${reactContext.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /**
     * Known OEM auto-start activities. Activity names vary by brand AND firmware version, so we
     * try them in order and use the first one that launches. Anything that isn't present just
     * throws and is skipped.
     */
    private fun autoStartIntents(): List<Intent> {
        val components = listOf(
            // Xiaomi / Redmi / Poco (MIUI)
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            // Oppo (ColorOS)
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
            "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
            // Vivo / iQOO
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
            // Realme (ColorOS based)
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.FakeActivity",
            // Huawei / Honor
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
            // Letv
            "com.letv.android.letvsafe" to "com.letv.android.letvsafe.AutobootManageActivity"
        )
        return components.map { (pkg, cls) ->
            Intent().apply {
                component = ComponentName(pkg, cls)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    /** Try launching directly; resolveActivity() is unreliable on API 30+ due to package visibility. */
    private fun tryStart(intent: Intent): Boolean {
        return try {
            reactContext.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun startSafely(intent: Intent, fallback: Intent?) {
        try {
            reactContext.startActivity(intent)
        } catch (e: Exception) {
            fallback?.let {
                try {
                    reactContext.startActivity(it)
                } catch (ignored: Exception) {
                }
            }
        }
    }
}
