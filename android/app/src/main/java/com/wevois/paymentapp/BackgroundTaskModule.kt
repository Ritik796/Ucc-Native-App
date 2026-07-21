package com.wevois.paymentapp


import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.ReadableType
import com.facebook.react.modules.core.DeviceEventManagerModule
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.wevois.paymentapp.MyTaskService.TravelHistoryManager.flushBackgroundHistoryIfNeeded
import java.io.File
import java.io.RandomAccessFile
import java.util.Calendar

class BackgroundTaskModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    private var lastStartTime = 0L
    private val minStartInterval = 10_000L // 10 seconds
    private val handler = Handler(Looper.getMainLooper())
    private var runnable: Runnable? = null
    private var serverTimeListener: ValueEventListener? = null
    private var serverTimeRef: DatabaseReference? = null
    private var currentServerTimePath: String? = null
    val context: ReactApplicationContext = reactApplicationContext

    override fun getName(): String {
        return "BackgroundTaskModule"
    }

    @SuppressLint("NewApi")
    @ReactMethod
    fun startBackgroundTask(options: ReadableMap) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastStartTime < minStartInterval) return
        lastStartTime = currentTime



        val accuracy = options.getStringSafe("LOCATION_ACCURACY")
        val updateDistance = options.getStringSafe("LOCATION_UPDATE_DISTANCE")
        val sendDelay = options.getStringSafe("LOCATION_SEND_INTERVAL")
        val updateInterval = options.getStringSafe("LOCATION_UPDATE_INTERVAL")
        val serverTimePath = options.getStringSafe("SERVER_TIME_PATH")
        val dbPath = options.getStringSafe("DB_PATH")
        val locHistoryPath = options.getStringSafe("LOCK_HISTORY_PATH")
        val travelPath = options.getStringSafe("TRAVEL_PATH")

        if (accuracy.isNullOrEmpty() || updateDistance.isNullOrEmpty() ||
            sendDelay.isNullOrEmpty() || updateInterval.isNullOrEmpty()
        ) return

        val serviceIntent = Intent(context, MyTaskService::class.java).apply {
            putExtra("LOCATION_ACCURACY", accuracy)
            putExtra("LOCATION_UPDATE_DISTANCE", updateDistance)
            putExtra("LOCATION_UPDATE_INTERVAL", updateInterval)
            putExtra("LOCATION_SEND_INTERVAL", sendDelay)
            putExtra("LOCK_HISTORY_PATH",locHistoryPath)
            putExtra("DB_PATH",dbPath)
            putExtra("TRAVEL_PATH", travelPath)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }

        startServerTimeListener(dbPath.toString(), serverTimePath.toString())

        scheduleHealthCheck()
        startTimeChecker()
    }

    @ReactMethod
    fun checkAndRestartBackgroundTask(options: ReadableMap) {
        val sendDelay = options.getStringSafe("LOCATION_SEND_INTERVAL")
        val updateInterval = options.getStringSafe("LOCATION_UPDATE_INTERVAL")
        val accuracy = options.getStringSafe("LOCATION_ACCURACY")
        val updateDistance = options.getStringSafe("LOCATION_UPDATE_DISTANCE")
        val serverTimePath = options.getStringSafe("SERVER_TIME_PATH")
        val dbPath = options.getStringSafe("DB_PATH")
        val locHistoryPath = options.getStringSafe("LOCK_HISTORY_PATH")
        val travelPath = options.getStringSafe("TRAVEL_PATH")

        if (accuracy.isNullOrEmpty() || updateDistance.isNullOrEmpty() ||
            sendDelay.isNullOrEmpty() || updateInterval.isNullOrEmpty()
        ) return

        if (!isServiceRunning(MyTaskService::class.java)) {
            val serviceIntent = Intent(context, MyTaskService::class.java).apply {
                putExtra("LOCATION_ACCURACY", accuracy)
                putExtra("LOCATION_UPDATE_DISTANCE", updateDistance)
                putExtra("LOCATION_UPDATE_INTERVAL", updateInterval)
                putExtra("LOCATION_SEND_INTERVAL", sendDelay)
                putExtra("LOCK_HISTORY_PATH",locHistoryPath)
                putExtra("DB_PATH",dbPath)
                putExtra("TRAVEL_PATH", travelPath)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
        startServerTimeListener(dbPath.toString(), serverTimePath.toString())
        Handler(Looper.getMainLooper()).postDelayed({flushBackgroundHistoryIfNeeded(context)},3000)
        scheduleHealthCheck()
        startTimeChecker()
    }

    /**
     * Point 8: WorkManager periodic health-check enqueue karo (har 15 min — WorkManager ka minimum).
     * KEEP policy: agar pehle se scheduled hai to dobara nahi banata. Ye tabhi kuch karta hai jab
     * service dead ho par tracking ON honi chahiye (LocationServiceRestartWorker guard sambhalta hai).
     */
    private fun scheduleHealthCheck() {
        val work = PeriodicWorkRequestBuilder<LocationServiceRestartWorker>(15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            LocationServiceRestartWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            work
        )
        Log.i("LocTrack", "🩺 WorkManager health-check scheduled (har 15 min: service alive check + restart)")
        // Fast layer: ~2.5 min AlarmManager watchdog (WorkManager ke 15-min gap ko bharta hai).
        ServiceWatchdogAlarmReceiver.schedule(context)
    }

    private fun cancelHealthCheck() {
        WorkManager.getInstance(context).cancelUniqueWork(LocationServiceRestartWorker.WORK_NAME)
        ServiceWatchdogAlarmReceiver.cancel(context)
        Log.i("LocTrack", "🩺 WorkManager + AlarmManager health-check cancelled (tracking intentionally band)")
    }

    private fun ReadableMap.getStringSafe(key: String): String? {
        if (!this.hasKey(key)) return null

        val dynamic = this.getDynamic(key)
        return if (dynamic.isNull) {
            null
        } else {
            when (dynamic.type) {
                ReadableType.String -> dynamic.asString()
                ReadableType.Number -> dynamic.asDouble().toString()
                else -> null
            }
        }
    }

    @SuppressLint("MissingPermission", "DeprecatedMethod")
    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val activityManager =
            reactApplicationContext.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        @Suppress("DEPRECATION")
        for (service in activityManager.getRunningServices(Int.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }

    /**
     * Sirf tracking band karo — service + time checker + server listener. Koi cache/data wipe NAHI.
     * 23:00 auto-stop aur server kill-switch ise use karte hain, kyunki un par poora app data uda
     * dena galat tha: config + un-flushed background location buffer (service_prefs) bhi udd jaata
     * tha, jisse START_STICKY recovery tootti thi aur last points kho jaate the.
     */
    private fun stopServiceOnly() {
        val context = reactApplicationContext
        val serviceIntent = Intent(context, MyTaskService::class.java)
        context.stopService(serviceIntent)
        cancelHealthCheck()
        stopTimeChecker()
        stopServerTimeListener()
        Log.w("LocTrack", "⏹️ stopServiceOnly — tracking band (data safe, koi nuke nahi)")
    }

    @ReactMethod
    fun stopBackgroundTask() {
        // Logout path: tracking band + poora app data wipe (fresh state). Ye deliberate hai —
        // isliye nuke sirf yahin hota hai, 23:00 / kill-switch par nahi.
        Log.w("LocTrack", "⛔ stopBackgroundTask() CALLED — service stop + cache nuke (logout). Call stack:\n" +
                Log.getStackTraceString(Throwable("stopBackgroundTask trace")))
        stopServiceOnly()
        clearAppCacheNuclear()
    }

    private fun startTimeChecker() {
        runnable = object : Runnable {
            override fun run() {
                val calendar = Calendar.getInstance()
                val hour = calendar.get(Calendar.HOUR_OF_DAY)
                val minute = calendar.get(Calendar.MINUTE)

                if (hour == 23 && minute == 0) {
                    // Raat 11 baje ke baad tracking nahi karni — sirf clean stop, data wipe NAHI.
                    Log.w("LocTrack", "⛔ 23:00 auto-stop trigger (device time $hour:$minute) — tracking band (data safe)")
                    stopServiceOnly()
                    stopTimeChecker()
                }

                handler.postDelayed(this, 60_000)
            }
        }

        handler.postDelayed(runnable!!, getDelayToNextMinute())
    }

    private fun stopTimeChecker() {
        runnable?.let { handler.removeCallbacks(it) }
        runnable = null
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

    // ✅ Working Realtime Listener
    @ReactMethod




   private fun startServerTimeListener(dbPath: String, serverTimePath: String) {
        val baseUrl = dbPath.trimEnd('/')
        val relativePath = serverTimePath.trimStart('/')
        currentServerTimePath = relativePath

        // Avoid constructing full URL, just use FirebaseDatabase.getInstance()
        try {
            // Firebase offline persistence — ye is DB instance ki PEHLI usage hai (getReference se pehle
            // enable karna zaroori, warna setPersistenceEnabled throw karta hai). MyTaskService bhi isi
            // shared helper ko call karti hai; jo pehle chale wahi enable kar dega (process me ek baar).
            MyTaskService.ensureFirebasePersistence(baseUrl)
            val db = FirebaseDatabase.getInstance(baseUrl)
            serverTimeRef = db.getReference(relativePath)

            Log.d("ServerTimeMonitor", "📡 Initialized reference at path: $relativePath")

            serverTimeListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val serverTimeValue = snapshot.value?.toString() ?: ""
                    Log.d("ServerTimeMonitor", "⏱ Server time updated: $serverTimeValue")

                    if (serverTimeValue.isEmpty()) {
                        reactApplicationContext
                            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                            .emit("onServerTimeStatus", "false")
                    }
                    else{
                        Log.w("LocTrack", "⛔ serverTime value NON-EMPTY ($serverTimeValue) → server kill-switch: tracking band (data safe, nuke nahi)")
                        reactApplicationContext
                            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                            .emit("onServerTimeStatus", "true")
                    stopServiceOnly()
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e("ServerTimeMonitor", "❌ Server time listener cancelled: ${error.message}")
                }
            }

            serverTimeRef?.addValueEventListener(serverTimeListener!!)
            Log.d("ServerTimeMonitor", "✅ Listener added successfully.")

        } catch (e: Exception) {
            Log.e("ServerTimeMonitor", "🚨 Failed to initialize Firebase reference: ${e.message}")
        }
    }

// App Cache clear functionality
@ReactMethod
fun clearAppCacheNuclear() {
    try {
        Log.d("App_Cache", "💥 NUCLEAR: Complete app data and cache destruction...")

        var totalCleared = 0
        var totalErrors = 0

        // 1. NUKE ALL CACHE DIRECTORIES
        val allCacheDirs = listOfNotNull(
            context.cacheDir,
            context.externalCacheDir,
            context.codeCacheDir,
            context.noBackupFilesDir
        )


        allCacheDirs.forEach { cacheDir ->
            if (cacheDir.exists()) {
                val success = nukeDirectory(cacheDir)
                if (success) {
                    totalCleared++
                    Log.d("App_Cache", "💥 NUKED: ${cacheDir.name}")
                } else {
                    totalErrors++
                    Log.w("App_Cache", "⚠️ Failed to nuke: ${cacheDir.name}")
                }
            }
        }

        // 2. NUKE ALL FILES DIRECTORY CONTENTS
        try {
            context.filesDir.listFiles()?.forEach { file ->
                // Don't delete the lib directory (contains native libraries)
                if (!file.name.equals("lib", ignoreCase = true)) {
                    val success = if (file.isDirectory) {
                        nukeDirectory(file)
                    } else {
                        nukeFile(file)
                    }

                    if (success) {
                        totalCleared++
                        Log.d("App_Cache", "💥 NUKED filesDir: ${file.name}")
                    }
                }
            }
        } catch (e: Exception) {
            totalErrors++
            Log.e("App_Cache", "❌ Error nuking filesDir: ${e.message}")
        }

        // 3. NUKE ALL DATABASES
        try {
            val databasesDir = File(context.filesDir.parent, "databases")
            if (databasesDir.exists()) {
                val success = nukeDirectory(databasesDir)
                if (success) {
                    totalCleared++
                    Log.d("App_Cache", "💥 ALL DATABASES NUKED")
                } else {
                    totalErrors++
                    Log.w("App_Cache", "⚠️ Failed to nuke databases")
                }
            }
        } catch (e: Exception) {
            totalErrors++
            Log.e("App_Cache", "❌ Error nuking databases: ${e.message}")
        }

        // 4. NUKE ALL SHARED PREFERENCES
        try {
            val sharedPrefsDir = File(context.filesDir.parent, "shared_prefs")
            if (sharedPrefsDir.exists()) {
                val success = nukeDirectory(sharedPrefsDir)
                if (success) {
                    totalCleared++
                    Log.d("App_Cache", "💥 ALL SHARED PREFERENCES NUKED")
                } else {
                    totalErrors++
                    Log.w("App_Cache", "⚠️ Failed to nuke shared preferences")
                }
            }
        } catch (e: Exception) {
            totalErrors++
            Log.e("App_Cache", "❌ Error nuking shared preferences: ${e.message}")
        }

        // 5. NUKE ALL EXTERNAL STORAGE (if exists)
        try {
            val externalFilesDir = context.getExternalFilesDir(null)
            if (externalFilesDir != null && externalFilesDir.exists()) {
                val success = nukeDirectory(externalFilesDir)
                if (success) {
                    totalCleared++
                    Log.d("App_Cache", "💥 EXTERNAL STORAGE NUKED")
                }
            }
        } catch (e: Exception) {
            totalErrors++
            Log.e("App_Cache", "❌ Error nuking external storage: ${e.message}")
        }

        // 6. NUKE WEB VIEW DATA
        try {
            val webViewDirs = listOf(
                File(context.cacheDir, "webview"),
                File(context.cacheDir, "WebView"),
                File(context.cacheDir, "org.chromium.android_webview"),
                File(context.filesDir, "webview"),
                File(context.filesDir, "WebView"),
                File(context.filesDir, "app_webview"),
                File(context.cacheDir, "app_webview")
            )

            webViewDirs.forEach { dir ->
                if (dir.exists()) {
                    nukeDirectory(dir)
                    Log.d("App_Cache", "💥 WebView nuked: ${dir.name}")
                }
            }
        } catch (e: Exception) {
            totalErrors++
            Log.e("App_Cache", "❌ Error nuking WebView: ${e.message}")
        }

        // 7. NUKE ALL TEMP DIRECTORIES EVERYWHERE
        try {
            val tempLocations = listOf(
                File(System.getProperty("java.io.tmpdir") ?: ""),
                File("/tmp"),
                File(context.cacheDir, "tmp"),
                File(context.filesDir, "tmp"),
                File(context.cacheDir, "temp"),
                File(context.filesDir, "temp")
            )

            tempLocations.forEach { tempDir ->
                if (tempDir.exists() && tempDir.canWrite()) {
                    // Only delete files that might be related to our app
                    tempDir.listFiles()?.forEach { file ->
                        try {
                            if (file.name.contains(context.packageName, ignoreCase = true) ||
                                file.name.startsWith("rn") ||
                                file.name.startsWith("react") ||
                                file.name.contains("cache") ||
                                file.name.contains("temp")) {

                                if (file.isDirectory) {
                                    nukeDirectory(file)
                                } else {
                                    nukeFile(file)
                                }
                                Log.d("App_Cache", "💥 Temp nuked: ${file.name}")
                            }
                        } catch (e: Exception) {
                            // Silent fail for temp files
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("App_Cache", "⚠️ Some temp files couldn't be nuked: ${e.message}")
        }

        // 8. FORCE MAXIMUM GARBAGE COLLECTION
        try {
            repeat(10) {
                System.gc()
                Runtime.getRuntime().gc()
                System.runFinalization()
                Thread.sleep(50)
            }
            Log.d("App_Cache", "💥 MAXIMUM GARBAGE COLLECTION PERFORMED")
        } catch (e: Exception) {
            Log.w("App_Cache", "⚠️ GC issues: ${e.message}")
        }

        // 9. SYSTEM MEMORY FLUSH
        try {
            val process = Runtime.getRuntime().exec("sync")
            process.waitFor()
            Log.d("App_Cache", "💥 SYSTEM MEMORY FLUSHED")
        } catch (e: Exception) {
            // Silent fail
        }

        // 10. ATTEMPT TO CLEAR DALVIK/ART CACHE (may fail without root)
        try {
            val dalvikCache = File("/data/dalvik-cache")
            if (dalvikCache.exists()) {
                dalvikCache.listFiles()?.forEach { file ->
                    if (file.name.contains(context.packageName)) {
                        nukeFile(file)
                        Log.d("App_Cache", "💥 Dalvik cache nuked: ${file.name}")
                    }
                }
            }
        } catch (e: Exception) {
            // Expected to fail without root - silent
        }

        // FINAL NUCLEAR SUMMARY
        Log.d("App_Cache", "☢️ NUCLEAR DESTRUCTION COMPLETE ☢️")
        Log.d("App_Cache", "💥 TOTAL DESTROYED: $totalCleared items")
        Log.d("App_Cache", "❌ FAILURES: $totalErrors items")
        Log.d("App_Cache", "🚨 WARNING: ALL APP DATA HAS BEEN DESTROYED")
        Log.d("App_Cache", "🔄 App restart recommended for clean state")

        if (totalErrors == 0) {
            Log.d("App_Cache", "✅ COMPLETE ANNIHILATION SUCCESSFUL!")
        } else {
            Log.w("App_Cache", "⚠️ Some items survived the nuclear blast ($totalErrors)")
        }

    } catch (e: Exception) {
        Log.e("App_Cache", "🚨 NUCLEAR MELTDOWN ERROR: ${e.message}")
        e.printStackTrace()
    }
}

    // NUCLEAR DELETION FUNCTIONS - NO MERCY
    private fun nukeDirectory(directory: File): Boolean {
        if (!directory.exists()) return true

        return try {
            var success = true

            // First pass - delete all contents
            directory.listFiles()?.forEach { file ->
                val deleted = if (file.isDirectory) {
                    nukeDirectory(file)
                } else {
                    nukeFile(file)
                }
                if (!deleted) success = false
            }

            // Second pass - delete the directory itself with extreme force
            if (success) {
                success = nukeFile(directory)
            }

            success
        } catch (e: Exception) {
            Log.e("App_Cache", "💥 Nuclear directory error: ${e.message}")
            false
        }
    }

    private fun nukeFile(file: File): Boolean {
        if (!file.exists()) return true

        // NUCLEAR FILE DELETION - MULTIPLE ATTEMPTS WITH EXTREME FORCE
        val strategies = listOf(
            // Strategy 1: Direct delete
            { file.delete() },

            // Strategy 2: Set all permissions and delete
            {
                file.setReadable(true)
                file.setWritable(true)
                file.setExecutable(true)
                file.delete()
            },

            // Strategy 3: Clear content and delete
            {
                if (file.isFile) {
                    file.writeText("")
                }
                System.gc()
                Thread.sleep(100)
                file.delete()
            },

            // Strategy 4: Truncate and delete
            {
                if (file.isFile) {
                    RandomAccessFile(file, "rw").use { raf ->
                        raf.setLength(0)
                    }
                }
                System.gc()
                file.delete()
            },

            // Strategy 5: Overwrite with zeros and delete
            {
                if (file.isFile && file.length() < 10 * 1024 * 1024) { // Only for files < 10MB
                    file.writeBytes(ByteArray(file.length().toInt()))
                }
                file.delete()
            },

            // Strategy 6: Final attempt - mark for deletion
            {
                file.deleteOnExit()
                !file.exists() // Return true if file no longer exists
            }
        )

        strategies.forEachIndexed { index, strategy ->
            try {
                if (strategy()) {
                    if (index > 0) {
                        Log.d("App_Cache", "💥 File nuked with strategy ${index + 1}: ${file.name}")
                    }
                    return true
                }
            } catch (e: Exception) {
                // Try next strategy
                if (index == strategies.size - 1) {
                    Log.w("App_Cache", "💀 File survived nuclear blast: ${file.name} - ${e.message}")
                }
            }
        }

        return false
    }

    // ULTIMATE NUCLEAR OPTION - RESTART THE APP AFTER CLEARING
    @ReactMethod
    fun clearAppCacheAndRestart() {
        clearAppCacheNuclear()

        // Force app restart
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)

                // Force kill current process
                android.os.Process.killProcess(android.os.Process.myPid())
            } catch (e: Exception) {
                Log.e("App_Cache", "❌ Restart failed: ${e.message}")
            }
        }, 1000)
    }

    // ✅ To stop Firebase real-time listener
    @ReactMethod
    fun stopServerTimeListener() {
        if (serverTimeRef != null && serverTimeListener != null) {
            serverTimeRef?.removeEventListener(serverTimeListener!!)
            Log.d("ServerTimeMonitor", "🛑 Listener removed from path: $currentServerTimePath")
        } else {
            Log.d("ServerTimeMonitor", "ℹ️ No active listener to remove.")
        }

        serverTimeListener = null
        serverTimeRef = null
        currentServerTimePath = null
    }
}
