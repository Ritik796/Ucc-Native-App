package com.wevois.paymentapp

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
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



    override fun onCreate() {
        super.onCreate()
        startForegroundServiceWithNotification()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
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
        startLocationManagerUpdates()
        return START_STICKY
    }

    @SuppressLint("MissingPermission", "InvalidWakeLockTag")
    private fun startLocationManagerUpdates() {
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

        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            intervalMillis,
            distanceMeters,
            locationListener,
            Looper.getMainLooper()
        )

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
        Log.d("LocationUpdate","$lat,$lng  , $acc")
        val requiredLocationAccuracy: Float = reqLocAccuracy.toFloatOrNull() ?: 15f
        if (acc > requiredLocationAccuracy) return

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

    }



    fun saveDataToDatabase(history: String) {
        val currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

        val jsonObj = JSONObject().apply {
            put("time", currentTime)
            put("history", history)
        }

        if (isAppInBackground(applicationContext)) {
            Log.d("LocationUpdate", "App is in background. Adding to background history.")
            TravelHistoryManager.addToBackgroundHistory(jsonObj)
        } else {
            val backArray = TravelHistoryManager.getBackgroundHistoryArrayAndClear()
            if (backArray != null) {
                Log.d("TravelLog", "App in foreground. Sending history with background history (type = both)")
                TravelHistoryManager.isSavingWithBoth = true
                jsonObj.put("back_history", backArray)
                jsonObj.put("type", "both")
            } else {
                Log.d("LocationUpdate", "App in foreground. Sending single history (type = history)")
                jsonObj.put("type", "history")
            }

            val intent = Intent("travel_history").apply {
                putExtra("travel_history", jsonObj.toString())
            }
            sendBroadcast(intent)

            TravelHistoryManager.isSavingWithBoth = false
        }
    }


    object TravelHistoryManager {
        private val backgroundTravelHistoryList = mutableListOf<JSONObject>()
        var isSavingWithBoth: Boolean = false

        fun addToBackgroundHistory(json: JSONObject) {
            Log.d("LocationUpdate", "Added to backgroundTravelHistoryList: $json")
            backgroundTravelHistoryList.add(json)
        }

        fun getBackgroundHistoryArrayAndClear(): JSONArray? {
            return if (backgroundTravelHistoryList.isNotEmpty()) {
                val array = JSONArray().apply {
                    backgroundTravelHistoryList.forEach { put(it) }
                }
                Log.d("LocationUpdate", "Returning and clearing backgroundTravelHistoryList. Count = ${backgroundTravelHistoryList.size}")
                backgroundTravelHistoryList.clear()
                array
            } else {
                Log.d("LocationUpdate", "No background history to attach.")
                null
            }
        }

        fun flushBackgroundHistoryIfNeeded(context: Context) {
            Log.d("LocationUpdate", "flushBackgroundHistoryIfNeeded() skipped: isSavingWithBoth = $isSavingWithBoth")
            if (isSavingWithBoth) {
                Log.d("LocationUpdate", "flushBackgroundHistoryIfNeeded() skipped: isSavingWithBoth = true")
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

                Log.d("LocationUpdate", "Flushed background history. Count = ${array.length()}")
                backgroundTravelHistoryList.clear()
            } else {
                Log.d("LocationUpdate", "No background history to flush.")
            }
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

        // Use SENSOR_DELAY_UI or SENSOR_DELAY_NORMAL only if needed
        sensorManager.registerListener(
            motionSensorListener,
            accelerometer,
            SensorManager.SENSOR_DELAY_UI // ~60ms delay, lighter than SENSOR_DELAY_NORMAL
        )
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

    private fun acquireWakeLock() {
        if (wakeLock == null || !wakeLock!!.isHeld) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "wevois:location_tracking_wakelock"
            )
            wakeLock?.acquire(10 * 60 * 1000L)
        }
    }

    private fun renewWakeLockIfNeeded() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
        acquireWakeLock()
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    override fun onDestroy() {
        handler.removeCallbacks(saveRunnable)
        locationManager.removeUpdates(locationListener)
        motionSensorListener?.let { sensorManager.unregisterListener(it) }
        releaseWakeLock()
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
