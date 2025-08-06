package com.wevois.paymentapp

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.google.firebase.database.*
import com.wevois.paymentapp.MyTaskService.TravelHistoryManager.flushBackgroundHistoryIfNeeded
import com.wevois.paymentapp.MyTaskService.TravelHistoryManager.flushLockHistory
import java.util.*

class BackgroundTaskModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    private var lastStartTime = 0L
    private val minStartInterval = 10_000L // 10 seconds
    private val handler = Handler(Looper.getMainLooper())
    private var runnable: Runnable? = null
    private var serverTimeListener: ValueEventListener? = null
    private var serverTimeRef: DatabaseReference? = null
    private var currentServerTimePath: String? = null


    override fun getName(): String {
        return "BackgroundTaskModule"
    }

    @SuppressLint("NewApi")
    @ReactMethod
    fun startBackgroundTask(options: ReadableMap) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastStartTime < minStartInterval) return
        lastStartTime = currentTime

        val context = reactApplicationContext

        val accuracy = options.getStringSafe("LOCATION_ACCURACY")
        val updateDistance = options.getStringSafe("LOCATION_UPDATE_DISTANCE")
        val sendDelay = options.getStringSafe("LOCATION_SEND_INTERVAL")
        val updateInterval = options.getStringSafe("LOCATION_UPDATE_INTERVAL")
        val serverTimePath = options.getStringSafe("SERVER_TIME_PATH")
        val dbPath = options.getStringSafe("DB_PATH")

        if (accuracy.isNullOrEmpty() || updateDistance.isNullOrEmpty() ||
            sendDelay.isNullOrEmpty() || updateInterval.isNullOrEmpty()
        ) return

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

        startServerTimeListener(dbPath.toString(), serverTimePath.toString())
        flushBackgroundHistoryIfNeeded(context)
        flushLockHistory(context)
        startTimeChecker()
    }

    @ReactMethod
    fun checkAndRestartBackgroundTask(options: ReadableMap) {
        val context = reactApplicationContext

        val sendDelay = options.getStringSafe("LOCATION_SEND_INTERVAL")
        val updateInterval = options.getStringSafe("LOCATION_UPDATE_INTERVAL")
        val accuracy = options.getStringSafe("LOCATION_ACCURACY")
        val updateDistance = options.getStringSafe("LOCATION_UPDATE_DISTANCE")
        val serverTimePath = options.getStringSafe("SERVER_TIME_PATH")
        val dbPath = options.getStringSafe("DB_PATH")

        if (accuracy.isNullOrEmpty() || updateDistance.isNullOrEmpty() ||
            sendDelay.isNullOrEmpty() || updateInterval.isNullOrEmpty()
        ) return

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
        }
        startServerTimeListener(dbPath.toString(), serverTimePath.toString())
        flushBackgroundHistoryIfNeeded(context)
        flushLockHistory(context)
        startTimeChecker()
    }

    private fun ReadableMap.getStringSafe(key: String): String? {
        if (!this.hasKey(key)) return null

        val dynamic = this.getDynamic(key)
        return if (dynamic.isNull) {
            null
        } else {
            when (dynamic.type) {
                ReadableType.String -> dynamic.asString()
                ReadableType.Number -> dynamic.asDouble().toString()
                else -> null
            }
        }
    }

    @SuppressLint("MissingPermission", "DeprecatedMethod")
    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val activityManager =
            reactApplicationContext.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
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
        stopServerTimeListener()
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

    // ✅ Working Realtime Listener
    @ReactMethod




   private fun startServerTimeListener(dbPath: String, serverTimePath: String) {
        val baseUrl = dbPath.trimEnd('/')
        val relativePath = serverTimePath.trimStart('/')
        currentServerTimePath = relativePath

        // Avoid constructing full URL, just use FirebaseDatabase.getInstance()
        try {
            val db = FirebaseDatabase.getInstance(baseUrl)
            serverTimeRef = db.getReference(relativePath)

            Log.d("ServerTimeMonitor", "📡 Initialized reference at path: $relativePath")

            serverTimeListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val serverTimeValue = snapshot.value?.toString() ?: ""
                    Log.d("ServerTimeMonitor", "⏱ Server time updated: $serverTimeValue")

                    if (serverTimeValue.isEmpty()) {
                        reactApplicationContext
                            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                            .emit("onServerTimeStatus", "false")
                    }
                    else{
                        reactApplicationContext
                            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                            .emit("onServerTimeStatus", "true")
                    stopBackgroundTask()
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e("ServerTimeMonitor", "❌ Server time listener cancelled: ${error.message}")
                }
            }

            serverTimeRef?.addValueEventListener(serverTimeListener!!)
            Log.d("ServerTimeMonitor", "✅ Listener added successfully.")

        } catch (e: Exception) {
            Log.e("ServerTimeMonitor", "🚨 Failed to initialize Firebase reference: ${e.message}")
        }
    }



    // ✅ To stop Firebase real-time listener
    @ReactMethod
    fun stopServerTimeListener() {
        if (serverTimeRef != null && serverTimeListener != null) {
            serverTimeRef?.removeEventListener(serverTimeListener!!)
            Log.d("ServerTimeMonitor", "🛑 Listener removed from path: $currentServerTimePath")
        } else {
            Log.d("ServerTimeMonitor", "ℹ️ No active listener to remove.")
        }

        serverTimeListener = null
        serverTimeRef = null
        currentServerTimePath = null
    }
}
