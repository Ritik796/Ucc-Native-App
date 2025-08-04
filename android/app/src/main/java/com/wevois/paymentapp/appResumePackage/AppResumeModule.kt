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

        registerLifecycle()
    }

    fun initLifecycleTracking() {

        registerLifecycle()
    }

    fun stopLifecycleTracking() {
        unregisterLifecycle()
    }

    private fun registerLifecycle() {

        if (lifecycleCallbacks != null) {

            return
        }

        val app = reactContext.applicationContext as? Application
        if (app == null) {

            return
        }

        lifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {

                setAppIsBackground(false)
                sendDialogStatusToReactNative(false)
            }

            override fun onActivityPaused(activity: Activity) {

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
    }

    private fun unregisterLifecycle() {
        val app = reactContext.applicationContext as? Application
        if (app != null && lifecycleCallbacks != null) {
            app.unregisterActivityLifecycleCallbacks(lifecycleCallbacks!!)
        }
        lifecycleCallbacks = null
    }

    private fun setAppIsBackground(isAppBackground: Boolean) {
        val sharedPreferences = reactContext.getSharedPreferences("service_pref", Context.MODE_PRIVATE)
        sharedPreferences.edit {
            putBoolean("isAppInBackground", isAppBackground)
            apply()
        }

    }

    private fun sendDialogStatusToReactNative(isVisible: Boolean) {
        val map = Arguments.createMap().apply {
            putBoolean("dialog", isVisible)
        }



        reactContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit("onSystemDialogStatus", map)
    }
}
