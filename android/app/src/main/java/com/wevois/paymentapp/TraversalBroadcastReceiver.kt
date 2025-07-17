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

class TraversalReceiverModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Validate the intent action for security
            if (intent?.action != "com.wevois.TRAVERSAL_HISTORY") {
                return
            }

            val data = intent.getStringExtra("travel_history") ?: return
            sendEvent(data)
        }
    }

    private var isReceiverRegistered = false

    init {
        registerReceiver()
    }

    private fun registerReceiver() {
        if (isReceiverRegistered) return

        try {
            val filter = IntentFilter("com.wevois.TRAVERSAL_HISTORY")

            // Fix for Android 13+ compatibility - use RECEIVER_EXPORTED for external broadcasts
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
            // Handle registration errors gracefully
            e.printStackTrace()
        }
    }

    private fun sendEvent(data: String) {
        try {
            reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit("onTraversalUpdate", data)
        } catch (e: Exception) {
            // Handle event sending errors
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
                // Handle unregistration errors gracefully
                e.printStackTrace()
            }
        }
    }
}