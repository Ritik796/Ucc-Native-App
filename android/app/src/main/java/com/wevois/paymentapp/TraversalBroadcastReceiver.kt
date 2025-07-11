package com.wevois.paymentapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.modules.core.DeviceEventManagerModule

class TraversalReceiverModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val data = intent?.getStringExtra("travel_history") ?: return
            sendEvent(data)
        }
    }

    init {
        val filter = IntentFilter("com.wevois.TRAVERSAL_HISTORY")
        reactContext.registerReceiver(receiver, filter)
    }

    private fun sendEvent(data: String) {
        reactContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit("onTraversalUpdate", data)
    }

    override fun getName(): String {
        return "TraversalReceiver"
    }
}
