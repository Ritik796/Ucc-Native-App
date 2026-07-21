package com.wevois.paymentapp

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Fast-recovery layer: WorkManager ka minimum 15 min (Android hard limit) hard-kill recovery ke liye
 * bahut lamba hai. Ye AlarmManager-based watchdog har ~2.5 min service-alive check karta hai aur KHUD KO
 * dobara schedule karta rehta hai (self-rescheduling chain) — Vivo abe/pem ke HARD kill ke baad
 * WorkManager se bahut pehle service wapas laane ki koshish.
 *
 * Layering (fast se slow):
 *   onTaskRemoved (~2s) -> ye AlarmManager (~2.5 min) -> WorkManager (~15 min, aakhri backup)
 *
 * Note: device deep-Doze me alarm rate-limit ho sakti hai (~9 min), par field worker ke active use me
 * (screen on/off, movement) device active rehta hai to ~2.5 min pe fire hoti hai. Chain ek pending alarm
 * ke through process-death ke paar bhi zinda rehti hai; sirf reboot par BootReceiver dobara arm karta hai.
 */
class ServiceWatchdogAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val shouldRun = context.getSharedPreferences("service_prefs", Context.MODE_PRIVATE)
            .getBoolean("isServiceRunning", false)

        if (!shouldRun) {
            // Tracking intentionally band (logout/23:00/kill-switch) — chain yahin rok do.
            Log.i("LocTrack", "⏰ ALARM-WATCHDOG: tracking OFF (isServiceRunning=false) — chain stop")
            return
        }

        if (!isServiceRunning(context)) {
            Log.w("LocTrack", "⏰ ALARM-WATCHDOG: service DEAD par tracking ON honi chahiye — RESTART")
            try {
                val svc = Intent(context, MyTaskService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(svc)
                } else {
                    context.startService(svc)
                }
            } catch (e: Exception) {
                // Android 12+ background se FGS start restrict kar sakta hai — WorkManager backup handle karega.
                Log.e("LocTrack", "⏰ ALARM-WATCHDOG restart FAIL: ${e.message}")
            }
        } else {
            Log.i("LocTrack", "⏰ ALARM-WATCHDOG: service zinda — sab theek")
        }

        // Chain continue: agla check ~2.5 min baad.
        schedule(context)
    }

    @Suppress("DEPRECATION")
    private fun isServiceRunning(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == MyTaskService::class.java.name
        }
    }

    companion object {
        private const val INTERVAL_MS = 150_000L // ~2.5 min
        const val ACTION = "com.wevois.paymentapp.SERVICE_WATCHDOG_ALARM"
        private const val REQ = 7

        private fun pending(context: Context): PendingIntent {
            val intent = Intent(context, ServiceWatchdogAlarmReceiver::class.java).apply {
                action = ACTION
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            return PendingIntent.getBroadcast(context, REQ, intent, flags)
        }

        /** Chain start/continue: agla alarm ~2.5 min baad. Tracking start hone par aur reboot par call. */
        fun schedule(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val triggerAt = System.currentTimeMillis() + INTERVAL_MS
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    // Exact-and-allow-while-idle best timing; Android 12+ bina SCHEDULE_EXACT_ALARM
                    // permission SecurityException de sakta hai -> inexact allow-while-idle fallback.
                    try {
                        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending(context))
                    } catch (se: SecurityException) {
                        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending(context))
                    }
                } else {
                    am.set(AlarmManager.RTC_WAKEUP, triggerAt, pending(context))
                }
                Log.i("LocTrack", "⏰ ALARM-WATCHDOG scheduled (~2.5 min baad next check)")
            } catch (e: Exception) {
                Log.e("LocTrack", "⏰ ALARM-WATCHDOG schedule FAIL: ${e.message}")
            }
        }

        fun cancel(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(pending(context))
            Log.i("LocTrack", "⏰ ALARM-WATCHDOG cancelled (tracking intentionally band)")
        }
    }
}
