package com.wevois.paymentapp

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap
import java.util.Calendar
import com.wevois.paymentapp.MyTaskService.TravelHistoryManager.flushBackgroundHistoryIfNeeded


class BackgroundTaskModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    private var lastStartTime = 0L
    private val minStartInterval = 10_000L // 10 seconds
    private val handler = Handler(Looper.getMainLooper())
    private var runnable: Runnable? = null
    override fun getName(): String {
        return "BackgroundTaskModule"
    }

    @SuppressLint("NewApi")
    @ReactMethod
    fun startBackgroundTask(options: ReadableMap) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastStartTime < minStartInterval) {

            return
        }
        lastStartTime = currentTime

        val context = reactApplicationContext
        val accuracy = if (options.hasKey("LOCATION_ACCURACY")) options.getString("LOCATION_ACCURACY") else null
        val updateDistance = if (options.hasKey("LOCATION_UPDATE_DISTANCE")) options.getString("LOCATION_UPDATE_DISTANCE") else null
        val sendDelay = if (options.hasKey("LOCATION_SEND_INTERVAL")) options.getString("LOCATION_SEND_INTERVAL") else null
        val updateInterval = if (options.hasKey("LOCATION_UPDATE_INTERVAL")) options.getString("LOCATION_UPDATE_INTERVAL") else null

        if (accuracy.isNullOrEmpty() || updateDistance.isNullOrEmpty() || sendDelay.isNullOrEmpty() || updateInterval.isNullOrEmpty()) {

            return
        }


        val serviceIntent = Intent(context, MyTaskService::class.java).apply {
            putExtra("LOCATION_ACCURACY", accuracy)
            putExtra("LOCATION_UPDATE_DISTANCE", updateDistance)
            putExtra("LOCATION_UPDATE_INTERVAL",updateInterval)
            putExtra("LOCATION_SEND_INTERVAL",sendDelay)
        }

        // Version check for startForegroundService
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
        startTimeChecker()
    }

    @ReactMethod
    fun checkAndRestartBackgroundTask(options: ReadableMap) {
        val context = reactApplicationContext
        flushBackgroundHistoryIfNeeded(context)
        val accuracy = if (options.hasKey("LOCATION_ACCURACY")) options.getString("LOCATION_ACCURACY") else null
        val updateDistance = if (options.hasKey("LOCATION_UPDATE_DISTANCE")) options.getString("LOCATION_UPDATE_DISTANCE") else null
        val sendDelay = if (options.hasKey("LOCATION_SEND_INTERVAL")) options.getString("LOCATION_SEND_INTERVAL") else null
        val updateInterval = if (options.hasKey("LOCATION_UPDATE_INTERVAL")) options.getString("LOCATION_UPDATE_INTERVAL") else null

        if (accuracy.isNullOrEmpty() || updateDistance.isNullOrEmpty() || sendDelay.isNullOrEmpty() || updateInterval.isNullOrEmpty()) {
            return
        }

        if (!isServiceRunning(MyTaskService::class.java)) {

            val serviceIntent = Intent(context, MyTaskService::class.java).apply {
                putExtra("LOCATION_ACCURACY", accuracy)
                putExtra("LOCATION_UPDATE_DISTANCE", updateDistance)
                putExtra("LOCATION_UPDATE_INTERVAL", updateInterval)
                putExtra("LOCATION_SEND_INTERVAL", sendDelay)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }



            startTimeChecker()
        }
    }





    @SuppressLint("MissingPermission", "DeprecatedMethod")
    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val activityManager = reactApplicationContext.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        @Suppress("DEPRECATION")
        for (service in activityManager.getRunningServices(Int.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }

    @ReactMethod
    fun stopBackgroundTask() {
        val context = reactApplicationContext
        val serviceIntent = Intent(context, MyTaskService::class.java)
        context.stopService(serviceIntent)
        stopTimeChecker()
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
}
