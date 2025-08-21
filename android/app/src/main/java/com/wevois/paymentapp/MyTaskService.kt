package com.wevois.paymentapp


import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.edit
import com.facebook.react.HeadlessJsTaskService
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.database.FirebaseDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt


class MyTaskService : HeadlessJsTaskService() {


    private lateinit var fusedLocationClient: FusedLocationProviderClient

    //    private lateinit var locationRequest: LocationRequest
    private lateinit var locationCallback: LocationCallback
    private lateinit var reqLocAccuracy: String
    private lateinit var reqLocDistance: String
    private lateinit var reqLocInterval: String
    private lateinit var reqLocSendInterval: String
    private lateinit var lockHistoryPath: String
    private lateinit var dbPath: String
    private var previousLat: Double? = null
    private var previousLng: Double? = null
    private val maxDistance = 15
    private var maxDistanceCanCover = maxDistance
    private val traversalHistory = StringBuilder()
    private lateinit var saveRunnable: Runnable
    private val handler = Handler(Looper.getMainLooper())
    private var isWaitingForUnlockLocation = false
    override fun onCreate() {
        super.onCreate()
        registerScreenLockReceiver()
        startForegroundServiceWithNotification()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        reqLocAccuracy = intent?.getStringExtra("LOCATION_ACCURACY") ?: "15"
        reqLocDistance = intent?.getStringExtra("LOCATION_UPDATE_DISTANCE") ?: "1"
        reqLocInterval = intent?.getStringExtra("LOCATION_UPDATE_INTERVAL") ?: "3000"
        reqLocSendInterval = intent?.getStringExtra("LOCATION_SEND_INTERVAL") ?: "1"
        lockHistoryPath = intent?.getStringExtra("LOCK_HISTORY_PATH") ?: ""
        dbPath = intent?.getStringExtra("DB_PATH") ?: ""
        startTraversalTracking()
        setServiceRunning(true)
        return START_STICKY
    }

    // Location Tracking Logic start
    @SuppressLint("MissingPermission")
    private fun startTraversalTracking() {
        val intervalMillis = reqLocInterval.toLongOrNull() ?: 3000L
        val distanceMeters = reqLocDistance.toFloatOrNull() ?: 1f
        val locationRequest =
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMillis).apply {
                setMinUpdateIntervalMillis(intervalMillis / 2)
                setMinUpdateDistanceMeters(distanceMeters)
            }.build()


        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (location in result.locations) {
                    handleLocationUpdate(location)
                }
            }

        }


        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
        saveRunnable = object : Runnable {
            override fun run() {
                if (traversalHistory.isNotEmpty()) {
                    while (traversalHistory.endsWith("~")) {
                        traversalHistory.setLength(traversalHistory.length - 1)
                    }
                    saveDataToDatabase(traversalHistory.toString())
                    traversalHistory.clear()
                    maxDistanceCanCover = maxDistance
                }
                handler.postDelayed(this, getDelayToNextMinute())
            }
        }
        handler.postDelayed(saveRunnable, getDelayToNextMinute())
    }


    @SuppressLint("MissingPermission")
    private fun handleLocationUpdate(location: Location) {
        val lat = location.latitude
        val lng = location.longitude
        val acc = location.accuracy
        val provider = location.provider
        val speed = location.speed // meters/second


        Log.d(
            "LocationUpdate",
            "Lat: $lat, Lng: $lng, Accuracy: $acc, Speed: $speed, Provider: $provider"
        )

        // Validate coordinates
        if (lat.isNaN() || lng.isNaN()) {
            Log.e("LocationUpdate", "Invalid coordinates received")
            return
        }

        // Accuracy check
        val requiredLocationAccuracy = reqLocAccuracy.toFloatOrNull() ?: 15f
        if (acc > requiredLocationAccuracy) {
            Log.d("LocationUpdate", "Accuracy too low: $acc > $requiredLocationAccuracy")
            return
        }



        // Handle first location
        if (previousLat == null || previousLng == null) {
            updateLocation(lat, lng)
            traversalHistory.append("($lat,$lng)")
            sendAvatarLocationToWebView(lat, lng, acc.toDouble())
            saveDataToDatabase(traversalHistory.toString())

            return
        }
        if (isWaitingForUnlockLocation) {
            if (lat == 0.0 || lng == 0.0) return
            val unlockObj = JSONObject().apply {
                put("status", "Unlock")
                put("lat_lng", "$lat,$lng")
            }
            saveLockUnLockHistory(unlockObj, dbPath, lockHistoryPath)
            isWaitingForUnlockLocation = false

        }
        val distance = getDistance(previousLat!!, previousLng!!, lat, lng) // in meters

        if (distance > 0 && distance < maxDistanceCanCover) {
            if (traversalHistory.isNotEmpty() && traversalHistory.last() != '~') {
                traversalHistory.append("~")
            }
            traversalHistory.append("($lat,$lng)")

            updateLocation(lat, lng)
            sendAvatarLocationToWebView(lat, lng,acc.toDouble())

        } else if (distance != 0.0 && distance <= 100) {
            if (traversalHistory.isNotEmpty() && traversalHistory.last() != '~') {
                traversalHistory.append("~")
            }
            traversalHistory.append("($lat,$lng)")
            updateLocation(lat, lng)
            sendAvatarLocationToWebView(lat, lng,acc.toDouble())
            maxDistanceCanCover = maxDistance * 2

        } else if (distance > 100) {
            maxDistanceCanCover += maxDistance
        }

    }


    private fun updateLocation(lat: Double, lng: Double) {
        previousLat = lat
        previousLng = lng
    }

    private fun getDelayToNextMinute(): Long {
        val now = Calendar.getInstance()
        val intervalMillis = reqLocSendInterval.toLongOrNull() ?: 60000L

        Log.d(
            "LocationUpdate",
            "Interval set to: ${intervalMillis}ms (${intervalMillis / 1000} seconds)"
        )

        // Simply add the interval to current time
        val nextUpdateTime = now.timeInMillis + intervalMillis

        val nextTime = Calendar.getInstance().apply {
            timeInMillis = nextUpdateTime
        }
        Log.d("LocationUpdate", "Next update scheduled in: ${intervalMillis}ms")
        Log.d(
            "LocationUpdate",
            "Next update time: ${
                SimpleDateFormat(
                    "HH:mm:ss",
                    Locale.getDefault()
                ).format(nextTime.time)
            }"
        )
        return intervalMillis
    }

    private fun sendAvatarLocationToWebView(lat: Double, lng: Double, acc: Double) {
        val intent = Intent("AVATAR_LOCATION_UPDATE").apply {
            putExtra("latitude", lat)
            putExtra("longitude", lng)
            putExtra("Accuracy", acc)
        }
        Log.d("LocationUpdate", "AVATAR_LOCATION_UPDATE $lat,$lng")
        sendBroadcast(intent)
    }

    private fun saveDataToDatabase(history: String) {
        val currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

        val jsonObj = JSONObject().apply {
            put("time", currentTime)
            put("history", history)
        }

        if (isAppInBackground(applicationContext)) {
            Log.d("LocationUpdate", "App in background, adding to background history")
            TravelHistoryManager.addToBackgroundHistory(jsonObj)
        } else {
            val backArray = TravelHistoryManager.getBackgroundHistoryArrayAndClear()
            if (backArray != null) {
                Log.d("LocationUpdate", "Sending history with background data")
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

    private fun isAppInBackground(context: Context): Boolean {
        val sp = context.getSharedPreferences("service_pref", Context.MODE_PRIVATE)
        return sp.getBoolean("isAppInBackground", false)
    }

    private fun getDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a =
            sin(dLat / 2).pow(2.0) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(
                dLon / 2
            ).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadius * c
    }

    // Location Tracking Logic End

    private val setServiceRunning: (Boolean) -> Unit = { isRunning ->
        getSharedPreferences("service_prefs", Context.MODE_PRIVATE).edit {
            putBoolean("isServiceRunning", isRunning)
        }
    }

    @SuppressLint("NewApi")
    private fun startForegroundServiceWithNotification() {
        val channelId = "MyTaskServiceChannel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Background Service",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                enableVibration(true)
                setShowBadge(false)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    Notification.AUDIO_ATTRIBUTES_DEFAULT
                )
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notification = Notification.Builder(this, channelId)
            .setContentTitle("WeVOIS Payment App")
            .setContentText("Background service is running")
            .setOngoing(true)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()

        startForeground(1, notification)
    }

    // Travel History Manager service
    object TravelHistoryManager {
        private val backgroundTravelHistoryList = mutableListOf<JSONObject>()
        var isSavingWithBoth: Boolean = false

        fun addToBackgroundHistory(json: JSONObject) {
            Log.d("LocationUpdate", "Added to background history: $json")
            backgroundTravelHistoryList.add(json)
        }

        fun getBackgroundHistoryArrayAndClear(): JSONArray? {
            return if (backgroundTravelHistoryList.isNotEmpty()) {
                val array = JSONArray().apply {
                    backgroundTravelHistoryList.forEach { put(it) }
                }
                Log.d(
                    "TravelHistory",
                    "Returning background history, count: ${backgroundTravelHistoryList.size}"
                )
                backgroundTravelHistoryList.clear()
                array
            } else {
                null
            }
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
                Log.d("LocationUpdate", "Flushed background history, count: ${array.length()}")
                backgroundTravelHistoryList.clear()
            }
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
            }
        }
    }

    private fun registerScreenLockReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenLockReceiver, filter)
        Log.d("LocationUpdate", "Screen lock receiver registered")
    }

    private fun onDeviceLocked() {

        val currentTime = getCurrentTime()

        // Use current location or last known location
        val lat = previousLat ?: 0.0
        val lng = previousLng ?: 0.0

        Log.d("LocationUpdate", "Device locked at $currentTime, location: $lat, $lng")
        if (lat == 0.0 || lng == 0.0) return
        val lockObj = JSONObject().apply {

            put("status", "Lock")
            put("lat_lng", if (previousLat != null && previousLng != null) "$lat,$lng" else "")
        }


        saveLockUnLockHistory(lockObj, dbPath, lockHistoryPath)
        stopLocationTracking()
    }

    private fun stopLocationTracking() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        Log.d("LocationUpdate", "Tracking Stop")
    }

    private fun onDeviceUnlocked() {
        // Flag to update unlock entry with next GPS fix
        startTraversalTracking()
        Log.d("LocationUpdate", "Tracking Start")
        isWaitingForUnlockLocation = true
    }

    private fun saveLockUnLockHistory(json: JSONObject, dbPath: String, lockHistoryPath: String) {
        try {
            val database = FirebaseDatabase.getInstance(dbPath)
            val reference = database.getReference(lockHistoryPath)

            // Convert JSONObject → Map<String, Any>
            val map = mutableMapOf<String, Any>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                map[key] = json.get(key)
            }


            val currentTime = getCurrentTime()  // Example: "09:25"

            reference.child(currentTime).setValue(map)
                .addOnSuccessListener {
                    Log.d("LockHistory", "✅ Saved at $dbPath/$lockHistoryPath/$currentTime : $map")
                }
                .addOnFailureListener { e ->
                    Log.e("LockHistory", "❌ Error saving", e)
                }
        } catch (e: Exception) {
            Log.e("LockHistory", "❌ Exception: ${e.message}", e)
        }
    }


    // ====== UTILITY METHODS ======
    private fun getCurrentTime(): String {
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
    }

    @Suppress("DEPRECATION")
    override fun onDestroy() {
        try {
            // Stop location updates
            if (::saveRunnable.isInitialized) {
                handler.removeCallbacks(saveRunnable)
            }
            fusedLocationClient.removeLocationUpdates(locationCallback)
            // Unregister sensors and receivers

            unregisterReceiver(screenLockReceiver)

            // Flush any remaining data
            TravelHistoryManager.flushBackgroundHistoryIfNeeded(this)


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