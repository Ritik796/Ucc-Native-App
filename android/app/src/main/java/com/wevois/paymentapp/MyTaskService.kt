package com.wevois.paymentapp

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.facebook.react.HeadlessJsTaskService
import com.facebook.react.bridge.Arguments
import com.facebook.react.jstasks.HeadlessJsTaskConfig
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*
import android.widget.Toast


class MyTaskService : HeadlessJsTaskService() {

    private lateinit var locationManager: LocationManager
    private lateinit var userId: String
    private lateinit var dbPath: String
    private lateinit var locationListener: LocationListener

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var saveRunnable: Runnable

    private var previousLat: Double? = null
    private var previousLng: Double? = null
    private var maxDistanceCanCover = 15
    private val maxDistance = 15
    private var minuteDistance: Double = 0.0

    private val traversalHistory = StringBuilder()

    override fun onCreate() {
        super.onCreate()
        startForegroundServiceWithNotification()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
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
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())

        // Initial last known location
        locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let {
            val lat = it.latitude
            val lng = it.longitude
            previousLat = lat
            previousLng = lng
            Log.d("InitialLocation", "Sending initial location: ($lat,$lng)")
            sendLocationHistoryToWebView("($lat,$lng)", "0.0", userId, dbPath)
        }

        // Live continuous location updates
        locationListener = LocationListener { location ->
            val lat = location.latitude
            val lng = location.longitude
            val currentTime = sdf.format(Date())

            Log.d("LocationListener", "onLocationChanged: Lat=$lat, Lng=$lng at $currentTime")

            if (previousLat != null && previousLng != null) {
                val distance = getDistance(previousLat!!, previousLng!!, lat, lng)
                Log.d("DistanceCheck", "Distance = $distance meters | Condition: ($distance > 0 && $distance < $maxDistanceCanCover)")

                if (distance > 0 && distance < maxDistanceCanCover) {
                    Log.d("TraversalUpdate", "Appended: ($lat,$lng) | minuteDistance = ${minuteDistance + distance}")
                    traversalHistory.append("($lat,$lng)~")
                    minuteDistance += distance
                } else if (distance != 0.0) {
                    maxDistanceCanCover += maxDistance
                }
            } else {
                Log.d("TraversalUpdate", "First point appended: ($lat,$lng)")
                traversalHistory.append("($lat,$lng)~")
            }

            previousLat = lat
            previousLng = lng
        }

        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            5000L,
            0f,
            locationListener
        )

        // Save runnable logic
        saveRunnable = object : Runnable {
            override fun run() {
                val calendar = Calendar.getInstance()
                val currentTime = String.format("%02d:%02d", calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE))
                Log.d("SaveRunnable", "Executing at $currentTime | traversalHistory length = ${traversalHistory.length}")

                if (traversalHistory.isNotEmpty()) {
                    if (traversalHistory.last() == '~') {
                        traversalHistory.setLength(traversalHistory.length - 1)
                    }

                    Log.d("TraversalSave", "Final history before sending: $traversalHistory")
                    sendLocationHistoryToWebView(
                        traversalHistory.toString(),
                        minuteDistance.toString(),
                        userId,
                        dbPath
                    )

                    traversalHistory.clear()
                    minuteDistance = 0.0
                    maxDistanceCanCover = maxDistance
                }

                // Schedule next run
                handler.postDelayed(this, getDelayToNextMinute())
            }
        }

        // Start saveRunnable immediately for the first minute cycle
        handler.postDelayed(saveRunnable, getDelayToNextMinute())
    }

    // Helper function to calculate delay to start of next minute
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
//        Toast.makeText(applicationContext, "Sending Location", Toast.LENGTH_SHORT).show()
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
        sendBroadcast(intent)
        Log.d("SendBroadcast", "Broadcasting at $currentTime: $history")
    }

    private fun getDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
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
            channel.enableVibration(true)
            channel.setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                Notification.AUDIO_ATTRIBUTES_DEFAULT
            )
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
        locationManager.removeUpdates(locationListener)
        stopForeground(true)
        super.onDestroy()
    }

    override fun getTaskConfig(intent: Intent?): HeadlessJsTaskConfig? {
        return intent?.extras?.let {
            HeadlessJsTaskConfig("BackgroundTask", Arguments.fromBundle(it), 5000, true)
        }
    }
}
