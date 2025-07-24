package com.wevois.paymentapp

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationManager
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.facebook.react.HeadlessJsTaskService
import com.facebook.react.bridge.Arguments
import com.facebook.react.jstasks.HeadlessJsTaskConfig
import com.google.android.gms.location.*
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

class MyTaskService : HeadlessJsTaskService() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private lateinit var locationCallback: LocationCallback
    private lateinit var userId: String
    private lateinit var dbPath: String

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var saveRunnable: Runnable

    private var previousLat: Double? = null
    private var previousLng: Double? = null
    private var maxDistanceCanCover = 15
    private val maxDistance = 15
    private var minuteDistance: Double = 0.0

    private val traversalHistory = StringBuilder()
    private var hasFirstFix = false

    private val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // Motion detection
    private lateinit var sensorManager: SensorManager
    private var isDeviceMoving = false
    private var lastAcceleration = FloatArray(3)
    private var accelerationThreshold = 0.5f
    private lateinit var motionSensorListener: SensorEventListener

    // Pending data cache when location is off
    private var pendingTraversalHistory: StringBuilder? = null


    override fun onCreate() {
        super.onCreate()
        startForegroundServiceWithNotification()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        setupMotionSensor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        userId = intent?.getStringExtra("USER_ID") ?: ""
        dbPath = intent?.getStringExtra("DB_PATH") ?: ""
        Log.d("StartLocationTraversal", "Service started with USER_ID = $userId")
        startTraversalTracking()
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startTraversalTracking() {
        locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            10000L
        )
            .setMinUpdateIntervalMillis(5000L)
            .setMinUpdateDistanceMeters(2f)
            .build()

//        Toast.makeText(reactContext, "Location Tracking start", Toast.LENGTH_SHORT).show()

        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            location?.let {
                if (it.accuracy <= 15 && isLocationTurnedOn()) {
                    val lat = it.latitude
                    val lng = it.longitude
                    previousLat = lat
                    previousLng = lng
                    hasFirstFix = true
                    sendLocationHistoryToWebView("($lat,$lng)", "0.0", userId, dbPath)
                    Log.d("InitialFix", "Initial location: $lat, $lng at ${Date(System.currentTimeMillis())}")
                }
            }
        }

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                if (location.accuracy > 15) {
                    Log.d("FusedLocation", "Ignored inaccurate location: ${location.accuracy}")
                    return
                }

                val lat = location.latitude
                val lng = location.longitude
                val currentTime = sdf.format(Date())
                Log.d("FusedLocation", "Accurate location: Lat: $lat, Lng: $lng at $currentTime")

                if (!hasFirstFix && isLocationTurnedOn()) {
                    hasFirstFix = true
                    sendLocationHistoryToWebView("($lat,$lng)", "0.0", userId, dbPath)
                }

                if (!isDeviceMoving) {
                    val latDiff = abs((previousLat ?: location.latitude) - location.latitude)
                    val lngDiff = abs((previousLng ?: location.longitude) - location.longitude)
                    if (latDiff < 0.00005 && lngDiff < 0.00005) {
                        Log.d("MotionFilter", "Ignored jitter location: ($latDiff, $lngDiff)")
                        return
                    }
                }

                if (previousLat != null && previousLng != null) {
                    val distance = getDistance(previousLat!!, previousLng!!, lat, lng)
                    if (distance > 0 && distance < maxDistanceCanCover) {
                        if (traversalHistory.isNotEmpty() && traversalHistory.last() != '~') {
                            traversalHistory.append("~")
                        }
                        traversalHistory.append("($lat,$lng)")
                        minuteDistance += distance
                    } else if (distance != 0.0) {
                        maxDistanceCanCover += maxDistance
                    }
                } else {
                    if (traversalHistory.isNotEmpty() && traversalHistory.last() != '~') {
                        traversalHistory.append("~")
                    }
                    traversalHistory.append("($lat,$lng)")
                }

                previousLat = lat
                previousLng = lng
            }
        }

        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())

        saveRunnable = object : Runnable {
            override fun run() {
                if (traversalHistory.isNotEmpty()) {
                    if (isLocationTurnedOn()) {
                        // Merge pending with current history using single "~"
                        if (!pendingTraversalHistory.isNullOrEmpty()) {
                            if (pendingTraversalHistory!!.last() != '~') {
                                pendingTraversalHistory!!.append("~")
                            }
                            if (traversalHistory.first() == '~') {
                                traversalHistory.deleteCharAt(0)
                            }
                            pendingTraversalHistory!!.append(traversalHistory)
                            traversalHistory.clear()
                            traversalHistory.append(pendingTraversalHistory.toString())
                            pendingTraversalHistory = null
                        }

                        // Remove trailing "~" if present
                        if (traversalHistory.lastOrNull() == '~') {
                            traversalHistory.setLength(traversalHistory.length - 1)
                        }

                        sendLocationHistoryToWebView(
                            traversalHistory.toString(),
                            minuteDistance.toString(),
                            userId,
                            dbPath
                        )

                        traversalHistory.clear()
                        minuteDistance = 0.0
                        maxDistanceCanCover = maxDistance
                    } else {
                        // Save for later
                        if (pendingTraversalHistory == null) {
                            pendingTraversalHistory = StringBuilder()
                        } else if (pendingTraversalHistory!!.isNotEmpty() && pendingTraversalHistory!!.last() != '~') {
                            pendingTraversalHistory!!.append("~")
                        }

                        if (traversalHistory.first() == '~') {
                            traversalHistory.deleteCharAt(0)
                        }

                        pendingTraversalHistory!!.append(traversalHistory)
                        traversalHistory.clear()
                        Log.d("TraversalSave", "Location off. Data saved for retry.")
                    }
                }

                handler.postDelayed(this, getDelayToNextMinute())
            }
        }

        handler.postDelayed(saveRunnable, getDelayToNextMinute())
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

    private fun sendLocationHistoryToWebView(history: String, distance: String, userId: String, dbPath: String) {
        val currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val intent = Intent("com.wevois.TRAVERSAL_HISTORY")

        val jsonObject = JSONObject().apply {
            put("path", history)
            put("time", currentTime)
            put("distance", distance)
            put("userId", userId)
            put("dbPath", dbPath)
        }

        intent.putExtra("travel_history", jsonObject.toString())
//        Toast.makeText(reactContext, "Sending Location to web view", Toast.LENGTH_SHORT).show()
        sendBroadcast(intent)
        Log.d("Broadcast", "Traversal history sent: $jsonObject")
//        Toast.makeText(reactContext, "Location Send", Toast.LENGTH_SHORT).show()
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
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    private fun setupMotionSensor() {
        motionSensorListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
                    val deltaX = abs(lastAcceleration[0] - event.values[0])
                    val deltaY = abs(lastAcceleration[1] - event.values[1])
                    val deltaZ = abs(lastAcceleration[2] - event.values[2])
                    val totalMovement = sqrt((deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ).toDouble())
                    isDeviceMoving = totalMovement > accelerationThreshold
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
                channelId,
                "Background Service",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                enableVibration(true)
                setShowBadge(false)
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), Notification.AUDIO_ATTRIBUTES_DEFAULT)
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

    override fun onDestroy() {
        handler.removeCallbacks(saveRunnable)
        fusedLocationClient.removeLocationUpdates(locationCallback)
        sensorManager.unregisterListener(motionSensorListener)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        super.onDestroy()
    }

    override fun getTaskConfig(intent: Intent?): HeadlessJsTaskConfig? {
        return intent?.extras?.let {
            HeadlessJsTaskConfig("BackgroundTask", Arguments.fromBundle(it), 5000, true)
        }
    }
}
