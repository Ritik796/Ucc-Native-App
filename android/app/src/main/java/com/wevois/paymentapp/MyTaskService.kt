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
import android.widget.Toast
import com.facebook.react.HeadlessJsTaskService
import com.facebook.react.bridge.Arguments
import com.facebook.react.jstasks.HeadlessJsTaskConfig
import com.google.android.gms.tasks.Tasks
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*
import androidx.core.content.edit

class MyTaskService : HeadlessJsTaskService() {

    private lateinit var locationManager: LocationManager
    private lateinit var locationListener: LocationListener
    private lateinit var sensorManager: SensorManager
    private lateinit var motionSensorListener: SensorEventListener
    private var wakeLock: PowerManager.WakeLock? = null


    private val handler = Handler(Looper.getMainLooper())
    private lateinit var saveRunnable: Runnable

    private lateinit var userId: String
    private lateinit var dbPath: String
    private lateinit var travelPath: String

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

    override fun onCreate() {
        super.onCreate()
        startForegroundServiceWithNotification()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        setupMotionSensor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        userId = intent?.getStringExtra("USER_ID") ?: ""
        dbPath = intent?.getStringExtra("DB_PATH") ?: ""
        travelPath = intent?.getStringExtra("TRAVEL_PATH") ?: ""

        acquireWakeLock()
        setServiceRunning(true)
        startLocationTracking()

        return START_STICKY
    }




    @SuppressLint("InvalidWakeLockTag")
    private fun acquireWakeLock() {
        if (wakeLock == null || wakeLock?.isHeld == false) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocationTrackingWakeLock")
            wakeLock?.acquire(60 * 1000L) // Timeout after 1 minute
            Log.d("WakeLock", "Wake lock acquired for 1 minute")
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d("WakeLock", "Wake lock released")
            }
        }
        wakeLock = null
    }
    @SuppressLint("MissingPermission")
    private fun startLocationTracking() {
        Log.d("LocationManager", "startTracking called")

        locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val lat = location.latitude
                val lng = location.longitude
                val acc = location.accuracy

                Log.d("LocationUpdate", "Lat: $lat, Lng: $lng, Accuracy: $acc")
                if(acc > 15) return
                if (!hasFirstFix && isLocationTurnedOn()) {
                    hasFirstFix = true
                    val time = getCurrentTime()
                    saveLocationHistoryToFirebase("($lat,$lng)", "0.0", userId, dbPath, travelPath, time)
                }

                if (!isDeviceMoving) {
                    val latDiff = abs((previousLat ?: lat) - lat)
                    val lngDiff = abs((previousLng ?: lng) - lng)
                    if (latDiff < 0.00005 && lngDiff < 0.00005) {
                        Log.d("MotionFilter", "Ignored minor GPS jitter.")
                        return
                    }
                }

                val distance = previousLat?.let {
                    getDistance(it, previousLng!!, lat, lng)
                } ?: 0.0

                if (distance in 0.1..maxDistanceCanCover.toDouble()) {
                    if (traversalHistory.isNotEmpty() && traversalHistory.last() != '~') traversalHistory.append("~")
                    traversalHistory.append("($lat,$lng)")
                    minuteDistance += distance
                } else if (distance != 0.0) {
                    maxDistanceCanCover += maxDistance
                }

                if (previousLat == null || previousLng == null) {
                    traversalHistory.append("($lat,$lng)")
                }

                previousLat = lat
                previousLng = lng
            }
        }

        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            3000L,
            1f,
            locationListener
        )

        saveRunnable = object : Runnable {
            override fun run() {
                if (traversalHistory.isNotEmpty()) {
                    val currentTime = getCurrentTime()

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

                        Log.d("SaveRunnable", "Saving: $traversalHistory | Distance: $minuteDistance")

                        saveLocationHistoryToFirebase(
                            traversalHistory.toString(),
                            minuteDistance.toString(),
                            userId,
                            dbPath,
                            travelPath,
                            currentTime
                        )

                        traversalHistory.clear()
                        minuteDistance = 0.0
                        maxDistanceCanCover = maxDistance
                    } else {
                        if (pendingTraversalHistory == null) pendingTraversalHistory = StringBuilder()
                        if (pendingTraversalHistory!!.isNotEmpty() && pendingTraversalHistory!!.last() != '~') pendingTraversalHistory!!.append("~")
                        if (traversalHistory.first() == '~') traversalHistory.deleteCharAt(0)
                        pendingTraversalHistory!!.append(traversalHistory)
                        traversalHistory.clear()
                        Log.d("SaveRunnable", "Location disabled. Pending data stored.")
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

    private fun getCurrentTime(): String {
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
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

        val database = Firebase.database(dbPath).reference
        val totalDistanceRef = database.child("$travelPath/TotalCoveredDistance")
        val currentTimeRef = database.child("$travelPath/$currentTime")

        totalDistanceRef.get().addOnSuccessListener { snapshot ->
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

            val tasks = listOf(
                currentTimeRef.setValue(updates),
                database.child(travelPath).updateChildren(summary)
            )

            Tasks.whenAll(tasks).addOnSuccessListener {
                Log.d("FirebaseSave", "Saved to Firebase: $traversalHistory")
//                Toast.makeText(reactContext,"Saved to Firebase",Toast.LENGTH_LONG).show()
            }.addOnFailureListener {
                Log.e("FirebaseSave", "Error saving: ${it.message}", it)
//                Toast.makeText(reactContext,"Error saving : Firebase",Toast.LENGTH_LONG).show()
            }

        }.addOnFailureListener {
            Toast.makeText(reactContext,"Failed fetching",Toast.LENGTH_LONG).show()
            Log.e("FirebaseSave", "Failed fetching TotalCoveredDistance: ${it.message}", it)
        }
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
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }
    private val setServiceRunning: (Boolean) -> Unit = { isRunning ->
        getSharedPreferences("service_prefs", Context.MODE_PRIVATE).edit {
            putBoolean("isServiceRunning", isRunning)
        }
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
