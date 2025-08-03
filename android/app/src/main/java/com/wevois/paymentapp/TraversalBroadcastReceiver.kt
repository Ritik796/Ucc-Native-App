package com.wevois.paymentapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.modules.core.DeviceEventManagerModule
import org.json.JSONObject

class TraversalReceiverModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "AVATAR_LOCATION_UPDATE" -> {
                    val lat = intent.getDoubleExtra("latitude", 0.0)
                    val lng = intent.getDoubleExtra("longitude", 0.0)

                    val locationData = JSONObject().apply {
                        put("type", "avatar")
                        put("latitude", lat)
                        put("longitude", lng)
                    }

                    sendEvent("onAvatarLocationUpdate", locationData.toString())
                }


                "travel_history" -> {
                    val data = intent.getStringExtra("travel_history") ?: return
                    sendEvent("onTraversalUpdate", data)
                }

                else -> return
            }
        }
    }

    private var isReceiverRegistered = false

    init {
        registerReceiver()
    }

    private fun registerReceiver() {
        if (isReceiverRegistered) return

        try {
            val filter = IntentFilter().apply {
                addAction("AVATAR_LOCATION_UPDATE")
                addAction("travel_history")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                reactContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                ContextCompat.registerReceiver(
                    reactContext,
                    receiver,
                    filter,
                    ContextCompat.RECEIVER_EXPORTED
                )
            }

            isReceiverRegistered = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun sendEvent(eventName: String, data: String) {
        try {
            reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit(eventName, data)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun getName(): String {
        return "TraversalReceiver"
    }

    override fun invalidate() {
        super.invalidate()
        unregisterReceiver()
    }

    private fun unregisterReceiver() {
        if (isReceiverRegistered) {
            try {
                reactContext.unregisterReceiver(receiver)
                isReceiverRegistered = false
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
