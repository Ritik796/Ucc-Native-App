package com.wevois.paymentapp.appResumePackage

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.core.content.edit
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.modules.core.DeviceEventManagerModule

class AppResumeModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    private var lifecycleCallbacks: Application.ActivityLifecycleCallbacks? = null

    override fun getName(): String = "AppResumeModule"

    override fun initialize() {
        super.initialize()
        Log.d("AppResumeModule", "initialize called")
        registerLifecycle()
    }

    fun initLifecycleTracking() {
        Log.d("AppResumeModule", "initialize called")
        registerLifecycle()
    }

    fun stopLifecycleTracking() {
        unregisterLifecycle()
    }

    private fun registerLifecycle() {
        Log.d("AppResumeModule", "Lifecycle Started")
        if (lifecycleCallbacks != null) {
            Log.d("AppResumeModule", "Lifecycle already registered")
            return
        }

        val app = reactContext.applicationContext as? Application
        if (app == null) {
            Log.e("AppResumeModule", "Application context is null")
            return
        }

        lifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                Log.d("AppResumeModule", "onActivityResumed")
                setAppIsBackground(false)
                sendDialogStatusToReactNative(false)
            }

            override fun onActivityPaused(activity: Activity) {
                Log.d("AppResumeModule", "onActivityPaused")
                setAppIsBackground(true)
                sendDialogStatusToReactNative(true)
            }

            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityDestroyed(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        }

        app.registerActivityLifecycleCallbacks(lifecycleCallbacks!!)
        Log.d("AppResumeModule", "Lifecycle registered")
    }

    private fun unregisterLifecycle() {
        val app = reactContext.applicationContext as? Application
        if (app != null && lifecycleCallbacks != null) {
            app.unregisterActivityLifecycleCallbacks(lifecycleCallbacks!!)
            Log.d("AppResumeModule", "Lifecycle unregistered")
        }
        lifecycleCallbacks = null
    }

    private fun setAppIsBackground(isAppBackground: Boolean) {
        val sharedPreferences = reactContext.getSharedPreferences("service_pref", Context.MODE_PRIVATE)
        sharedPreferences.edit {
            putBoolean("isAppInBackground", isAppBackground)
            apply()
        }
        Log.d("AppResumeModule", "isAppInBackground set to $isAppBackground")
    }

    private fun sendDialogStatusToReactNative(isVisible: Boolean) {
        val map = Arguments.createMap().apply {
            putBoolean("dialog", isVisible)
        }

        Log.d("AppResumeModule", "Sending event to JS: onSystemDialogStatus = $isVisible")

        reactContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit("onSystemDialogStatus", map)
    }
}
