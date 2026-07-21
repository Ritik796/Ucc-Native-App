package com.wevois.paymentapp

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * Point 8 (backup safety-net): Har ~15 min WorkManager ise chalata hai. Ye khud GPS track NAHI karta —
 * sirf sehat check karta hai: "tracking ON honi chahiye par MyTaskService dead hai?" — agar haan to
 * service dobara start kar deta hai. OEM (Vivo abe/pem) ke chup-chaap kill kar dene ke baad wapas recover
 * karne ka aakhri layer.
 *
 * Watchdog (service ke andar) sirf tab kaam karta hai jab service ZINDA ho; ye worker service ke MAR jaane
 * wale case ko cover karta hai — dono milke poora gap bharte hain.
 */
class LocationServiceRestartWorker(
    private val context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        val sp = context.getSharedPreferences("service_prefs", Context.MODE_PRIVATE)
        val shouldRun = sp.getBoolean("isServiceRunning", false)

        if (!shouldRun) {
            Log.i("LocTrack", "🩺 HEALTH-CHECK: tracking intentionally OFF (isServiceRunning=false) — kuch nahi karna")
            return Result.success()
        }

        if (isServiceRunning()) {
            Log.i("LocTrack", "🩺 HEALTH-CHECK: service zinda hai — sab theek")
            return Result.success()
        }

        // Flag ON par bhi service dead — OEM ne kill kar diya. Restart.
        Log.w("LocTrack", "🩺 HEALTH-CHECK: service DEAD par tracking ON honi chahiye — RESTART kar rahe hain")
        return try {
            val serviceIntent = Intent(context, MyTaskService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Result.success()
        } catch (e: Exception) {
            // Android 12+ background se FGS start restriction laga sakta hai — agli window me retry.
            Log.e("LocTrack", "🩺 HEALTH-CHECK restart FAIL: ${e.message} — agli window me retry")
            Result.retry()
        }
    }

    @Suppress("DEPRECATION")
    private fun isServiceRunning(): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == MyTaskService::class.java.name
        }
    }

    companion object {
        const val WORK_NAME = "location_service_health_check"
    }
}
