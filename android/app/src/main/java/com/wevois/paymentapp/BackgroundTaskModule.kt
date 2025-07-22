package com.wevois.paymentapp

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap
import android.util.Log
import java.util.Calendar

class BackgroundTaskModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    override fun getName(): String {
        return "BackgroundTaskModule"
    }

    @SuppressLint("NewApi")
    @ReactMethod
    fun startBackgroundTask(options: ReadableMap) {
        val context = reactApplicationContext
        val userId = if (options.hasKey("USER_ID")) options.getString("USER_ID") else null
        val dbPath = if (options.hasKey("DB_PATH")) options.getString("DB_PATH") else null

        Log.d("BackgroundTaskModule", "Received USER_ID: $userId")
        Log.d("BackgroundTaskModule", "Received DB_PATH: $dbPath")

        if (userId.isNullOrEmpty() || dbPath.isNullOrEmpty()) {
//            Toast.makeText(context, "USER_ID or DB_PATH is missing", Toast.LENGTH_SHORT).show()
            Log.e("BackgroundTaskModule", "Missing USER_ID or DB_PATH")
            return
        }

//        Toast.makeText(context, "Background Service Started", Toast.LENGTH_SHORT).show()
        Log.i("BackgroundTaskModule", "Starting MyTaskService with USER_ID: $userId and DB_PATH: $dbPath")

        val serviceIntent = Intent(context, MyTaskService::class.java).apply {
            putExtra("USER_ID", userId)
            putExtra("DB_PATH", dbPath)
        }

        context.startForegroundService(serviceIntent)
        startTimeChecker()
        Log.d("BackgroundTaskModule", "startForegroundService called")
    }

    @ReactMethod
    fun stopBackgroundTask() {
        val context = reactApplicationContext

        val serviceIntent = Intent(context, MyTaskService::class.java)
        context.stopService(serviceIntent)
        stopTimeChecker()
    }
    private val handler = Handler(Looper.getMainLooper())
    private var runnable: Runnable? = null

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
