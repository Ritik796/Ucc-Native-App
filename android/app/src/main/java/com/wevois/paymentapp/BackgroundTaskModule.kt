package com.wevois.paymentapp;

import android.annotation.SuppressLint
import android.content.Intent
import android.widget.Toast
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap
import android.util.Log

class BackgroundTaskModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    override fun getName(): String {
        return "BackgroundTaskModule"
    }

    @SuppressLint("NewApi")
    @ReactMethod
    fun startBackgroundTask(options: ReadableMap) {
        val context = reactApplicationContext

        val userId = if (options.hasKey("USER_ID")) options.getString("USER_ID") else null
        val dbPath = if (options.hasKey("DB_PATH")) options.getString("DB_PATH") else null

        Log.d("BackgroundTaskModule", "Received USER_ID: $userId")
        Log.d("BackgroundTaskModule", "Received DB_PATH: $dbPath")

        if (userId.isNullOrEmpty() || dbPath.isNullOrEmpty()) {
            Toast.makeText(context, "USER_ID or DB_PATH is missing", Toast.LENGTH_SHORT).show()
            Log.e("BackgroundTaskModule", "Missing USER_ID or DB_PATH")
            return
        }

//        Toast.makeText(context, "Background Service Started", Toast.LENGTH_SHORT).show()
        Log.i("BackgroundTaskModule", "Starting MyTaskService with USER_ID: $userId and DB_PATH: $dbPath")

        val serviceIntent = Intent(context, MyTaskService::class.java).apply {
            putExtra("USER_ID", userId)
            putExtra("DB_PATH", dbPath)
        }

        context.startForegroundService(serviceIntent)
        Log.d("BackgroundTaskModule", "startForegroundService called")
    }

    @ReactMethod
    fun stopBackgroundTask() {
        val context = reactApplicationContext

        val serviceIntent = Intent(context, MyTaskService::class.java)
        context.stopService(serviceIntent)
    }
}
