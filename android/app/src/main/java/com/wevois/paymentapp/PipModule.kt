package com.wevois.paymentapp

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod

/**
 * Picture-in-Picture (PiP) ka JS <-> native bridge.
 *
 * PiP enter karne ka actual call MainActivity.onUserLeaveHint() mein hota hai. Yeh module sirf:
 *  1. Sensitive flows (camera/payment/bluetooth) ke around PiP ko temporarily allow/block karta hai.
 *  2. Device support + per-app PiP permission check karta hai.
 *  3. PiP settings screen kholta hai taaki user toggle ON kar sake (OEMs par by default OFF hota hai).
 */
class PipModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    override fun getName(): String = "PipModule"

    companion object {
        /**
         * MainActivity.onUserLeaveHint() isko padta hai. Default true (normal tracking par PiP allow).
         * Camera/payment/bluetooth ke time JS isko false set karta hai taaki app un flows ke beech
         * shrink na ho.
         */
        @Volatile
        var isPipAllowed: Boolean = true
    }

    @ReactMethod
    fun setPipAllowed(allowed: Boolean) {
        isPipAllowed = allowed
    }

    /** Device PiP support karta hai ya nahi (OS + hardware level). */
    @ReactMethod
    fun isPipSupported(promise: Promise) {
        val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            reactContext.packageManager.hasSystemFeature(
                PackageManager.FEATURE_PICTURE_IN_PICTURE
            )
        promise.resolve(supported)
    }

    /** Per-app PiP permission ON hai ya nahi (Settings -> Picture-in-picture toggle). */
    @ReactMethod
    fun isPipPermissionGranted(promise: Promise) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                promise.resolve(false)
                return
            }
            promise.resolve(hasPipPermission(reactContext))
        } catch (e: Exception) {
            promise.resolve(false)
        }
    }

    /** PiP settings screen kholo; agar wo na mile to app details settings par fallback. */
    @ReactMethod
    fun openPipSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val intent = Intent(
                "android.settings.PICTURE_IN_PICTURE_SETTINGS",
                Uri.parse("package:${reactContext.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            reactContext.startActivity(intent)
        } catch (e: Exception) {
            try {
                val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${reactContext.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                reactContext.startActivity(fallback)
            } catch (ignored: Exception) {
            }
        }
    }
}

/**
 * AppOps se PiP permission check. MainActivity bhi isi helper ko use karta hai taaki logic ek hi
 * jagah rahe.
 */
internal fun hasPipPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
    return try {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_PICTURE_IN_PICTURE,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_PICTURE_IN_PICTURE,
                Process.myUid(),
                context.packageName
            )
        }
        mode == AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) {
        false
    }
}
