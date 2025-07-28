package com.wevois.paymentapp

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.*
import android.location.LocationManager
import android.media.RingtoneManager
import android.os.*
import android.util.Log
import android.widget.Toast
import com.facebook.react.HeadlessJsTaskService
import com.facebook.react.bridge.Arguments
import com.facebook.react.jstasks.HeadlessJsTaskConfig
import com.google.android.gms.location.*
import com.google.firebase.database.*
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import com.google.android.gms.tasks.Tasks
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*
import androidx.core.content.edit

class MyTaskService : HeadlessJsTaskService() {
    private lateinit var database: DatabaseReference
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private lateinit var locationCallback: LocationCallback
    private lateinit var userId: String
    private lateinit var dbPath: String
    private lateinit var travelPath: String

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var saveRunnable: Runnable

    private var previousLat: Double? = null
    private var previousLng: Double? = null
    private var maxDistanceCanCover = 15
    private val maxDistance = 15
    private var minuteDistance: Double = 0.0

    private val traversalHistory = StringBuilder()
    private var hasFirstFix = false

    private lateinit var sensorManager: SensorManager
    private var isDeviceMoving = false
    private var lastAcceleration = FloatArray(3)
    private var accelerationThreshold = 1.2f
    private lateinit var motionSensorListener: SensorEventListener
    private var lastMotionDetectedTime: Long = 0
    private val motionTimeoutMs = 3000

    private var pendingTraversalHistory: StringBuilder? = null

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundServiceWithNotification()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        setupMotionSensor()

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WevoisApp::MyTaskWakeLock")
        wakeLock?.acquire()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        userId = intent?.getStringExtra("USER_ID") ?: ""
        dbPath = intent?.getStringExtra("DB_PATH") ?: ""
        travelPath = intent?.getStringExtra("TRAVEL_PATH") ?: ""
        Log.d("StartLocationTraversal", "Service started with USER_ID = $userId")
        startTraversalTracking()
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startTraversalTracking() {
        locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000L)
            .setMinUpdateIntervalMillis(5000L)
            .setMinUpdateDistanceMeters(2f)
            .build()

        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            location?.let {
                if (it.accuracy <= 15 && isLocationTurnedOn()) {
                    val lat = it.latitude
                    val lng = it.longitude
                    previousLat = lat
                    previousLng = lng
                    hasFirstFix = true
                    sendAndSaveLocation("($lat,$lng)", "0.0")
                }
            }
        }

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                if (location.accuracy > 15 || !isDeviceMoving) return

                val lat = location.latitude
                val lng = location.longitude

                if (!hasFirstFix && isLocationTurnedOn()) {
                    hasFirstFix = true
                    sendAndSaveLocation("($lat,$lng)", "0.0")
                }

                if (previousLat != null && previousLng != null) {
                    val distance = getDistance(previousLat!!, previousLng!!, lat, lng)
                    if (distance < 2.0) return

                    if (distance > 0 && distance < maxDistanceCanCover) {
                        if (traversalHistory.isNotEmpty() && traversalHistory.last() != '~') {
                            traversalHistory.append("~")
                        }
                        traversalHistory.append("($lat,$lng)")
                        minuteDistance += distance
                    } else if (distance >= maxDistanceCanCover) {
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
                        if (!pendingTraversalHistory.isNullOrEmpty()) {
                            if (pendingTraversalHistory!!.last() != '~') pendingTraversalHistory!!.append("~")
                            if (traversalHistory.first() == '~') traversalHistory.deleteCharAt(0)
                            pendingTraversalHistory!!.append(traversalHistory)
                            traversalHistory.clear()
                            traversalHistory.append(pendingTraversalHistory.toString())
                            pendingTraversalHistory = null
                        }

                        if (traversalHistory.lastOrNull() == '~') {
                            traversalHistory.setLength(traversalHistory.length - 1)
                        }

                        sendAndSaveLocation(traversalHistory.toString(), minuteDistance.toString())

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
                    }
                }
                handler.postDelayed(this, getDelayToNextMinute())
            }
        }

        handler.postDelayed(saveRunnable, getDelayToNextMinute())
    }

    private fun sendAndSaveLocation(history: String, distance: String) {
        val currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        saveLocationHistoryToFirebase(history, distance, userId, dbPath, travelPath, currentTime)
    }

    private fun saveLocationHistoryToFirebase(
        traversalHistory: String,
        minuteDistance: String,
        userId: String,
        dbPath: String,
        travelPath: String,
        currentTime: String
    ) {
        if (traversalHistory.isEmpty() || userId.isEmpty() || dbPath.isEmpty() || travelPath.isEmpty()) return

        database = Firebase.database(dbPath).reference

        val totalCoveredDistanceRef = database.child("$travelPath/TotalCoveredDistance")
        val currentTimePathRef = database.child("$travelPath/$currentTime")

        totalCoveredDistanceRef.get().addOnSuccessListener { snapshot ->
            val existingDistance = snapshot.getValue(Double::class.java) ?: 0.0
            val totalDistance = (minuteDistance.toDoubleOrNull() ?: 0.0) + existingDistance

            val updates = mapOf(
                "distance-in-meter" to minuteDistance,
                "lat-lng" to traversalHistory
            )

            val summary = mapOf(
                "TotalCoveredDistance" to totalDistance,
                "last-update-time" to currentTime
            )

            val updateTasks = listOf(
                currentTimePathRef.setValue(updates),
                database.child(travelPath).updateChildren(summary)
            )

            Tasks.whenAll(updateTasks).addOnSuccessListener {
                Log.d("FirebaseSave", "Saved location history to Firebase")
//                Toast.makeText(reactContext,"Saved location history",Toast.LENGTH_LONG).show()
            }.addOnFailureListener {
                Log.e("FirebaseSave", "Error saving to Firebase", it)
//                Toast.makeText(reactContext,"Error saving history",Toast.LENGTH_LONG).show()
            }
        }.addOnFailureListener {
            Log.e("FirebaseSave", "Failed to fetch TotalCoveredDistance", it)
//            Toast.makeText(reactContext,"Error saving history",Toast.LENGTH_LONG).show()
        }
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
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    private fun setupMotionSensor() {
        motionSensorListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
                    val deltaX = abs(lastAcceleration[0] - event.values[0])
                    val deltaY = abs(lastAcceleration[1] - event.values[1])
                    val deltaZ = abs(lastAcceleration[2] - event.values[2])
                    val totalMovement = sqrt((deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ).toDouble())

                    if (totalMovement > accelerationThreshold) {
                        lastMotionDetectedTime = System.currentTimeMillis()
                    }

                    isDeviceMoving = (System.currentTimeMillis() - lastMotionDetectedTime) < motionTimeoutMs
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
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notification = Notification.Builder(this, channelId)
            .setContentTitle("WeVOIS Payment App")
            .setContentText("Background service is running")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setSmallIcon(R.drawable.rn_edit_text_material)
            .build()

        startForeground(1, notification)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val sharedPref = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        sharedPref.edit {
            putBoolean("isAppKilled", true)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(saveRunnable)
        fusedLocationClient.removeLocationUpdates(locationCallback)
        sensorManager.unregisterListener(motionSensorListener)
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(false)
        }
        setServiceRunning(false)
        super.onDestroy()
    }

    private val setServiceRunning: (Boolean) -> Unit = { isRunning ->
        getSharedPreferences("service_prefs", Context.MODE_PRIVATE).edit {
            putBoolean("isServiceRunning", isRunning)
        }
    }

    override fun getTaskConfig(intent: Intent?): HeadlessJsTaskConfig? {
        return intent?.extras?.let {
            HeadlessJsTaskConfig("BackgroundTask", Arguments.fromBundle(it), 5000, true)
        }
    }
}
