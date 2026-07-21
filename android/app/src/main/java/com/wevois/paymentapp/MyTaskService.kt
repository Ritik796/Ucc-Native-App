package com.wevois.paymentapp


import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.location.Location
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.content.edit
import com.facebook.react.HeadlessJsTaskService
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.MutableData
import com.google.firebase.database.Transaction
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt


class MyTaskService : HeadlessJsTaskService() {


    private lateinit var fusedLocationClient: FusedLocationProviderClient

    //    private lateinit var locationRequest: LocationRequest
    private lateinit var locationCallback: LocationCallback
    private lateinit var reqLocAccuracy: String
    private lateinit var reqLocDistance: String
    private lateinit var reqLocInterval: String
    private lateinit var reqLocSendInterval: String
    private lateinit var lockHistoryPath: String
    private lateinit var dbPath: String
    // Traversal trail Firebase path (web app se aata hai). Iske hone par native SEEDHA Firebase likhta
    // hai (lock/unlock jaisa) — lock/PiP/background/no-network sab me reliable, WebView pe depend nahi.
    private lateinit var travelPath: String
    // Firebase offline persistence ek hi baar enable karni hoti hai (network na hone par writes disk
    // par queue ho ke baad me sync ho jaayein — field me network-drop pe data loss se bachta hai).
    private var previousLat: Double? = null
    private var previousLng: Double? = null
    private val maxDistance = 15
    private var maxDistanceCanCover = maxDistance
    private val traversalHistory = StringBuilder()
    private lateinit var saveRunnable: Runnable
    private val handler = Handler(Looper.getMainLooper())
    private var isWaitingForUnlockLocation = false
    // Location updates abhi active hain ya nahi — duplicate start aur call/lock ke baad
    // reliable resume ke liye.
    private var isTracking = false
    // Save-timer (saveRunnable) INDEPENDENT chalta hai — GPS re-arm (watchdog) ise touch nahi karega.
    // Pehle har re-arm saveRunnable ko reset kar deta tha; lock me watchdog har ~15-20s re-arm karta hai,
    // isliye 60s save-timer kabhi fire hi nahi hota tha aur poore lock ke points ek hi node me dump ho
    // jaate the (per-minute breakdown missing). Ab save-timer sirf ek baar start hota hai aur apne
    // 60s cycle par bina ruke chalta rehta hai.
    private var saveRunnableActive = false

    // POWER OPT (adaptive GPS): high-accuracy GPS 24/7 sabse bada drain hai. Jab worker STATIONARY ho
    // (hil nahi raha) to GPS ko BALANCED priority + lamba interval pe daal do — drain bahut kam. Move
    // karte hi wapas high-accuracy. Tracking impact ~zero (stationary me trail me kuch banega hi nahi).
    private var lowPowerGps = false                    // abhi low-power (stationary) GPS mode me?
    private var stationaryRefLat: Double? = null       // stationary-detection ka reference point
    private var stationaryRefLng: Double? = null
    private var lastSignificantMoveTime = 0L           // aakhri meaningful move ka time
    private val stationaryMoveThresholdM = 20.0        // reference se itna hile to "moving"
    private val stationaryTimeoutMs = 90_000L          // itni der na hile to low-power mode
    private val lowPowerIntervalMs = 20_000L           // stationary me GPS interval (20s)
    // Partial wake lock: screen off / Doze me CPU suspend ho jaata tha (WAKE_LOCK permission thi
    // par kabhi acquire nahi hoti thi) — isi wajah se lock ke baad GPS fixes throttle/ruk jaate the.
    // Ye held wake lock CPU jaga rakhta hai taaki screen off pe bhi location aati rahe.
    private var wakeLock: PowerManager.WakeLock? = null
    // Aakhri location fix kab aaya — watchdog ke liye (OEM throttle ke baad auto re-arm).
    private var lastFixTime = 0L
    // Vivo continuous GPS update-stream ko throttle karta hai (~1 fix/55s), isliye watchdog ka fresh
    // re-arm hi reliably fix kheenchta hai. Watchdog aggressive rakha hai taaki gap ~55s se ghat ke
    // ~15-18s ho jaaye (screen-off pe bhi location dense aati rahe). Moving device pe jab natural
    // updates aa rahe hon to gap kabhi timeout tak pahunchta hi nahi — tab watchdog chup rehta hai.
    private val watchdogIntervalMs = 10_000L
    private val fixTimeoutMs = 15_000L

    // Reason 1 (gap fix): accuracy gate har poor-accuracy fix ko drop kar deta tha — kharab accuracy
    // ke poore window me koi point record nahi hota tha aur "beech beech me" gap aa jaata tha
    // (watchdog ko bhi pata nahi chalta tha kyunki raw fixes to aa rahe hote the). Ab agar itni der
    // se koi accurate point record NAHI hua, to ek hadd tak degraded fix accept kar lo taaki trail
    // kabhi freeze na ho. Normal haalat me strict threshold hi chalta hai.
    private var lastRecordedTime = 0L
    // Escalating accuracy fallback: jitni der se koi point record NAHI hua, utna accuracy ka bar
    // dheela karo — taaki kharab-GPS ke lambe period me bhi trail kabhi PERMANENTLY freeze na ho.
    // Normal haalat me strict threshold hi chalta hai. (Pehle single 20s/50m cap tha jo >50m accuracy
    // par hamesha drop kar deta tha aur location ek jagah stuck ho jaati thi.)
    private val staleFallbackMs = 20_000L    // is ke baad pehla degraded tier (<=50m) shuru
    // Watchdog ke liye: itni der koi POINT record na ho (raw fix aane ke bawajood) to accuracy-freeze
    // maan ke alert karo. (Pehle watchdog sirf lastFixTime dekhta tha, isliye accuracy-freeze uski
    // nazar se bach jaata tha — location stuck rehti thi par watchdog ko pata hi nahi chalta tha.)
    private val recordTimeoutMs = 60_000L

    // Watchdog: agar tracking active hai par kaafi der se koi fix nahi aaya (Vivo/Oppo ne background
    // me throttle kar diya), to request ko re-arm karo. App jaise hi wapas visible/foreground hoga,
    // agla re-arm turant fixes resume kar dega — bina kisi manual setting ya lifecycle event ke.
    private val locationWatchdog = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            when {
                // Case A: kaafi der se koi RAW fix hi nahi aaya (OEM throttle / GPS off) — request re-arm.
                // Re-arm lastRecordedTime reset kar deta hai, jo yahan sahi hai (GPS ko fresh mauka).
                isTracking && lastFixTime > 0L && now - lastFixTime > fixTimeoutMs -> {
                    Log.w(
                        "LocTrack",
                        "🐕 WATCHDOG: ${(now - lastFixTime) / 1000}s se koi GPS fix nahi aaya (OEM throttle/GPS issue) — tracking RE-ARM kar rahe hain"
                    )
                    startTraversalTracking()
                }
                // Case B: raw fixes to aa rahe hain (lastFixTime fresh) par kaafi der se koi POINT record
                // nahi hua — yani accuracy freeze (sab fixes accuracy-gate se drop ho rahe). Yahan re-arm
                // NAHI karna, kyunki startTraversalTracking() lastRecordedTime reset kar dega aur
                // escalating fallback (jo staleness par depend karta hai) phir se 0 se shuru ho ke atak
                // jaayega. Escalating fallback khud accept kar lega — yahan sirf visibility ke liye log.
                isTracking && lastRecordedTime > 0L && now - lastRecordedTime > recordTimeoutMs -> {
                    Log.w(
                        "LocTrack",
                        "🐕 WATCHDOG: raw fixes aa rahe hain par ${(now - lastRecordedTime) / 1000}s se koi POINT record nahi (accuracy freeze) — escalating fallback accept karega, location stuck nahi rahegi"
                    )
                }
            }
            handler.postDelayed(this, watchdogIntervalMs)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i("LocTrack", "▶️ SERVICE onCreate — location tracking system boot")
        registerScreenLockReceiver()
        startForegroundServiceWithNotification()
        // POWER OPT: wakelock sirf SCREEN-OFF pe rakhte hain (screen-on pe CPU waise bhi jaga rehta hai —
        // continuously held wakelock bada drain deta tha + Vivo abe "heavy battery" flag karta tha).
        // onCreate ke waqt agar screen already OFF ho (recovery restart) to hi acquire; warna SCREEN_OFF
        // broadcast pe acquire hoga aur SCREEN_ON pe release.
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) acquireWakeLock()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        // Agar OEM ne pichli baar background me service kill ki thi, to disk par bacha hua background
        // buffer wapas load karo taaki wo data khone ke bajaye agle flush par save ho jaaye.
        TravelHistoryManager.restoreFromDisk(applicationContext)
        handler.postDelayed(locationWatchdog, watchdogIntervalMs)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // PiP window close hone par MainActivity yeh action bhejti hai. Sirf "AppClosed" log karke
        // return — tracking params yahan touch nahi karne (warna previous config "" se overwrite ho jaaye).
        if (intent?.action == MainActivity.ACTION_APP_CLOSED) {
            Log.w("LocTrack", "⛔ onStartCommand: APP CLOSED (PiP window band ho gayi)")
            handleAppClosedFromPip()
            return START_STICKY
        }
        // App wapas foreground aaya. Vivo/Oppo jaise OEM background me location throttle kar dete hain;
        // foreground aate hi request ko re-arm (refresh) karo taaki fixes turant resume ho jaayein.
        // (Existing request ko refresh karne ke liye startTraversalTracking idempotent hai.)
        if (intent?.action == ACTION_REARM_LOCATION) {
            if (::reqLocInterval.isInitialized) {
                Log.i("LocTrack", "🔄 onStartCommand: RE-ARM (app foreground aaya) — tracking refresh")
                Log.d("LocationUpdate", "Re-arming location updates (app foreground)")
                startTraversalTracking()
            } else {
                Log.w("LocTrack", "🔄 RE-ARM aaya par config abhi set nahi — skip")
            }
            return START_STICKY
        }
        // System agar service ko kill karke START_STICKY se restart kare, to intent null aata hai.
        // Us case me params default/khaali ho jaate the (config + dbPath kho jaata tha). Isliye
        // intent na hone par persist ki gayi last-good values se fallback karo — taaki tracking
        // sahi config ke saath continue rahe, na ki chup-chaap toot jaaye.
        val fromSystemRestart = intent == null
        val sp = getSharedPreferences("service_prefs", Context.MODE_PRIVATE)
        reqLocAccuracy = intent?.getStringExtra("LOCATION_ACCURACY")
            ?: sp.getString("LOCATION_ACCURACY", "15") ?: "15"
        reqLocDistance = intent?.getStringExtra("LOCATION_UPDATE_DISTANCE")
            ?: sp.getString("LOCATION_UPDATE_DISTANCE", "1") ?: "1"
        reqLocInterval = intent?.getStringExtra("LOCATION_UPDATE_INTERVAL")
            ?: sp.getString("LOCATION_UPDATE_INTERVAL", "5000") ?: "5000"
        reqLocSendInterval = intent?.getStringExtra("LOCATION_SEND_INTERVAL")
            ?: sp.getString("LOCATION_SEND_INTERVAL", "1") ?: "1"
        lockHistoryPath = intent?.getStringExtra("LOCK_HISTORY_PATH")
            ?: sp.getString("LOCK_HISTORY_PATH", "") ?: ""
        dbPath = intent?.getStringExtra("DB_PATH")
            ?: sp.getString("DB_PATH", "") ?: ""
        travelPath = intent?.getStringExtra("TRAVEL_PATH")
            ?: sp.getString("TRAVEL_PATH", "") ?: ""
        // Sab params persist kar do taaki kisi bhi restart par config + paths dono mile.
        sp.edit {
            putString("LOCATION_ACCURACY", reqLocAccuracy)
            putString("LOCATION_UPDATE_DISTANCE", reqLocDistance)
            putString("LOCATION_UPDATE_INTERVAL", reqLocInterval)
            putString("LOCATION_SEND_INTERVAL", reqLocSendInterval)
            putString("DB_PATH", dbPath)
            putString("LOCK_HISTORY_PATH", lockHistoryPath)
            putString("TRAVEL_PATH", travelPath)
        }
        // dbPath milte hi (kisi bhi Firebase use se pehle) offline persistence enable karo — taaki network
        // na hone par bhi trail/lock writes disk par queue ho ke baad me sync ho jaayein.
        ensureFirebasePersistence(dbPath)
        if (fromSystemRestart) {
            Log.w("LocTrack", "⚠️ onStartCommand: intent NULL — system ne service KILL karke restart kiya (START_STICKY). Config SharedPreferences se restore, tracking continue")
        } else {
            Log.i("LocTrack", "▶️ onStartCommand: START tracking — acc=${reqLocAccuracy}m interval=${reqLocInterval}ms dist=${reqLocDistance}m dbPath=${if (dbPath.isEmpty()) "EMPTY!" else "ok"}")
        }
        startTraversalTracking()
        setServiceRunning(true)
        return START_STICKY
    }

    /** PiP se app close hone par last known location ke saath "AppClosed" Firebase par log karta hai. */
    private fun handleAppClosedFromPip() {
        val sp = getSharedPreferences("service_prefs", Context.MODE_PRIVATE)
        val db = if (::dbPath.isInitialized && dbPath.isNotEmpty()) dbPath
        else sp.getString("DB_PATH", "") ?: ""
        val lockPath = if (::lockHistoryPath.isInitialized && lockHistoryPath.isNotEmpty()) lockHistoryPath
        else sp.getString("LOCK_HISTORY_PATH", "") ?: ""

        if (db.isEmpty() || lockPath.isEmpty()) {
            Log.w("LocationUpdate", "AppClosed: db/lock path missing, skipping log")
            return
        }

        val hasFix = previousLat != null && previousLng != null
        val closeObj = JSONObject().apply {
            put("status", "AppClosed")
            put("lat_lng", if (hasFix) "${previousLat},${previousLng}" else "")
        }
        saveLockUnLockHistory(closeObj, db, lockPath)
        Log.d("LocationUpdate", "AppClosed logged from PiP")
    }

    // Location Tracking Logic start
    @SuppressLint("MissingPermission")
    private fun startTraversalTracking() {
        // Guard: agar tracking params (config) abhi set nahi hue — service sirf re-arm /
        // USER_PRESENT se start hua, real StartBackGroundService nahi aaya — to tracking start
        // mat karo. Warna lateinit reqLocInterval access crash karta tha (unlock par service mar
        // jaati thi aur tracking band ho jaati thi).
        if (!::reqLocInterval.isInitialized) {
            Log.w("LocTrack", "⚠️ START skipped: config params abhi set nahi (service sirf re-arm/unlock se aaya, real StartBackGroundService nahi) — tracking nahi shuru hui")
            Log.w("LocationUpdate", "startTraversalTracking skipped: tracking params not set yet")
            return
        }
        // Idempotent: pehle se chal raha ho to purane updates/runnable hata do, warna
        // call/unlock par double location callbacks aur duplicate save-runnable ban jaate hain.
        if (isTracking) {
            Log.d("LocTrack", "🔁 tracking pehle se chal rahi thi — purani GPS updates hata ke fresh re-arm (save-timer chalta rahega)")
            if (::locationCallback.isInitialized) {
                fusedLocationClient.removeLocationUpdates(locationCallback)
            }
            // NOTE: saveRunnable ko yahan REMOVE nahi karna — warna re-arm 60s save-timer reset kar deta.
            isTracking = false
        }
        val intervalMillis = reqLocInterval.toLongOrNull() ?: 5000L
        val distanceMeters = reqLocDistance.toFloatOrNull() ?: 1f
        // POWER OPT: low-power (stationary) mode me BALANCED priority + lamba interval — GPS chip kam
        // chalega, drain bahut kam. Moving mode me HIGH_ACCURACY + normal interval (accurate trail).
        val effectiveInterval = if (lowPowerGps) lowPowerIntervalMs else intervalMillis
        val priority = if (lowPowerGps) Priority.PRIORITY_BALANCED_POWER_ACCURACY
        else Priority.PRIORITY_HIGH_ACCURACY
        val locationRequest =
            LocationRequest.Builder(priority, effectiveInterval).apply {
                setMinUpdateIntervalMillis(effectiveInterval / 2)
                setMinUpdateDistanceMeters(distanceMeters)
            }.build()


        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (location in result.locations) {
                    handleLocationUpdate(location)
                }
            }

        }


        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
        isTracking = true
        Log.i("LocTrack", "✅ tracking ARMED — GPS updates chalu (mode=${if (lowPowerGps) "LOW-POWER/stationary" else "HIGH-ACC/moving"}, interval=${effectiveInterval}ms, minDist=${distanceMeters}m)")
        // Re-arm/start par stale-clock reset karo: GPS ko pehle accurate fix dene ka mauka milta hai;
        // tabhi (staleFallbackMs ke baad) degraded fallback consider hota hai. Pehla fix bhi agar
        // lambe samay tak accurate na mile to is wajah se seed ho paata hai (gap-at-start avoid).
        lastRecordedTime = System.currentTimeMillis()
        // Save-timer sirf EK BAAR start hota hai — re-arm par dobara nahi (warna 60s reset ho jaata).
        if (!saveRunnableActive) {
            saveRunnable = object : Runnable {
                override fun run() {
                    if (traversalHistory.isNotEmpty()) {
                        while (traversalHistory.endsWith("~")) {
                            traversalHistory.setLength(traversalHistory.length - 1)
                        }
                        saveDataToDatabase(traversalHistory.toString())
                        traversalHistory.clear()
                        maxDistanceCanCover = maxDistance
                    }
                    handler.postDelayed(this, getDelayToNextMinute())
                }
            }
            handler.postDelayed(saveRunnable, getDelayToNextMinute())
            saveRunnableActive = true
            Log.d("LocTrack", "⏱️ save-timer START (independent 60s cycle — re-arm se affect nahi hoga)")
        }
    }


    /**
     * POWER OPT: Adaptive GPS mode switch. Reference point se NET movement dekhta hai:
     *  - Reference se > 20m hile → MOVING → high-accuracy mode (re-arm). Reference + move-time update.
     *  - 90s+ se < 20m (nahi hile) → STATIONARY → low-power mode (re-arm: BALANCED + 20s interval).
     * Re-arm handler.post se (location callback ke andar removeLocationUpdates safe rehta hai).
     */
    private fun checkMovementMode(lat: Double, lng: Double) {
        val now = System.currentTimeMillis()
        if (stationaryRefLat == null || stationaryRefLng == null) {
            stationaryRefLat = lat
            stationaryRefLng = lng
            lastSignificantMoveTime = now
            return
        }
        val moved = getDistance(stationaryRefLat!!, stationaryRefLng!!, lat, lng)
        if (moved > stationaryMoveThresholdM) {
            // Meaningful move — reference aage, move-time reset.
            stationaryRefLat = lat
            stationaryRefLng = lng
            lastSignificantMoveTime = now
            if (lowPowerGps) {
                lowPowerGps = false
                Log.i("LocTrack", "🏃 MOVING detected (${moved.toInt()}m) — GPS wapas HIGH-ACCURACY")
                handler.post { startTraversalTracking() }
            }
        } else if (!lowPowerGps && lastSignificantMoveTime > 0L &&
            now - lastSignificantMoveTime > stationaryTimeoutMs) {
            // Kaafi der se nahi hile → low-power.
            lowPowerGps = true
            Log.i("LocTrack", "🧍 STATIONARY ${(now - lastSignificantMoveTime) / 1000}s — GPS LOW-POWER (drain kam, tracking impact ~zero)")
            handler.post { startTraversalTracking() }
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleLocationUpdate(location: Location) {
        lastFixTime = System.currentTimeMillis()
        val lat = location.latitude
        val lng = location.longitude
        val acc = location.accuracy

        // POWER OPT: har fix par movement check — stationary ho to GPS low-power, moving ho to high-acc.
        checkMovementMode(lat, lng)
        val provider = location.provider
        val speed = location.speed // meters/second


        Log.d(
            "LocationUpdate",
            "Lat: $lat, Lng: $lng, Accuracy: $acc, Speed: $speed, Provider: $provider"
        )

        // Validate coordinates
        if (lat.isNaN() || lng.isNaN()) {
            Log.e("LocTrack", "⛔ DROP: invalid coordinates (NaN) — ye point skip")
            return
        }

        // Accuracy check — escalating fallback (strict drop ke bajaye time-based tiers). Jitni der se
        // koi point record nahi hua, utna accuracy ka bar dheela — isliye trail kabhi permanently
        // stuck nahi rahegi (rough point > frozen dot).
        val requiredLocationAccuracy = reqLocAccuracy.toFloatOrNull() ?: 15f
        if (acc > requiredLocationAccuracy) {
            val staleFor = if (lastRecordedTime > 0L) System.currentTimeMillis() - lastRecordedTime else 0L
            val allowedAccuracy = allowedAccuracyFor(staleFor, requiredLocationAccuracy)
            // Degraded fix tabhi accept jab ek baar tracking start/point record ho chuka ho aur current
            // staleness-tier ke hisaab se accuracy allowed ho.
            val canFallback = lastRecordedTime > 0L && acc <= allowedAccuracy
            if (!canFallback) {
                Log.w(
                    "LocTrack",
                    "⚠️ DROP: accuracy kharab ($acc m > allowed ${allowedAccuracy}m, ${staleFor / 1000}s se stale) — point skip, GAP ban sakta hai"
                )
                return
            }
            Log.i(
                "LocTrack",
                "↪️ degraded fix ACCEPT: accuracy $acc m (allowed ${allowedAccuracy}m, ${staleFor / 1000}s se koi point nahi) — freeze rokne ke liye rakh liya"
            )
        }



        // Save unlock history with the first fresh GPS fix after an unlock.
        // Must run BEFORE the first-location check below, because previousLat
        // is reset to null on unlock and would otherwise short-circuit here.
        if (isWaitingForUnlockLocation) {
            if (lat == 0.0 || lng == 0.0) return
            val unlockObj = JSONObject().apply {
                put("status", "Unlock")
                put("lat_lng", "$lat,$lng")
            }
            saveLockUnLockHistory(unlockObj, dbPath, lockHistoryPath)
            isWaitingForUnlockLocation = false
        }

        // Handle first location (also re-seeds after an unlock/gap so the new
        // position becomes a fresh start point instead of being discarded).
        if (previousLat == null || previousLng == null) {
            updateLocation(lat, lng)
            traversalHistory.append("($lat,$lng)")
            sendAvatarLocationToWebView(lat, lng, acc.toDouble())
            saveDataToDatabase(traversalHistory.toString())

            return
        }
        val distance = getDistance(previousLat!!, previousLng!!, lat, lng) // in meters

        if (distance > 0 && distance < maxDistanceCanCover) {
            if (traversalHistory.isNotEmpty() && traversalHistory.last() != '~') {
                traversalHistory.append("~")
            }
            traversalHistory.append("($lat,$lng)")

            updateLocation(lat, lng)
            sendAvatarLocationToWebView(lat, lng,acc.toDouble())

        } else if (distance != 0.0 && distance <= 100) {
            if (traversalHistory.isNotEmpty() && traversalHistory.last() != '~') {
                traversalHistory.append("~")
            }
            traversalHistory.append("($lat,$lng)")
            updateLocation(lat, lng)
            sendAvatarLocationToWebView(lat, lng,acc.toDouble())
            maxDistanceCanCover = maxDistance * 2

        } else if (distance > 100) {
            // Sustained real movement (passed the accuracy gate but >100m from
            // the last point — e.g. moved while locked/backgrounded). Record it
            // and advance the reference point so location history never freezes.
            if (traversalHistory.isNotEmpty() && traversalHistory.last() != '~') {
                traversalHistory.append("~")
            }
            traversalHistory.append("($lat,$lng)")
            updateLocation(lat, lng)
            sendAvatarLocationToWebView(lat, lng, acc.toDouble())
            maxDistanceCanCover = maxDistance
        }

    }


    private fun updateLocation(lat: Double, lng: Double) {
        previousLat = lat
        previousLng = lng
        // Jab bhi koi point actually record hua, stale-clock reset — taaki degraded fallback sirf
        // tabhi trigger ho jab sach me lambe samay se kuch record na hua ho.
        lastRecordedTime = System.currentTimeMillis()
    }

    /**
     * Escalating accuracy limit — jitni der se koi point record nahi hua utni chhoot.
     * Fresh: strict required accuracy (accurate data). Phir 20s / 60s / 120s par bar dheela hota
     * jaata hai taaki kharab-GPS ke lambe period me bhi trail freeze na ho. Top tier (~500m) lagbhag
     * har real GPS fix cover karta hai — yani location kabhi permanently stuck nahi rahegi.
     */
    private fun allowedAccuracyFor(staleForMs: Long, requiredAccuracy: Float): Float {
        return when {
            staleForMs > 120_000L -> 500f            // 2 min+ koi point nahi -> lagbhag koi bhi real fix lo (freeze todo)
            staleForMs > 60_000L -> 100f             // 1 min+ -> <=100m
            staleForMs > staleFallbackMs -> 50f      // 20s+ -> <=50m
            else -> requiredAccuracy                 // normal -> strict
        }
    }

    private fun getDelayToNextMinute(): Long {
        val now = Calendar.getInstance()
        val intervalMillis = reqLocSendInterval.toLongOrNull() ?: 60000L

        Log.d(
            "LocationUpdate",
            "Interval set to: ${intervalMillis}ms (${intervalMillis / 1000} seconds)"
        )

        // Simply add the interval to current time
        val nextUpdateTime = now.timeInMillis + intervalMillis

        val nextTime = Calendar.getInstance().apply {
            timeInMillis = nextUpdateTime
        }
        Log.d("LocationUpdate", "Next update scheduled in: ${intervalMillis}ms")
        Log.d(
            "LocationUpdate",
            "Next update time: ${
                SimpleDateFormat(
                    "HH:mm:ss",
                    Locale.getDefault()
                ).format(nextTime.time)
            }"
        )
        return intervalMillis
    }

    private fun sendAvatarLocationToWebView(lat: Double, lng: Double, acc: Double) {
        val intent = Intent("AVATAR_LOCATION_UPDATE").apply {
            putExtra("latitude", lat)
            putExtra("longitude", lng)
            putExtra("Accuracy", acc)
        }
        Log.d("LocationUpdate", "AVATAR_LOCATION_UPDATE $lat,$lng")
        sendBroadcast(intent)
    }

    private fun saveDataToDatabase(history: String) {
        val currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

        // 1. NATIVE-DIRECT Firebase write — foreground/background/lock/PiP SAB me chalega (pehle sirf
        //    foreground me web app likhta tha; background/PiP me buffer hota tha -> gaps/jumps). Ab
        //    lock/unlock ki tarah native seedha likhta hai, WebView pe koi depend nahi.
        saveTraversalToFirebase(history, currentTime)
   
        // 2. Broadcast — sirf LIVE map DISPLAY ke liye (ab web app likhta nahi, sirf dikhata hai).
        val jsonObj = JSONObject().apply {
            put("time", currentTime)
            put("history", history)
            put("type", "history")
        }
        val intent = Intent("travel_history").apply {
            putExtra("travel_history", jsonObj.toString())
        }
        sendBroadcast(intent)
    }


    /**
     * Traversal trail ko SEEDHA Firebase par likhta hai (web app ke bajaye), bilkul web app ke same
     * structure me taaki dashboard pe koi farak na pade:
     *   travelPath/HH:mm            = { distance-in-meter, lat-lng }
     *   travelPath/TotalCoveredDistance  += is minute ka distance (transaction se — atomic, koi race nahi)
     *   travelPath/last-update-time = HH:mm
     */
    private fun saveTraversalToFirebase(history: String, time: String) {
        val path = if (::travelPath.isInitialized && travelPath.isNotEmpty()) travelPath
        else getSharedPreferences("service_prefs", Context.MODE_PRIVATE).getString("TRAVEL_PATH", "") ?: ""
        if (path.isEmpty() || !::dbPath.isInitialized || dbPath.isEmpty()) {
            Log.w("LocTrack", "💾 SAVE skip: travelPath/dbPath khaali (config abhi nahi aaya) — trail is minute save nahi hua")
            return
        }
        try {
            ensureFirebasePersistence(dbPath)
            val database = FirebaseDatabase.getInstance(dbPath.trimEnd('/'))
            val minuteDistance = calculatePathDistance(history)

            // Per-minute node (siblings ko touch kiye bina — child par setValue safe hai)
            database.getReference("$path/$time").setValue(
                mapOf("distance-in-meter" to minuteDistance, "lat-lng" to history)
            )
            // last-update-time
            database.getReference("$path/last-update-time").setValue(time)
            // TotalCoveredDistance — transaction se atomic add (concurrent minute writes me race nahi)
            database.getReference("$path/TotalCoveredDistance")
                .runTransaction(object : Transaction.Handler {
                    override fun doTransaction(currentData: MutableData): Transaction.Result {
                        val current = (currentData.getValue(Double::class.java)) ?: 0.0
                        currentData.value = current + minuteDistance
                        return Transaction.success(currentData)
                    }
                    override fun onComplete(error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?) {
                        if (error != null) Log.e("LocTrack", "💾 TotalCoveredDistance transaction FAIL: ${error.message}")
                    }
                })
            val bg = isAppInBackground(applicationContext)
            Log.i("LocTrack", "💾 SAVE(native-direct): trail Firebase par likha @ $time (${"%.1f".format(minuteDistance)}m, app=${if (bg) "background/PiP" else "foreground"}) — offline ho to queue")
        } catch (e: Exception) {
            Log.e("LocTrack", "💾 SAVE(native-direct) FAIL: ${e.message} — dbPath/travelPath sahi hai?")
        }
    }

    /**
     * "(lat,lng)~(lat,lng)~..." string ke consecutive points ke beech haversine distance ka sum.
     * Web app ke calculatePathDistance ki exact nakal — taaki distance-in-meter same rahe.
     */
    private fun calculatePathDistance(historyString: String): Double {
        val points = historyString.split("~").mapNotNull { coord ->
            val cleaned = coord.replace("(", "").replace(")", "").trim()
            val parts = cleaned.split(",")
            if (parts.size != 2) return@mapNotNull null
            val lat = parts[0].toDoubleOrNull() ?: return@mapNotNull null
            val lng = parts[1].toDoubleOrNull() ?: return@mapNotNull null
            Pair(lat, lng)
        }
        var distance = 0.0
        for (i in 0 until points.size - 1) {
            distance += getDistance(points[i].first, points[i].second, points[i + 1].first, points[i + 1].second)
        }
        return distance
    }

    private fun isAppInBackground(context: Context): Boolean {
        val sp = context.getSharedPreferences("service_pref", Context.MODE_PRIVATE)
        return sp.getBoolean("isAppInBackground", false)
    }

    private fun getDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a =
            sin(dLat / 2).pow(2.0) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(
                dLon / 2
            ).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadius * c
    }

    // Location Tracking Logic End

    private val setServiceRunning: (Boolean) -> Unit = { isRunning ->
        getSharedPreferences("service_prefs", Context.MODE_PRIVATE).edit {
            putBoolean("isServiceRunning", isRunning)
        }
    }

    @SuppressLint("NewApi")
    private fun startForegroundServiceWithNotification() {
        // Naya channel ID — channel ki importance ek baar bann jaane ke baad code se badalti nahi
        // (Android cache karta hai). Pehle LOW importance thi jise Vivo lock screen par NAHI dikhata
        // tha; lock-screen visibility ke liye DEFAULT importance chahiye — isliye naya channel ID.
        val channelId = "LocationTrackingChannelV2"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            // Purane orphan channels hata do (settings me extra entries na dikhein).
            manager?.deleteNotificationChannel("MyTaskServiceChannel")
            manager?.deleteNotificationChannel("LocationTrackingChannel")

            val channel = NotificationChannel(
                channelId,
                "Location Tracking",
                // DEFAULT importance taaki LOCK SCREEN par bhi dikhe (LOW ko Vivo/kai OEM lock screen
                // par chhupa dete hain). Heads-up popup nahi hoga (wo sirf HIGH par hota hai).
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Location tracking chalu hone par persistent notification"
                setShowBadge(false)
                // Silent rakho — koi sound/vibration nahi (Uber jaisa), par importance DEFAULT rahe
                // taaki lock screen par dikhe.
                setSound(null, null)
                enableVibration(false)
                // Lock screen par poora content dikhe — "Contents hidden" na ho.
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            manager?.createNotificationChannel(channel)
        }

        // Tap karne par app khule (Uber jaisa).
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val contentPending = PendingIntent.getActivity(this, 0, tapIntent, pendingFlags)

        val notification = Notification.Builder(this, channelId)
            .setContentTitle("WeVOIS — Location ON")
            .setContentText("Aapki location record ho rahi hai (service chalu hai)")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)                                   // non-dismissible (swipe se hat nahi sakti)
            .setContentIntent(contentPending)                   // tap -> app khule
            .setVisibility(Notification.VISIBILITY_PUBLIC)      // lock screen par bhi poora dikhe
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

        // IMPORTANT: Android 10+ par foreground service ko background location dene ke liye
        // runtime par bhi LOCATION type pass karna zaroori hai. Sirf manifest me declare karne se
        // kuch OEM/versions background location exempt nahi karte — isi wajah se PiP + doosri app
        // (e.g. YouTube) ke peeche location aana band ho jaata tha.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(1, notification)
        }
    }

    /**
     * Partial wake lock acquire karta hai taaki screen off / Doze mode me bhi CPU jagta rahe aur
     * FusedLocation ke GPS callbacks aate rahein. Bina iske, screen off hone ke kuch der baad device
     * deep sleep me chala jaata tha aur location updates ruk/throttle ho jaate the.
     */
    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld == true) return
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "WeVOIS:LocationTrackingWakeLock"
            ).apply {
                setReferenceCounted(false)
                // No timeout: tracking session-long chahiye. Service 23:00 auto-stop, server
                // kill-switch aur stopBackgroundTask par band hoti hai — tabhi wake lock release hota hai.
                acquire()
            }
            Log.i("LocTrack", "🔋 WakeLock ACQUIRED — screen off/Doze me bhi CPU jagega, location aati rahegi")
        } catch (e: Exception) {
            Log.e("LocTrack", "🔋 WakeLock acquire FAIL: ${e.message}")
        }
    }

    /** Wake lock safely release karta hai (service band hone par). */
    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.i("LocTrack", "🔋 WakeLock RELEASED — CPU ab normal power state me")
            }
        } catch (e: Exception) {
            Log.e("LocTrack", "🔋 WakeLock release error: ${e.message}")
        } finally {
            wakeLock = null
        }
    }

    companion object {
        const val ACTION_REARM_LOCATION = "com.wevois.paymentapp.ACTION_REARM_LOCATION"

        // Firebase offline persistence process me ek hi baar enable hoti hai, aur us instance ke kisi
        // bhi use (getReference/read/write) se PEHLE. Isliye ye shared helper — jo bhi Firebase pehle
        // touch kare (BackgroundTaskModule ka server-time listener YA MyTaskService ka write) wahi
        // pehle ise call kare. URL normalize (trailing slash hata ke) taaki dono ek hi instance use karein.
        @Volatile
        private var persistenceEnabled = false

        fun ensureFirebasePersistence(dbUrl: String?) {
            if (persistenceEnabled) return
            val url = dbUrl?.trimEnd('/') ?: ""
            if (url.isEmpty()) return
            try {
                FirebaseDatabase.getInstance(url).setPersistenceEnabled(true)
                Log.i("LocTrack", "🔌 Firebase offline persistence ON — network na ho to writes disk par queue + baad me sync")
            } catch (e: Exception) {
                // Instance pehle se use ho chuka — persistence set nahi hui (par crash nahi).
                Log.d("LocTrack", "Firebase persistence set skip: ${e.message}")
            }
            persistenceEnabled = true
        }
    }

    // Travel History Manager service
    object TravelHistoryManager {
        private val backgroundTravelHistoryList = mutableListOf<JSONObject>()
        var isSavingWithBoth: Boolean = false

        // Reason 2 (gap fix): background buffer pehle sirf RAM me tha. OEM (Vivo/Oppo) background me
        // process kill kar dete the to poora buffer udd jaata tha aur us duration ka location gap
        // aa jaata tha. Ab buffer disk par bhi mirror hota hai — kill/START_STICKY restart ke baad
        // restoreFromDisk() se wapas aa jaata hai aur agle foreground/flush par normal pipeline se
        // (back_history ke roop me) save ho jaata hai. Web schema bilkul same rehta hai.
        private const val BG_PREF = "service_prefs"
        private const val BG_KEY = "persisted_bg_history"

        private fun persist(context: Context) {
            val array = JSONArray().apply { backgroundTravelHistoryList.forEach { put(it) } }
            context.getSharedPreferences(BG_PREF, Context.MODE_PRIVATE).edit {
                putString(BG_KEY, array.toString())
            }
        }

        /** Process restart ke baad disk se buffer wapas RAM me load karo — sirf jab RAM khaali ho,
         *  taaki points duplicate na ho. */
        fun restoreFromDisk(context: Context) {
            if (backgroundTravelHistoryList.isNotEmpty()) return
            val raw = context.getSharedPreferences(BG_PREF, Context.MODE_PRIVATE)
                .getString(BG_KEY, null) ?: return
            try {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    backgroundTravelHistoryList.add(arr.getJSONObject(i))
                }
                if (arr.length() > 0) {
                    Log.d("LocationUpdate", "Restored ${arr.length()} background entries from disk")
                }
            } catch (e: Exception) {
                Log.e("LocationUpdate", "Failed to restore background history", e)
            }
        }

        fun addToBackgroundHistory(context: Context, json: JSONObject) {
            Log.d("LocationUpdate", "Added to background history: $json")
            backgroundTravelHistoryList.add(json)
            persist(context)
        }

        fun getBackgroundHistoryArrayAndClear(context: Context): JSONArray? {
            return if (backgroundTravelHistoryList.isNotEmpty()) {
                val array = JSONArray().apply {
                    backgroundTravelHistoryList.forEach { put(it) }
                }
                Log.d(
                    "TravelHistory",
                    "Returning background history, count: ${backgroundTravelHistoryList.size}"
                )
                backgroundTravelHistoryList.clear()
                persist(context)
                array
            } else {
                null
            }
        }

        fun flushBackgroundHistoryIfNeeded(context: Context) {
            if (isSavingWithBoth) {
                Log.d("TravelHistory", "Skipping background flush - saving with both")
                return
            }

            if (backgroundTravelHistoryList.isNotEmpty()) {
                val array = JSONArray().apply {
                    backgroundTravelHistoryList.forEach { put(it) }
                }

                val jsonObject = JSONObject().apply {
                    put("back_history", array)
                    put("type", "back_history")
                }

                val intent = Intent("travel_history").apply {
                    putExtra("travel_history", jsonObject.toString())
                }

                context.sendBroadcast(intent)
                Log.d("LocationUpdate", "Flushed background history, count: ${array.length()}")
                backgroundTravelHistoryList.clear()
                persist(context)
            }
        }


    }

    // ====== SCREEN LOCK/UNLOCK RECEIVER ======
    private var screenLockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    // POWER OPT: screen-off hote hi wakelock acquire — ab CPU ko jaga rakhna zaroori
                    // hai taaki Doze/screen-off me bhi GPS callbacks aate rahein. (Screen-on pe release
                    // hota hai — tab CPU waise bhi jaga hota hai.)
                    acquireWakeLock()
                    // Screen off ke 2 case: (a) genuine lock → tracking band karo,
                    // (b) incoming call ka proximity sensor screen off (device locked NAHI) →
                    //     tracking chaalu rakho, warna call ke baad location gap aa jaata hai.
                    val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                    if (km.isKeyguardLocked) {
                        Log.i("LocTrack", "🔒 SCREEN OFF + DEVICE LOCKED — tracking CONTINUE (wake lock + FGS se screen-off pe bhi location aayegi)")
                        onDeviceLocked()
                    } else {
                        Log.i(
                            "LocTrack",
                            "📴 SCREEN OFF par device LOCK nahi (call/proximity) — tracking CONTINUE"
                        )
                    }
                }

                Intent.ACTION_SCREEN_ON -> {
                    // POWER OPT: screen-on hote hi wakelock RELEASE — ab CPU screen ki wajah se jaga
                    // rehta hai, alag wakelock ki zaroorat nahi (drain + abe-flag kam).
                    releaseWakeLock()
                    // Call khatam / screen wapas on. USER_PRESENT sirf keyguard-unlock par aata hai,
                    // call-proximity ke baad nahi — isliye yahan resume karna zaroori hai.
                    onScreenOn()
                }

                Intent.ACTION_USER_PRESENT -> {
                    Log.i("LocTrack", "🔓 DEVICE UNLOCKED — tracking RESUME")
                    onDeviceUnlocked()
                }
            }
        }
    }

    private fun registerScreenLockReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenLockReceiver, filter)
        Log.d("LocationUpdate", "Screen lock receiver registered")
    }

    private fun onDeviceLocked() {

        val currentTime = getCurrentTime()

        // Use current location or last known location
        val lat = previousLat ?: 0.0
        val lng = previousLng ?: 0.0

        // Lock hone par tracking BAND NAHI karni — wake lock + foreground service (LOCATION type)
        // ke saath GPS screen off/lock me bhi chalti rahegi. Sirf "Lock" event audit ke liye log karo.
        Log.i("LocTrack", "🔒 DEVICE LOCKED @ $currentTime (loc: $lat,$lng) — tracking CONTINUE (wake lock active, screen off pe bhi location aayegi)")
        Log.d("LocationUpdate", "Device locked at $currentTime, location: $lat, $lng")
        if (lat == 0.0 || lng == 0.0) {
            Log.w("LocTrack", "   (lock par koi last-known location nahi thi — lock history skip)")
            return
        }
        val lockObj = JSONObject().apply {

            put("status", "Lock")
            put("lat_lng", if (previousLat != null && previousLng != null) "$lat,$lng" else "")
        }


        saveLockUnLockHistory(lockObj, dbPath, lockHistoryPath)
        // NOTE: stopLocationTracking() jaan-boojh ke hata diya — screen off/lock me bhi location chahiye.
    }

    private fun stopLocationTracking() {
        // Guard: agar tracking kabhi start hi na hui ho to locationCallback lateinit uninitialized
        // hota hai — bina check ke access karne par UninitializedPropertyAccessException crash deta.
        if (::locationCallback.isInitialized) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
        if (::saveRunnable.isInitialized) handler.removeCallbacks(saveRunnable)
        saveRunnableActive = false
        // POWER OPT: mode reset — agli baar tracking HIGH-ACCURACY se shuru ho (moving assume).
        lowPowerGps = false
        stationaryRefLat = null
        stationaryRefLng = null
        isTracking = false
        Log.w("LocTrack", "⏹️ STOP: GPS location updates hata diye (isTracking=false) — ab koi point capture nahi hoga")
    }

    /**
     * Screen wapas ON hui (e.g. call khatam). Agar device locked nahi hai aur tracking ruki padi
     * hai to dobara start karo. Yeh unlock-history log NAHI karta (woh sirf genuine unlock par
     * onDeviceUnlocked karta hai) — yeh sirf call/proximity ke baad gap bharne ke liye hai.
     */
    private fun onScreenOn() {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!km.isKeyguardLocked && !isTracking && ::reqLocInterval.isInitialized) {
            Log.i("LocTrack", "▶️ RESUME: screen ON + unlocked (call/proximity khatam) — tracking dobara start")
            startTraversalTracking()
        }
    }

    private fun onDeviceUnlocked() {
        // Reset the reference point: the worker may have moved far while the
        // screen was locked. Without this the next fix would be >100m from the
        // stale previousLat and the traversal buffer would freeze.
        previousLat = null
        previousLng = null
        // Flag to update unlock entry with next GPS fix
        startTraversalTracking()
        Log.i("LocTrack", "▶️ RESUME: unlock ke baad tracking START + reference reseed (agla fix fresh start point)")
        isWaitingForUnlockLocation = true
    }

    private fun saveLockUnLockHistory(json: JSONObject, dbPath: String, lockHistoryPath: String) {
        try {
            ensureFirebasePersistence(dbPath)
            val database = FirebaseDatabase.getInstance(dbPath.trimEnd('/'))
            val reference = database.getReference(lockHistoryPath)

            // Convert JSONObject → Map<String, Any>
            val map = mutableMapOf<String, Any>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                map[key] = json.get(key)
            }


            val currentTime = getCurrentTime()  // Example: "09:25"

            reference.child(currentTime).setValue(map)
                .addOnSuccessListener {
                    Log.i("LocTrack", "✅ Firebase SAVE ok: ${map["status"]} @ $currentTime")
                    Log.d("LockHistory", "✅ Saved at $dbPath/$lockHistoryPath/$currentTime : $map")
                }
                .addOnFailureListener { e ->
                    Log.e("LocTrack", "❌ Firebase SAVE FAIL (${map["status"]} @ $currentTime): ${e.message} — data lost (offline persistence off)")
                    Log.e("LockHistory", "❌ Error saving", e)
                }
        } catch (e: Exception) {
            Log.e("LocTrack", "❌ Firebase SAVE exception: ${e.message} — dbPath sahi hai?")
            Log.e("LockHistory", "❌ Exception: ${e.message}", e)
        }
    }


    // ====== UTILITY METHODS ======
    private fun getCurrentTime(): String {
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
    }

    /**
     * Point 7 (recovery): User ne app ko recents se swipe kiya. Aggressive OEM (Vivo) is par poora
     * process kill kar deta hai — foreground service ke bawajood. Agar tracking ON honi chahiye
     * (isServiceRunning == true) to AlarmManager se ~2s baad service ko wapas start kar dete hain,
     * taaki swipe ke baad location gap na aaye. Logout/23:00/kill-switch me flag false hota hai —
     * un par restart nahi hoga.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val shouldRun = getSharedPreferences("service_prefs", Context.MODE_PRIVATE)
            .getBoolean("isServiceRunning", false)
        if (shouldRun) {
            try {
                val restartIntent = Intent(applicationContext, MyTaskService::class.java)
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                } else {
                    PendingIntent.FLAG_ONE_SHOT
                }
                // O+ par background se plain service start restricted hai — getForegroundService use karo.
                val pending = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    PendingIntent.getForegroundService(this, 1, restartIntent, flags)
                } else {
                    PendingIntent.getService(this, 1, restartIntent, flags)
                }
                val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
                am.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 2000, pending)
                Log.w("LocTrack", "🗑️ onTaskRemoved — app swipe hui, tracking ON thi — ~2s baad restart alarm set")
            } catch (e: Exception) {
                Log.e("LocTrack", "🗑️ onTaskRemoved restart schedule FAIL: ${e.message}")
            }
        } else {
            Log.i("LocTrack", "🗑️ onTaskRemoved — tracking OFF thi (isServiceRunning=false), restart nahi karna")
        }
        super.onTaskRemoved(rootIntent)
    }

    @Suppress("DEPRECATION")
    override fun onDestroy() {
        Log.w("LocTrack", "⛔ SERVICE DESTROYED — location tracking khatam (START_STICKY se system restart karega, bacha data disk buffer me safe)")
        try {
            // Stop location updates
            if (::saveRunnable.isInitialized) {
                handler.removeCallbacks(saveRunnable)
            }
            saveRunnableActive = false
            handler.removeCallbacks(locationWatchdog)
            fusedLocationClient.removeLocationUpdates(locationCallback)
            // Wake lock release — service band ho rahi hai, ab CPU jaga rakhne ki zaroorat nahi.
            releaseWakeLock()
            // Unregister sensors and receivers

            unregisterReceiver(screenLockReceiver)

            // Flush any remaining data
            TravelHistoryManager.flushBackgroundHistoryIfNeeded(this)


            stopForeground(true)
            setServiceRunning(false)

            Log.d("ServiceDestroy", "Service destroyed successfully")
        } catch (error: Exception) {
            Log.e("ServiceDestroy", "Error during cleanup", error)
        } finally {
            super.onDestroy()
        }
    }


}