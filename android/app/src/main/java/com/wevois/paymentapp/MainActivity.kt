package com.wevois.paymentapp

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.util.Log
import android.util.Rational
import androidx.annotation.RequiresApi
import androidx.lifecycle.Lifecycle
import com.facebook.react.ReactActivity
import com.facebook.react.ReactActivityDelegate
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint.fabricEnabled
import com.facebook.react.defaults.DefaultReactActivityDelegate

class MainActivity : ReactActivity() {

  /**
   * Returns the name of the main component registered from JavaScript. This is used to schedule
   * rendering of the component.
   */
  override fun getMainComponentName(): String = "wevois_payment_app"

  /**
   * Returns the instance of the [ReactActivityDelegate]. We use [DefaultReactActivityDelegate]
   * which allows you to enable New Architecture with a single boolean flags [fabricEnabled]
   */
  override fun createReactActivityDelegate(): ReactActivityDelegate =
      DefaultReactActivityDelegate(this, mainComponentName, fabricEnabled)

  // PiP window khula tha ya nahi — close-detection ke liye.
  private var wasInPipMode = false

  /**
   * Home/recents press par call hota hai. Sab guards pass hone par app ko PiP (floating window)
   * mein bhej dete hain. Yahan guard lagana zaroori hai warna camera/payment/settings ke time bhi
   * app shrink ho jaayega.
   */
  override fun onUserLeaveHint() {
    super.onUserLeaveHint()
    tryEnterPipMode()
  }

  private fun tryEnterPipMode() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    if (isInPictureInPictureMode || isFinishing) return
    if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
    if (!PipModule.isPipAllowed) return        // camera/payment/bluetooth ke time JS isko false karta hai
    if (!isTrackingActive()) return            // sirf jab background location tracking chal rahi ho
    if (!hasPipPermission(this)) return        // OEM par PiP permission OFF ho to chup-chaap skip

    try {
      enterPictureInPictureMode(buildPipParams())
    } catch (e: Exception) {
      Log.e("PiP", "enterPictureInPictureMode failed: ${e.message}")
    }
  }

  @RequiresApi(Build.VERSION_CODES.O)
  private fun buildPipParams(): PictureInPictureParams =
      PictureInPictureParams.Builder()
          .setAspectRatio(Rational(9, 16)) // portrait-locked app ke liye
          .build()

  /** MyTaskService start hone par "isServiceRunning" set karti hai — wahi tracking ka source of truth. */
  private fun isTrackingActive(): Boolean {
    val sp = getSharedPreferences("service_prefs", Context.MODE_PRIVATE)
    return sp.getBoolean("isServiceRunning", false)
  }

  override fun onPictureInPictureModeChanged(
    isInPictureInPictureMode: Boolean,
    newConfig: Configuration
  ) {
    super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
    // JS ko PiP state batao taaki WebViewPage PiP me location watch band na kare.
    // Internal app-broadcast — TraversalReceiverModule isko "onPipModeChanged" JS event me forward karta hai.
    try {
      sendBroadcast(Intent(ACTION_PIP_MODE_CHANGED).apply {
        setPackage(packageName)
        putExtra("inPip", isInPictureInPictureMode)
      })
    } catch (e: Exception) {
      Log.e("PiP", "broadcast pip mode failed: ${e.message}")
    }
    if (isInPictureInPictureMode) {
      wasInPipMode = true
    } else {
      // PiP se bahar nikalne ke do case: (a) user ne fullscreen restore kiya, (b) window band ki.
      // Restore par lifecycle STARTED/RESUMED hota hai; window band par STARTED se neeche (CREATED).
      if (wasInPipMode && !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
        notifyAppClosedFromPip()
      }
      wasInPipMode = false
    }
  }

  /**
   * PiP window close hone par service (jo abhi zinda hai) ko bolo ki "AppClosed" Firebase par log
   * kar de. JS/RN context tab tak tear-down ho raha hota hai, isliye service se likhwana reliable hai.
   */
  private fun notifyAppClosedFromPip() {
    try {
      val intent = Intent(this, MyTaskService::class.java).apply {
        action = ACTION_APP_CLOSED
      }
      startService(intent)
    } catch (e: Exception) {
      Log.e("PiP", "notifyAppClosedFromPip failed: ${e.message}")
    }
  }

  companion object {
    const val ACTION_APP_CLOSED = "com.wevois.paymentapp.ACTION_APP_CLOSED"
    const val ACTION_PIP_MODE_CHANGED = "pip_mode_changed"
  }
}
