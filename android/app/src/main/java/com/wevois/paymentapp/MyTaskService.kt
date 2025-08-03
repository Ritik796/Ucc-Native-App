package com.wevois.paymentapp


import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.*
import android.location.Location

import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import com.facebook.react.HeadlessJsTaskService
import com.facebook.react.bridge.Arguments
import com.facebook.react.jstasks.HeadlessJsTaskConfig
import com.google.android.gms.location.*
import com.google.android.gms.location.LocationRequest
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*


class MyTaskService : HeadlessJsTaskService() {

    private lateinit var fusedClient: FusedLocationProviderClient

    private lateinit var locationCallback: LocationCallback

    private lateinit var sensorManager: SensorManager
    private var motionSensorListener: SensorEventListener? = null
    private var wakeLock: PowerManager.WakeLock? = null

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
    private val backgroundTravelHistoryList = mutableListOf<JSONObject>()

    override fun onCreate() {
        super.onCreate()
        startForegroundServiceWithNotification()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        setupMotionSensor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        reqLocAccuracy = intent?.getStringExtra("LOCATION_ACCURACY") ?: "15"
        reqLocDistance = intent?.getStringExtra("LOCATION_UPDATE_DISTANCE") ?: "1"
        reqLocInterval = intent?.getStringExtra("LOCATION_UPDATE_INTERVAL") ?: "3000"
        reqLocSendInterval = intent?.getStringExtra("LOCATION_SEND_INTERVAL") ?: "1"
        acquireWakeLock()
        setServiceRunning(true)
        startFusedLocationTracking()
        return START_STICKY
    }

    @SuppressLint("InvalidWakeLockTag")
    private fun acquireWakeLock() {
        if (wakeLock == null || !wakeLock!!.isHeld) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocationTrackingWakeLock")
            wakeLock?.acquire(10 * 60 * 1000L)
            Log.d("WakeLock", "Wake lock acquired")
        }
    }

    private fun renewWakeLockIfNeeded() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        acquireWakeLock()
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
            Log.d("WakeLock", "Wake lock released")
        }
        wakeLock = null
    }

    @SuppressLint("MissingPermission")
    private fun startFusedLocationTracking() {
        val intervalMillis = reqLocInterval.toLongOrNull() ?: 3000L
        val distanceMeters = reqLocDistance.toFloatOrNull() ?: 1f

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,  // priority
            intervalMillis                    // interval in milliseconds
        ).apply {
            setMinUpdateIntervalMillis(intervalMillis / 2)    // fastest interval
            setMinUpdateDistanceMeters(distanceMeters)        // minimum distance change
        }.build()


        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (location in result.locations) {
                    handleLocationUpdate(location)
                }
            }
        }

        fusedClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())

        saveRunnable = object : Runnable {
            override fun run() {
                renewWakeLockIfNeeded()
                if (traversalHistory.isNotEmpty()) {
                    while (traversalHistory.endsWith("~")) traversalHistory.setLength(traversalHistory.length - 1)
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
        Log.d("LocationUpdate", "Lat: $lat, Lng: $lng, Accuracy: $acc")
        val requiredLocationAccuracy: Float = reqLocAccuracy.toFloatOrNull() ?: 15f
        if(acc > requiredLocationAccuracy) return
        if (previousLat == null || previousLng == null) {
            previousLat = lat
            previousLng = lng
            traversalHistory.append("($lat,$lng)")
            sendAvatarLocationToWebView(lat, lng)
            saveDataToDatabase(traversalHistory.toString())
            return
        }

        val latDiff = abs(previousLat!! - lat)
        val lngDiff = abs(previousLng!! - lng)

        if (!isDeviceMoving && latDiff < 0.00005 && lngDiff < 0.00005) {
            Log.d("LocationUpdate", "Ignored minor GPS jitter.")
            return
        }

        val distance = getDistance(previousLat!!, previousLng!!, lat, lng)
        if (distance in 0.1..maxDistanceCanCover.toDouble()) {
            if (traversalHistory.isNotEmpty() && traversalHistory.last() != '~') {
                traversalHistory.append("~")
            }
            traversalHistory.append("($lat,$lng)")
            minuteDistance += distance
            sendAvatarLocationToWebView(lat, lng)
        } else if (distance != 0.0) {
            maxDistanceCanCover += maxDistance
        }

        previousLat = lat
        previousLng = lng
    }

    private fun sendAvatarLocationToWebView(lat: Double, lng: Double) {
        val intent = Intent("AVATAR_LOCATION_UPDATE").apply {
            putExtra("latitude", lat)
            putExtra("longitude", lng)
        }
        sendBroadcast(intent)
        Log.d("LocationUpdate", "Broadcast sent: ($lat, $lng)")
    }

    private fun saveDataToDatabase(history: String) {
        val currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val jsonObj = JSONObject().apply {
            put("time", currentTime)
            put("history", history)
        }

        if (isAppInBackground(applicationContext)) {
            backgroundTravelHistoryList.add(jsonObj)
            Log.d("LocationUpdate", "App in background. Stored: $jsonObj")
        } else {
            if (backgroundTravelHistoryList.isNotEmpty()) {
                val jsonArray = JSONArray()
                backgroundTravelHistoryList.forEach { jsonArray.put(it) }
                jsonObj.put("back_history", jsonArray)
                backgroundTravelHistoryList.clear()
            }
            val intent = Intent("travel_history").apply {
                putExtra("travel_history", jsonObj.toString())
            }
            sendBroadcast(intent)
            Log.d("LocationUpdate", "Broadcast travel_history: $jsonObj")
        }
    }

    private fun getDelayToNextMinute(): Long {
        val now = Calendar.getInstance()
        val nextMin = reqLocSendInterval.toIntOrNull() ?: 1
        val nextMinute = (now.clone() as Calendar).apply {
            add(Calendar.MINUTE, nextMin)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return nextMinute.timeInMillis - now.timeInMillis
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
        sensorManager.registerListener(motionSensorListener, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
    }

    @SuppressLint("NewApi")
    private fun startForegroundServiceWithNotification() {
        val channelId = "MyTaskServiceChannel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Background Service", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("WeVOIS Payment App")
            .setContentText("Background service is running")
            .setOngoing(true)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()

        startForeground(1, notification)
    }

    override fun onDestroy() {
        handler.removeCallbacks(saveRunnable)
        fusedClient.removeLocationUpdates(locationCallback)
        motionSensorListener?.let { sensorManager.unregisterListener(it) }
        releaseWakeLock()
        @Suppress("DEPRECATION")
        stopForeground(true)
        setServiceRunning(false)
        super.onDestroy()
    }

    override fun getTaskConfig(intent: Intent?): HeadlessJsTaskConfig? {
        return intent?.extras?.let {
            HeadlessJsTaskConfig("BackgroundTask", Arguments.fromBundle(it), 5000, true)
        }
    }
}
