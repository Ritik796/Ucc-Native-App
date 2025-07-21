package com.wevois.paymentapp.appResumePackage

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.modules.core.DeviceEventManagerModule

class AppResumeModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    override fun getName(): String = "AppResumeModule"

    override fun initialize() {
        super.initialize()
        Log.d("AppResumeModule", "initialize called")
        registerLifecycle()
    }

    private fun registerLifecycle() {
        val app = reactContext.applicationContext as? Application
        if (app == null) {
            Log.e("AppResumeModule", "Application context is null")
            return
        }

        Log.d("Test", "registerLifecycle called")

        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                Log.d("AppResumeModule", "onActivityResumed")
                // You can also send false here if you want to reset the dialog status
                sendDialogStatusToReactNative(reactContext, false)
            }

            override fun onActivityPaused(activity: Activity) {
                Log.d("AppResumeModule", "System dialog might be shown")
                sendDialogStatusToReactNative(reactContext, true)
            }

            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {
                Log.d("AppResumeModule", "System dialog might be shown")
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityDestroyed(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        })
    }

    // ✅ Move this function outside the inner class
    private fun sendDialogStatusToReactNative(reactContext: ReactContext, isVisible: Boolean) {
        Log.d("AppResumeModule", "sendDialogStatusToReactNative: $isVisible")
        val map = Arguments.createMap().apply {
            putBoolean("dialog", isVisible)
        }

        reactContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit("onSystemDialogStatus", map)
    }
}
