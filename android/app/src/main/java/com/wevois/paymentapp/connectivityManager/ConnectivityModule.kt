package com.wevois.paymentapp.connectivityManager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule
import java.util.Calendar
import android.provider.Settings
import androidx.core.net.toUri

class ConnectivityModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    private var isReceiverRegistered = false
    private var isCallbackRegistered = false
    private val connectivityManager =
        reactContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val locationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            sendOnlyLocationStatus()
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            sendOnlyNetworkStatus()
        }

        override fun onLost(network: Network) {
            Handler(Looper.getMainLooper()).postDelayed({
                sendOnlyNetworkStatus()
            }, 3000) // 3 seconds delay
        }
    }

    override fun getName(): String = "ConnectivityModule"

    override fun initialize() {
        super.initialize()
        register()
        sendInitialStatusToJS()  // <-- Send current state at startup
    }

    override fun invalidate() {
        super.invalidate()
        unregister()
    }

    private fun register() {
        if (!isCallbackRegistered) {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            try {
                connectivityManager.registerNetworkCallback(request, networkCallback)
                isCallbackRegistered = true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (!isReceiverRegistered) {
            val filter = IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    reactContext.registerReceiver(locationReceiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    ContextCompat.registerReceiver(
                        reactContext,
                        locationReceiver,
                        filter,
                        ContextCompat.RECEIVER_EXPORTED
                    )
                }
                isReceiverRegistered = true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun unregister() {
        if (isCallbackRegistered) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback)
                isCallbackRegistered = false
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (isReceiverRegistered) {
            try {
                reactContext.unregisterReceiver(locationReceiver)
                isReceiverRegistered = false
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // This function sends both statuses initially
    private fun sendInitialStatusToJS() {
        sendOnlyNetworkStatus()
        sendOnlyLocationStatus()
    }

    private fun sendOnlyNetworkStatus() {
        val isMobile = isInternetAvailable(reactContext)


        val mobileMap = Arguments.createMap().apply {
            putBoolean("isMobileDataOn", isMobile)
        }

        try {
            reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit("onConnectivityStatus", mobileMap)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun sendOnlyLocationStatus() {
        val isLocation = isDeviceLocationHighAccuracy(reactContext)


        val locationMap = Arguments.createMap().apply {
            putBoolean("isLocationOn", isLocation)
        }

        try {
            reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit("onLocationStatus", locationMap)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun isInternetAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = cm.getNetworkCapabilities(cm.activeNetwork)

        return capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
    }

    @Suppress("DEPRECATION")
   private fun isDeviceLocationHighAccuracy(context: Context): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return false

        // For Android Q (API 29) and above
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return LocationManagerCompat.isLocationEnabled(locationManager)
        }

        // For Android 4.4 (KitKat) to Android 9 (Pie)
        return try {
            val mode = Settings.Secure.getInt(context.contentResolver, Settings.Secure.LOCATION_MODE)
            mode == Settings.Secure.LOCATION_MODE_HIGH_ACCURACY
        } catch (e: Settings.SettingNotFoundException) {
            false
        }
    }

    @ReactMethod
    fun openAutoStartSettings() {
        try {
            val intent = Intent()
            val manufacturer = Build.MANUFACTURER.lowercase()

            when {
                manufacturer.contains("xiaomi") -> {
                    intent.setClassName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
                }
                manufacturer.contains("oppo") -> {
                    intent.setClassName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")
                }
                manufacturer.contains("vivo") -> {
                    intent.setClassName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
                }
                manufacturer.contains("realme") -> {
                    intent.setClassName("com.realme.securitycenter", "com.realme.securitycenter.startupapp.StartupAppListActivity")
                }
                manufacturer.contains("samsung") -> {
                    intent.action = "android.settings.APPLICATION_DETAILS_SETTINGS"
                    intent.data = ("package:" + reactContext.packageName).toUri()
                }
                else -> {
                    // fallback to app details settings
                    intent.action = android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                    intent.data = ("package:" + reactContext.packageName).toUri()
                }
            }

            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            reactContext.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }


    @ReactMethod
    fun startMonitoring() {

        register()
        sendInitialStatusToJS() // <-- Send immediately on JS call too
        startTimeChecker()
    }
    private val handler = Handler(Looper.getMainLooper())
    private var runnable: Runnable? = null

    private fun startTimeChecker() {

        runnable = object : Runnable {
            override fun run() {
                val calendar = Calendar.getInstance()
                val hour = calendar.get(Calendar.HOUR_OF_DAY)
                val minute = calendar.get(Calendar.MINUTE)

                if (hour == 23 && minute == 0) {
                    stopMonitoring()
                    stopTimeChecker()
                }

                handler.postDelayed(this, 60_000)
            }
        }

        handler.postDelayed(runnable!!, getDelayToNextMinute())
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


    private fun stopTimeChecker() {
        runnable?.let { handler.removeCallbacks(it) }
        runnable = null
    }
    @ReactMethod
    fun stopMonitoring() {

        unregister()
    }
}
