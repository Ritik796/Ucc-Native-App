package com.wevois.paymentapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Point 6 (recovery): Reboot / app-update ke baad location tracking apne aap wapas start karta hai —
 * PAR sirf tab jab tracking pehle intentionally ON thi (service_prefs.isServiceRunning == true).
 *
 * Guard zaroori hai: logout (stopBackgroundTask) shared_prefs nuke kar deta hai, aur 23:00 auto-stop /
 * server kill-switch onDestroy me isServiceRunning=false set kar dete hain. In sab cases me flag false
 * hoga, isliye ye receiver un par restart NAHI karega — sirf genuine crash/OEM-kill/reboot par karega.
 *
 * Config (accuracy/interval/dbPath ...) service_prefs me persist hota hai, isliye khaali intent se start
 * karne par bhi MyTaskService.onStartCommand khud config restore kar leti hai.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val sp = context.getSharedPreferences("service_prefs", Context.MODE_PRIVATE)
        val shouldRun = sp.getBoolean("isServiceRunning", false)
        if (!shouldRun) {
            Log.i("LocTrack", "🔁 BOOT ($action): tracking pehle band thi (isServiceRunning=false) — restart nahi karna")
            return
        }

        Log.i("LocTrack", "🔁 BOOT ($action): tracking ON thi — MyTaskService restart kar rahe hain")
        try {
            val serviceIntent = Intent(context, MyTaskService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            // Alarms reboot par clear ho jaate hain — fast-recovery watchdog chain dobara arm karo.
            ServiceWatchdogAlarmReceiver.schedule(context)
        } catch (e: Exception) {
            // Android 14+ 'location' type FGS ko BOOT_COMPLETED se start karne par rok sakta hai.
            // Us case me WorkManager health-check agli window me service wapas le aayega.
            Log.e("LocTrack", "🔁 BOOT restart FAIL: ${e.message} — WorkManager backup handle karega")
        }
    }
}
