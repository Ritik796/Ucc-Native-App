package com.wevois.paymentapp

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.*
import android.location.*
import android.media.RingtoneManager
import android.os.*
import android.util.Log
import com.facebook.react.HeadlessJsTaskService
import com.facebook.react.bridge.Arguments
import com.facebook.react.jstasks.HeadlessJsTaskConfig
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*
import androidx.core.content.edit
import androidx.core.location.LocationManagerCompat
import org.json.JSONObject

class MyTaskService : HeadlessJsTaskService() {

    private lateinit var locationManager: LocationManager
    private lateinit var locationListener: LocationListener
    private lateinit var sensorManager: SensorManager
    private lateinit var motionSensorListener: SensorEventListener
    private var wakeLock: PowerManager.WakeLock? = null

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var saveRunnable: Runnable

    private lateinit var reqLocAccuracy: String
    private lateinit var reqLocDistance: String
    private lateinit var reqLocInterval: String
    private lateinit var reqLocSendInterval: String

    private var previousLat: Double? = null
    private var previousLng: Double? = null
    private var maxDistanceCanCover = 15
    private val maxDistance = 15
    private var minuteDistance: Double = 0.0
    private val traversalHistory = StringBuilder()
    private var hasFirstFix = false

    private var isDeviceMoving = false
    private var lastAcceleration = FloatArray(3)
    private var accelerationThreshold = 0.5f
    private var pendingTraversalHistory: StringBuilder? = null
    private val backgroundTravelHistoryList = mutableListOf<JSONObject>()

    override fun onCreate() {
        super.onCreate()
        startForegroundServiceWithNotification()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        setupMotionSensor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        reqLocAccuracy = intent?.getStringExtra("LOCATION_ACCURACY") ?: ""
        reqLocDistance = intent?.getStringExtra("LOCATION_UPDATE_DISTANCE") ?: ""
        reqLocInterval = intent?.getStringExtra("LOCATION_UPDATE_INTERVAL") ?: ""
        reqLocSendInterval = intent?.getStringExtra("LOCATION_SEND_INTERVAL") ?: ""
        startLocationTracking()
        acquireWakeLock()
        setServiceRunning(true)


        return START_STICKY
    }

    @SuppressLint("InvalidWakeLockTag")
    private fun acquireWakeLock() {
        if (wakeLock == null || !wakeLock!!.isHeld) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "LocationTrackingWakeLock"
            )
            wakeLock?.acquire(2 * 60 * 1000L) // 2 minutes
            Log.d("WakeLock", "Wake lock acquired")
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
            Log.d("WakeLock", "Wake lock released")
        }
        wakeLock = null
    }

    @SuppressLint("MissingPermission")
    private fun startLocationTracking() {
        Log.d("LocationUpdate", "startTracking called")
        val intervalMillis = reqLocInterval.toLongOrNull() ?: 3000L
        val distanceMeters = reqLocDistance.toIntOrNull()?.toFloat() ?: 1f
        val locAccuracy = reqLocAccuracy.toFloatOrNull() ?: 15f
        Log.d("LocationUpdate"," intervalMillis $intervalMillis distanceMeters : $distanceMeters locAccuracy : $locAccuracy ")
        locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val lat = location.latitude
                val lng = location.longitude
                val acc = location.accuracy
                Log.d("LocationUpdate", "Lat: $lat, Lng: $lng, Accuracy: $acc")
                if (acc > locAccuracy) return
                if (previousLat == null || previousLng == null) {
                    previousLat = lat
                    previousLng = lng
                    hasFirstFix = true
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
                val shouldAddPoint = distance in 0.1..maxDistanceCanCover.toDouble()

                if (shouldAddPoint) {
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

            override fun onProviderEnabled(provider: String) {
                Log.d("LocationUpdate", "$provider enabled")
            }

            override fun onProviderDisabled(provider: String) {
                Log.d("LocationUpdate", "$provider disabled")
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }

        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            intervalMillis,
            distanceMeters,
            locationListener
        )
        saveRunnable = object : Runnable {
            override fun run() {
                acquireWakeLock() // Reacquire each minute
                if (traversalHistory.isNotEmpty()) {
                    if (isLocationTurnedOn()) {
                        if (!pendingTraversalHistory.isNullOrEmpty()) {
                            if (pendingTraversalHistory!!.last() != '~') pendingTraversalHistory!!.append("~")
                            while (pendingTraversalHistory!!.endsWith("~")) pendingTraversalHistory!!.setLength(pendingTraversalHistory!!.length - 1)
                            if (traversalHistory.first() == '~') traversalHistory.deleteCharAt(0)
                            pendingTraversalHistory!!.append("~").append(traversalHistory)
                            traversalHistory.clear()
                            traversalHistory.append(pendingTraversalHistory.toString())
                            pendingTraversalHistory = null
                        }

                        while (traversalHistory.endsWith("~")) traversalHistory.setLength(traversalHistory.length - 1)

                        saveDataToDatabase(traversalHistory.toString())
                        traversalHistory.clear()
                        minuteDistance = 0.0
                        maxDistanceCanCover = maxDistance
                    } else {
                        if (pendingTraversalHistory == null) pendingTraversalHistory = StringBuilder()
                        if (pendingTraversalHistory!!.isNotEmpty() && pendingTraversalHistory!!.last() != '~') {
                            pendingTraversalHistory!!.append("~")
                        }
                        if (traversalHistory.first() == '~') traversalHistory.deleteCharAt(0)
                        pendingTraversalHistory!!.append(traversalHistory)
                        traversalHistory.clear()
                        Log.d("LocationUpdate", "Location disabled. Stored to pending.")
                    }
                }

                handler.postDelayed(this, getDelayToNextMinute())
            }
        }

        handler.postDelayed(saveRunnable, getDelayToNextMinute())
    }

    private fun sendAvatarLocationToWebView(lat: Double, lng: Double) {
        val intent = Intent("AVATAR_LOCATION_UPDATE")
        intent.putExtra("latitude", lat)
        intent.putExtra("longitude", lng)
        sendBroadcast(intent)
        Log.d("LocationUpdate", "Broadcast sent: ($lat, $lng)")
    }

    private fun saveDataToDatabase(history: String) {
        val currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val jsonObj = JSONObject().apply {
            put("time", currentTime)
            put("history", history)
        }

        val intent = Intent("travel_history")

        if (isAppInBackground(applicationContext)) {
            backgroundTravelHistoryList.add(jsonObj)
            Log.d("LocationUpdate", "App is in background. Stored to backgroundTravelHistoryList: $jsonObj")
        } else {
            // If there are background entries, send them as JSONArray
            if (backgroundTravelHistoryList.isNotEmpty()) {
                val jsonArray = org.json.JSONArray()
                backgroundTravelHistoryList.forEach { jsonArray.put(it) }

                intent.putExtra("back_history", jsonArray.toString())
                backgroundTravelHistoryList.clear()
                Log.d("LocationUpdate", "Sending back_history to web: $jsonArray")
            }

            // Send current travel history
            intent.putExtra("travel_history", jsonObj.toString())
            sendBroadcast(intent)
            Log.d("LocationUpdate", "Sending travel_history to web: $jsonObj")
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
        val earthRadius = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadius * c
    }

    private fun isLocationTurnedOn(): Boolean {
        val locationManager = applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            LocationManagerCompat.isLocationEnabled(locationManager)
        } else {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }

    private val setServiceRunning: (Boolean) -> Unit = { isRunning ->
        getSharedPreferences("service_prefs", Context.MODE_PRIVATE).edit {
            putBoolean("isServiceRunning", isRunning)
        }
    }

    private fun isAppInBackground(context: Context): Boolean {
        val sharedPreferences = context.getSharedPreferences("service_pref", Context.MODE_PRIVATE)
        return sharedPreferences.getBoolean("isAppInBackground", false)
    }

    private fun setupMotionSensor() {
        motionSensorListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
                    val dx = abs(lastAcceleration[0] - event.values[0])
                    val dy = abs(lastAcceleration[1] - event.values[1])
                    val dz = abs(lastAcceleration[2] - event.values[2])
                    val total = sqrt((dx * dx + dy * dy + dz * dz).toDouble())
                    isDeviceMoving = total > accelerationThreshold
                    lastAcceleration = event.values.clone()
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        sensorManager.registerListener(motionSensorListener, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
    }

    @SuppressLint("NewApi")
    private fun startForegroundServiceWithNotification() {
        val channelId = "MyTaskServiceChannel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Background Service", NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                enableVibration(true)
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), null)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notification = Notification.Builder(this, channelId)
            .setContentTitle("WeVOIS Payment App")
            .setContentText("Background service running")
            .setOngoing(true)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()

        startForeground(1, notification)
    }

    override fun onDestroy() {
        handler.removeCallbacks(saveRunnable)
        locationManager.removeUpdates(locationListener)
        sensorManager.unregisterListener(motionSensorListener)
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
