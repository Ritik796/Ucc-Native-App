package com.wevois.paymentapp

import android.annotation.SuppressLint
import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.*
import android.location.*
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import com.facebook.react.HeadlessJsTaskService
import com.facebook.react.bridge.Arguments
import com.facebook.react.jstasks.HeadlessJsTaskConfig
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

@Suppress("DEPRECATION")
class MyTaskService : HeadlessJsTaskService() {

    private lateinit var locationManager: LocationManager
    private lateinit var locationListener: LocationListener
    private lateinit var sensorManager: SensorManager
    private var motionSensorListener: SensorEventListener? = null

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var saveRunnable: Runnable

    private lateinit var reqLocAccuracy: String
    private lateinit var reqLocDistance: String
    private lateinit var reqLocInterval: String
    private lateinit var reqLocSendInterval: String

    private var previousLat: Double? = null
    private var previousLng: Double? = null
    private val maxDistance = 15
    private var maxDistanceCanCover = maxDistance
    private var minuteDistance: Double = 0.0
    private val traversalHistory = StringBuilder()
    private var isDeviceMoving = false
    private var lastAcceleration = FloatArray(3)
    private val accelerationThreshold = 0.5f
    private var isWaitingForUnlockLocation = false
    private var isDeviceLocked = false

    override fun onCreate() {
        super.onCreate()
        startForegroundServiceWithNotification()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        registerScreenLockReceiver()
        setupMotionSensor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        reqLocAccuracy = intent?.getStringExtra("LOCATION_ACCURACY") ?: "15"
        reqLocDistance = intent?.getStringExtra("LOCATION_UPDATE_DISTANCE") ?: "1"
        reqLocInterval = intent?.getStringExtra("LOCATION_UPDATE_INTERVAL") ?: "3000"
        reqLocSendInterval = intent?.getStringExtra("LOCATION_SEND_INTERVAL") ?: "1"

        setServiceRunning(true)
        startLocationManagerUpdates()
        return START_STICKY
    }

    @SuppressLint("MissingPermission", "InvalidWakeLockTag")
    private fun startLocationManagerUpdates() {
        Log.d("LocationService", "Starting location updates")

        val intervalMillis = reqLocInterval.toLongOrNull() ?: 3000L
        val distanceMeters = reqLocDistance.toFloatOrNull() ?: 1f

        locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                handleLocationUpdate(location)
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                intervalMillis,
                distanceMeters,
                locationListener,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            Log.e("LocationService", "Permission denied for location updates", e)
        }

        // Setup periodic save
        saveRunnable = object : Runnable {
            override fun run() {
                if (traversalHistory.isNotEmpty()) {
                    while (traversalHistory.endsWith("~")) {
                        traversalHistory.setLength(traversalHistory.length - 1)
                    }
                    saveDataToDatabase(traversalHistory.toString())
                    traversalHistory.clear()
                    minuteDistance = 0.0
                    maxDistanceCanCover = maxDistance
                }
                handler.postDelayed(this, getDelayToNextMinute())
            }
        }
        handler.postDelayed(saveRunnable, getDelayToNextMinute())
    }

    private fun handleLocationUpdate(location: Location) {
        val lat = location.latitude
        val lng = location.longitude
        val acc = location.accuracy

        Log.d("LocationUpdate", "Location: $lat,$lng, Accuracy: $acc")

        if (lat.isNaN() || lng.isNaN()) {
            Log.e("LocationUpdate", "Invalid coordinates received")
            return
        }

        val requiredLocationAccuracy = reqLocAccuracy.toFloatOrNull() ?: 15f
        if (acc > requiredLocationAccuracy) {
            Log.d("LocationUpdate", "Accuracy too low: $acc > $requiredLocationAccuracy")
            return
        }

        // Handle first location or unlock location
        if (previousLat == null || previousLng == null) {
            updateLocation(lat, lng)
            traversalHistory.append("($lat,$lng)")
            sendAvatarLocationToWebView(lat, lng)
            saveDataToDatabase(traversalHistory.toString())
            return
        }

        // Handle unlock location update
        if (isWaitingForUnlockLocation) {
            updateLocation(lat, lng)
            TravelHistoryManager.updateLastUnlockWithLatLng(lat, lng, this)
            isWaitingForUnlockLocation = false
        }

        // Check for significant movement
        val latDiff = abs(previousLat!! - lat)
        val lngDiff = abs(previousLng!! - lng)

        if (!isDeviceMoving && latDiff < 0.00005 && lngDiff < 0.00005) {
            return
        }

        val distance = getDistance(previousLat!!, previousLng!!, lat, lng)

        if (distance in 0.1..maxDistanceCanCover.toDouble()) {
            if (traversalHistory.isNotEmpty() && traversalHistory.last() != '~') {
                traversalHistory.append("~")
            }
            traversalHistory.append("($lat,$lng)")
            minuteDistance += distance
            updateLocation(lat, lng)
            sendAvatarLocationToWebView(lat, lng)
        } else if (distance > maxDistanceCanCover) {
            maxDistanceCanCover += maxDistance
            Log.d("LocationUpdate", "Distance too large: $distance, increasing max to $maxDistanceCanCover")
        }
    }

    private fun updateLocation(lat: Double, lng: Double) {
        previousLat = lat
        previousLng = lng
    }

    private fun sendAvatarLocationToWebView(lat: Double, lng: Double) {
        val intent = Intent("AVATAR_LOCATION_UPDATE").apply {
            putExtra("latitude", lat)
            putExtra("longitude", lng)
        }
        sendBroadcast(intent)
    }

    private fun saveDataToDatabase(history: String) {
        val currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

        val jsonObj = JSONObject().apply {
            put("time", currentTime)
            put("history", history)
        }

        if (isAppInBackground(applicationContext)) {
            Log.d("LocationService", "App in background, adding to background history")
            TravelHistoryManager.addToBackgroundHistory(jsonObj)
        } else {
            val backArray = TravelHistoryManager.getBackgroundHistoryArrayAndClear()
            if (backArray != null) {
                Log.d("LocationService", "Sending history with background data")
                TravelHistoryManager.isSavingWithBoth = true
                jsonObj.put("back_history", backArray)
                jsonObj.put("type", "both")
            } else {
                jsonObj.put("type", "history")
            }

            val intent = Intent("travel_history").apply {
                putExtra("travel_history", jsonObj.toString())
            }
            sendBroadcast(intent)

            TravelHistoryManager.isSavingWithBoth = false
        }
    }

    // ====== LOCK/UNLOCK HISTORY MANAGEMENT ======
    object TravelHistoryManager {
        private val backgroundTravelHistoryList = mutableListOf<JSONObject>()
        private val lockHistoryList = mutableListOf<JSONObject>()
        var isSavingWithBoth: Boolean = false
        private var lastUnlockUpdatedIndex: Int = -1

        fun addToBackgroundHistory(json: JSONObject) {
            Log.d("TravelHistory", "Added to background history: $json")
            backgroundTravelHistoryList.add(json)
        }

        fun addLockHistory(json: JSONObject) {
            Log.d("LockHistory", "Added lock/unlock event: $json")
            lockHistoryList.add(json)
        }

        fun updateLastUnlockWithLatLng(lat: Double, lng: Double, context: Context) {
            Log.d("LockHistory", "Updating unlock location: $lat, $lng")

            for (i in lockHistoryList.size - 1 downTo 0) {
                val item = lockHistoryList[i]
                val status = item.optString("status")
                val latLng = item.optString("lat_lng")

                if (status == "Unlock" && latLng.isEmpty() && i != lastUnlockUpdatedIndex) {
                    item.put("lat_lng", "$lat,$lng")
                    lastUnlockUpdatedIndex = i
                    Log.d("LockHistory", "Updated unlock at index $i with coordinates")

                    // Send to WebView if app is in foreground
                    if (!isAppInBackground(context)) {
                        flushLockHistory(context)
                    }
                    break
                }
            }
        }

        fun getBackgroundHistoryArrayAndClear(): JSONArray? {
            return if (backgroundTravelHistoryList.isNotEmpty()) {
                val array = JSONArray().apply {
                    backgroundTravelHistoryList.forEach { put(it) }
                }
                Log.d("TravelHistory", "Returning background history, count: ${backgroundTravelHistoryList.size}")
                backgroundTravelHistoryList.clear()
                array
            } else {
                null
            }
        }

        fun flushLockHistory(context: Context) {
            Log.d("LockHistory", "Flushing lock history, size: ${lockHistoryList.size}")

            if (lockHistoryList.isEmpty()) {
                Log.d("LockHistory", "No lock history to flush")
                return
            }

            // Check if any entry has empty lat_lng
            val hasEmptyLocation = lockHistoryList.any { item ->
                val latLng = item.optString("lat_lng", "")
                val isEmpty = latLng.isEmpty() || latLng.isBlank()
                if (isEmpty) {
                    Log.d("LockHistory", "Found entry with empty location: ${item.optString("status")} at ${item.optString("time")}")
                }
                isEmpty
            }

            if (hasEmptyLocation) {
                Log.d("LockHistory", "Skipping flush - some entries have empty lat_lng, waiting for location updates")
                return
            }

            Log.d("LockHistory", "All entries have valid locations, proceeding with flush")
            sendLockHistoryToWebView(context)
        }

        fun flushBackgroundHistoryIfNeeded(context: Context) {
            if (isSavingWithBoth) {
                Log.d("TravelHistory", "Skipping background flush - saving with both")
                return
            }

            if (backgroundTravelHistoryList.isNotEmpty()) {
                val array = JSONArray().apply {
                    backgroundTravelHistoryList.forEach { put(it) }
                }

                val jsonObject = JSONObject().apply {
                    put("back_history", array)
                    put("type", "back_history")
                }

                val intent = Intent("travel_history").apply {
                    putExtra("travel_history", jsonObject.toString())
                }

                context.sendBroadcast(intent)
                Log.d("TravelHistory", "Flushed background history, count: ${array.length()}")
                backgroundTravelHistoryList.clear()
            }
        }

        private fun sendLockHistoryToWebView(context: Context) {
            val array = JSONArray().apply {
                lockHistoryList.forEach { put(it) }
            }

            val jsonObject = JSONObject().apply {
                put("lock_history", array)
                put("type", "lock_history")
            }

            val intent = Intent("lock_history").apply {
                putExtra("lock_history", jsonObject.toString())
            }

            Log.d("LockHistory", "Sending lock history to WebView: $array")
            context.sendBroadcast(intent)
            lockHistoryList.clear()
        }

        private fun isAppInBackground(context: Context): Boolean {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val appProcesses = activityManager.runningAppProcesses ?: return true
            val packageName = context.packageName

            for (appProcess in appProcesses) {
                if (appProcess.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
                    appProcess.processName == packageName) {
                    return false
                }
            }
            return true
        }
    }

    // ====== SCREEN LOCK/UNLOCK RECEIVER ======
    private var screenLockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d("ScreenLockReceiver", "📱 Screen turned OFF (device locked)")
                    onDeviceLocked()
                }
                Intent.ACTION_USER_PRESENT -> {
                    Log.d("ScreenLockReceiver", "🔓 Device unlocked")
                    onDeviceUnlocked()
                }
                Intent.ACTION_SCREEN_ON -> {
                    // Optional: handle screen on (but not unlocked)
                    Log.d("ScreenLockReceiver", "💡 Screen turned ON")
                }
            }
        }
    }

    private fun registerScreenLockReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_ON) // Optional
        }
        registerReceiver(screenLockReceiver, filter)
        Log.d("ScreenLockReceiver", "Screen lock receiver registered")
    }

    private fun onDeviceLocked() {
        isDeviceLocked = true
        val currentTime = getCurrentTime()

        // Use current location or last known location
        val lat = previousLat ?: 0.0
        val lng = previousLng ?: 0.0

        Log.d("LockHistory", "Device locked at $currentTime, location: $lat, $lng")

        val lockObj = JSONObject().apply {
            put("time", currentTime)
            put("status", "Lock")
            put("lat_lng", if (previousLat != null && previousLng != null) "$lat,$lng" else "")
        }

        TravelHistoryManager.addLockHistory(lockObj)

        // Send immediately if app is in foreground
        if (!isAppInBackground(this)) {
            TravelHistoryManager.flushLockHistory(this)
        }

        // Stop location updates to save battery when locked
        stopLocationUpdatesTemporarily()
    }

    private fun onDeviceUnlocked() {
        isDeviceLocked = false
        val currentTime = getCurrentTime()

        Log.d("LockHistory", "Device unlocked at $currentTime")

        // Create unlock entry with empty coordinates (will be updated when location is received)
        val unlockObj = JSONObject().apply {
            put("time", currentTime)
            put("status", "Unlock")
            put("lat_lng", "") // Will be updated in updateLastUnlockWithLatLng
        }

        TravelHistoryManager.addLockHistory(unlockObj)

        // Flag to update unlock location when next GPS fix is received
        isWaitingForUnlockLocation = true

        // Resume location updates
        startLocationManagerUpdates()
    }

    private fun stopLocationUpdatesTemporarily() {
        try {
            if (::saveRunnable.isInitialized) {
                handler.removeCallbacks(saveRunnable)
            }
            locationManager.removeUpdates(locationListener)
            Log.d("LocationService", "Location updates stopped (device locked)")
        } catch (e: Exception) {
            Log.e("LocationService", "Error stopping location updates", e)
        }
    }

    // ====== UTILITY METHODS ======
    private fun getCurrentTime(): String {
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
    }

    private fun getDelayToNextMinute(): Long {
        val now = Calendar.getInstance()
        val intervalMillis = reqLocSendInterval.toLongOrNull() ?: 60000L

        Log.d("LocationService", "Interval set to: ${intervalMillis}ms (${intervalMillis / 1000} seconds)")

        // Simply add the interval to current time
        val nextUpdateTime = now.timeInMillis + intervalMillis

        val nextTime = Calendar.getInstance().apply {
            timeInMillis = nextUpdateTime
        }

        Log.d("LocationService", "Next update scheduled in: ${intervalMillis}ms")
        Log.d("LocationService", "Next update time: ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(nextTime.time)}")

        return intervalMillis
    }

    private fun getDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }

    private val setServiceRunning: (Boolean) -> Unit = { isRunning ->
        getSharedPreferences("service_prefs", Context.MODE_PRIVATE).edit {
            putBoolean("isServiceRunning", isRunning)
        }
    }

    private fun isAppInBackground(context: Context): Boolean {
        val sp = context.getSharedPreferences("service_pref", Context.MODE_PRIVATE)
        return sp.getBoolean("isAppInBackground", false)
    }

    private fun setupMotionSensor() {
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        motionSensorListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                event?.let {
                    val dx = abs(lastAcceleration[0] - it.values[0])
                    val dy = abs(lastAcceleration[1] - it.values[1])
                    val dz = abs(lastAcceleration[2] - it.values[2])
                    val total = sqrt((dx * dx + dy * dy + dz * dz).toDouble())
                    isDeviceMoving = total > accelerationThreshold
                    lastAcceleration = it.values.clone()
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        sensorManager.registerListener(
            motionSensorListener,
            accelerometer,
            SensorManager.SENSOR_DELAY_UI
        )
    }

    @SuppressLint("NewApi")
    private fun startForegroundServiceWithNotification() {
        val channelId = "MyTaskServiceChannel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Background Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                description = "Location tracking service"
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("WeVOIS Payment App")
            .setContentText("Location tracking active")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()

        startForeground(1, notification)
    }

    override fun getTaskConfig(intent: Intent?): HeadlessJsTaskConfig? {
        return intent?.extras?.let {
            HeadlessJsTaskConfig("BackgroundTask", Arguments.fromBundle(it), 5000, true)
        }
    }

    override fun onDestroy() {
        try {
            // Stop location updates
            if (::saveRunnable.isInitialized) {
                handler.removeCallbacks(saveRunnable)
            }
            locationManager.removeUpdates(locationListener)

            // Unregister sensors and receivers
            motionSensorListener?.let { sensorManager.unregisterListener(it) }
            unregisterReceiver(screenLockReceiver)

            // Flush any remaining data
            TravelHistoryManager.flushBackgroundHistoryIfNeeded(this)
            TravelHistoryManager.flushLockHistory(this)

            stopForeground(true)
            setServiceRunning(false)

            Log.d("ServiceDestroy", "Service destroyed successfully")
        } catch (error: Exception) {
            Log.e("ServiceDestroy", "Error during cleanup", error)
        } finally {
            super.onDestroy()
        }
    }
}