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
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule
import java.util.Calendar
//import android.provider.Settings

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
        Log.d("ConnectivityModule", "Network status: isMobile=$isMobile")

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
        val isLocation = isDeviceLocationOn(reactContext)
        Log.d("ConnectivityModule", "Location status: isLocation=$isLocation")

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


  private  fun isDeviceLocationOn(context: Context): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return LocationManagerCompat.isLocationEnabled(locationManager)
    }


    @ReactMethod
    fun startMonitoring() {
        Log.d("Connection Listener", "Started")
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
        Log.d("Connection Listener", "Ended")
        unregister()
    }
}
