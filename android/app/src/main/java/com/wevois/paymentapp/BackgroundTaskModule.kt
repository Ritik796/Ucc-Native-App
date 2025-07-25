package com.wevois.paymentapp

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.facebook.react.bridge.*
import java.util.Calendar

class BackgroundTaskModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    private var lastStartTime = 0L
    private val minStartInterval = 10_000L
    private val handler = Handler(Looper.getMainLooper())
    private var runnable: Runnable? = null

    override fun getName(): String = "BackgroundTaskModule"

    @SuppressLint("NewApi")
    @ReactMethod
    fun startBackgroundTask(options: ReadableMap) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastStartTime < minStartInterval) {
            Log.d("BackgroundTaskModule", "Start request ignored due to cooldown")
            return
        }
        lastStartTime = currentTime

        val context = reactApplicationContext
        val userId = options.getString("USER_ID") ?: ""
        val dbPath = options.getString("DB_PATH") ?: ""

        if (userId.isEmpty() || dbPath.isEmpty()) {
            Log.e("BackgroundTaskModule", "Missing USER_ID or DB_PATH")
            return
        }

        val serviceIntent = Intent(context, MyTaskService::class.java).apply {
            putExtra("USER_ID", userId)
            putExtra("DB_PATH", dbPath)
        }

        // Start foreground service
        ContextCompat.startForegroundService(context, serviceIntent)

        setTrackingActive(true)
        startTimeChecker()
        Log.d("BackgroundTaskModule", "startForegroundService called")
    }

    @ReactMethod
    fun stopBackgroundTask() {
        val context = reactApplicationContext
        val serviceIntent = Intent(context, MyTaskService::class.java)

        context.stopService(serviceIntent)
        setTrackingActive(false)
        stopTimeChecker()

        // Cancel the foreground notification and the status notification
        NotificationManagerCompat.from(context).cancel(1)
        NotificationManagerCompat.from(context).cancel(1001)

        // Reset the tracking flag
        val prefs = context.getSharedPreferences("tracking_prefs", Context.MODE_PRIVATE)
        prefs.edit {
            putBoolean("is_notification_shown", false)
        }

        Log.d("BackgroundTaskModule", "stopBackgroundTask called")
    }

    @ReactMethod
    fun checkAndRestartBackgroundTask(options: ReadableMap) {
        if (!isTrackingActive()) {
            Log.d("BackgroundTaskModule", "Tracking inactive. Restarting...")
            startBackgroundTask(options)
        } else {
            Log.d("BackgroundTaskModule", "Tracking already running.")
            showTrackingAlreadyRunningNotification()
        }
    }

    private fun startTimeChecker() {
        runnable = object : Runnable {
            override fun run() {
                val calendar = Calendar.getInstance()
                val hour = calendar.get(Calendar.HOUR_OF_DAY)
                val minute = calendar.get(Calendar.MINUTE)

                if (hour == 23 && minute == 0) {
                    stopBackgroundTask()
                    stopTimeChecker()
                }

                handler.postDelayed(this, 60_000)
            }
        }
        handler.postDelayed(runnable!!, getDelayToNextMinute())
    }

    private fun stopTimeChecker() {
        runnable?.let { handler.removeCallbacks(it) }
        runnable = null
    }

    private fun getDelayToNextMinute(): Long {
        val now = Calendar.getInstance()
        val nextMinute = (now.clone() as Calendar).apply {
            add(Calendar.MINUTE, 1)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return nextMinute.timeInMillis - now.timeInMillis
    }

    private fun setTrackingActive(isActive: Boolean) {
        val prefs = reactApplicationContext.getSharedPreferences("tracking_prefs", Context.MODE_PRIVATE)
        prefs.edit {
            putBoolean("is_tracking_active", isActive)
        }
    }

    private fun isTrackingActive(): Boolean {
        val prefs = reactApplicationContext.getSharedPreferences("tracking_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("is_tracking_active", false)
    }

    private fun showTrackingAlreadyRunningNotification() {
        val context = reactApplicationContext
        val channelId = "TrackingStatusChannel"
        val notificationId = 1001

        // ✅ Android 13+ notification permission check
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permissionCheck = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            )
            if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
                Log.w("BackgroundTaskModule", "Notification permission not granted, skipping.")
                return
            }
        }

        // ✅ Delete and recreate channel to force badge setting
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.deleteNotificationChannel(channelId) // Remove old channel to reset settings

            val channel = NotificationChannel(
                channelId,
                "Tracking Status",
                NotificationManager.IMPORTANCE_MIN // MIN hides dot/badge/sound
            ).apply {
                description = "Shows when tracking is already active"
                enableVibration(false)
                setSound(null, null)
                setShowBadge(false) // ✅ No badge on app icon
            }
            manager.createNotificationChannel(channel)
        }

        // ✅ Cancel any previous notification
        NotificationManagerCompat.from(context).cancel(notificationId)

        // ✅ Build new notification without badge
        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle("WeVOIS Payment App")
            .setContentText("Background service is running")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_MIN) // ✅ No badge
            .setAutoCancel(false)
            .setOngoing(true)
            .setBadgeIconType(NotificationCompat.BADGE_ICON_NONE) // ✅ No badge icon
            .build()

        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (e: SecurityException) {
            Log.e("BackgroundTaskModule", "Notification failed: ${e.message}")
        }
    }

}
