import React, { useEffect, useRef, useState } from 'react';
import WebView from 'react-native-webview';
import {
  StyleSheet,
  AppState,
  KeyboardAvoidingView,
  Platform,
  NativeModules,
  BackHandler,
  DeviceEventEmitter,
} from 'react-native';
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context';

import LoadingScreen from './LoadingScreen';
import * as action from '../Action/WebViewPageAction/WebViewPageAction';
import * as batteryAction from '../Action/BatteryOptimization/batteryOptimizationAction';
import * as pipAction from '../Action/Pip/pipAction';
import CameraComponent from '../components/Camera/Camera';
import PermissionOnboarding from '../components/PermissionOnboarding/PermissionOnboarding';
import BluetoothModule from '../components/Bluetooth/BluetoothModule';
import { reconnectBt } from '../Action/Bluetooth/bluetoothModuleAction';
import NetworkErrorScreen from './NetworkErrorScreen';
import LoadingOffScreen from './LocationOffScreen';
import NetworkOffScreen from './NetWorkOffScreen';

// Itne ms me WebView load poora na ho to loader band karke Retry screen dikhate hain
// (white-screen/crash ke baad remount atak jaaye to infinite loader se bachne ke liye).
const LOAD_TIMEOUT_MS = 20000;

const WebViewPage = () => {
  const appState = useRef(AppState.currentState);
  const [loading, setLoading] = useState(true);
  const [webKey, setWebKey] = useState(0);
  const [isVisible, setIsVisible] = useState(false);
  const [showCamera, setShowCamera] = useState(false);
  const [base64Image, setBase64Image] = useState('');
  const [loader, setLoader] = useState(false);
  const [webData, setWebData] = useState({ userId: "", dbPath: "" });
  const webViewRef = useRef(null);
  const locationRef = useRef(null);
  const isCameraActive = useRef(null);
  const { BackgroundTaskModule, ConnectivityModule, AppResumeModule } = NativeModules;
  // Bluetooth States
  const [bluetoothEvent, setBluetoothEvent] = useState(null);
  const [btConnectionRequest, setBtConnectionRequest] = useState(null);
  const [netWorkError, setNetWorkError] = useState(false);
  const blutoothRef = useRef(false);
  const isDialogVisible = useRef(false);
  const isPaymentProcess = useRef(false);
  // App PiP (floating window) me hai ya nahi. PiP me location tracking band nahi karni —
  // app tab bhi "visible" hai, sirf chhoti window me. Native MainActivity yeh state bhejti hai.
  const isInPipRef = useRef(false);
  // Load-watchdog: jab loader ON ho (initial load / crash-remount / retry) to ek timer chalta hai.
  // Agar itne time me page load (onLoadEnd) na ho — network hang / renderer dobara crash / web app
  // atak gaya — to loader ko infinite chalne ke bajaye band karke Retry screen dikha dete hain.
  const loadTimeoutRef = useRef(null);
  // Aakhri known location (avatar) — reload/recovery ke baad dot turant wapas dikhane ke liye.
  const lastLocationRef = useRef(null);
  const refContext = useRef({ traversalUpdate: null, networkStatus: null, locationStatus: null, appStatus: null, serverTime: null });
  const [status, setStatus] = useState({ networkStatus: false, locationStatus: false });

  useEffect(() => {
    // Sirf location permission yahan. Battery/OEM background settings ab PermissionOnboarding screen
    // (full-screen, PiP-safe checklist) handle karti hai — pehle wala Alert-based ensureBackgroundExecution
    // Vivo par PiP + timing ki wajah se guidance kho deta tha.
    action.requestLocationPermission();

    // Add AppState change listener
    const subscription = AppState.addEventListener('change', handleAppStateChange);

    // Start Android listeners
    const androidListener = action.listenAndroidMessages(refContext, webViewRef, locationRef, isDialogVisible, setStatus);

    // Add back button listener
    const backAction = () => {
      webViewRef.current?.postMessage(JSON.stringify({ type: "EXIT_REQUEST" }));
      return true;
    };
    const backHandler = BackHandler.addEventListener("hardwareBackPress", backAction);

    // Cleanup
    return () => {
      subscription.remove();
      androidListener(); // cleanup Android listeners
      backHandler.remove();
    };

    // eslint-disable-next-line
  }, []);

  useEffect(() => {
    const avatorSub = DeviceEventEmitter.addListener(
      'onAvatarLocationUpdate',
      avatar => {
        // Native se aaya payload malformed/khaali ho to JSON.parse throw karta tha — ye listener
        // har location update par chalta hai, isliye ek bhi bad payload app crash kar sakta tha.
        try {
          const data = JSON.parse(avatar);
          // Aakhri known location yaad rakho — reload/recovery ke baad dot turant dikhane ke liye.
          if (data?.latitude && data?.longitude) {
            lastLocationRef.current = { lat: data.latitude, lng: data.longitude, acc: data.Accuracy };
          }
          action.handleTravelHistory('avatar', data, webViewRef);
        } catch (e) {
          console.log('onAvatarLocationUpdate parse error:', e);
        }
      }
    );
    const travelSub = DeviceEventEmitter.addListener(
      'onTraversalUpdate',
      history => {
        try {
          const data = JSON.parse(history);
          action.handleTravelHistory('history', data, webViewRef);
        } catch (e) {
          console.log('onTraversalUpdate parse error:', e);
        }
      }
    );
    const lockSub = DeviceEventEmitter.addListener(
      'onLockHistoryUpdate',
      lockHistory => {
        try {
          const data = JSON.parse(lockHistory);
          action.handleSaveLockHistory(data, webViewRef);
        } catch (e) {
          console.log('onLockHistoryUpdate parse error:', e);
        }
      }
    );


    return () => {
      travelSub?.remove();
      avatorSub?.remove();
      lockSub?.remove();
    };
  }, []);

  // Native PiP mode change sun kar flag set karo. PiP me aate hi (agar watch band ho gaya ho)
  // location tracking dobara ensure kar lo — AppState 'background' event PiP event se pehle aa
  // sakta hai, isliye yeh race-safe restart zaroori hai.
  useEffect(() => {
    const pipSub = DeviceEventEmitter.addListener('onPipModeChanged', value => {
      const inPip = value === 'true';
      isInPipRef.current = inPip;
      if (inPip) {
        action.resumeTrackingIfDesired(locationRef, webViewRef);
      }
    });
    return () => pipSub?.remove();
  }, []);
  // Camera khula ho to PiP block karo (camera + PiP saath mein resource conflict/crash de sakta hai).
  // Camera band hote hi wapas allow.
  useEffect(() => {
    pipAction.setPipAllowed(!showCamera);
  }, [showCamera]);

  const handleAppStateChange = async nextAppState => {
    try {
      const wasInBackground = appState.current.match(/inactive|background/);
      const isNowActive = nextAppState === 'active';

      if (wasInBackground || isNowActive) {
        if (isNowActive) {
          console.log('[LocTrack] ▶️ app FOREGROUND/ACTIVE — JS watch resume check');
          startConnectivityListener();
          // App wapas active (e.g. call ke baad) — agar location watch ruk gaya tha to dobara start karo.
          action.resumeTrackingIfDesired(locationRef, webViewRef);
          // Shift ke beech location permission revoke ho gayi ho (Android 11+ auto-reset / manual) to
          // foreground aate hi dobara check + nudge — warna tracking silently ruki rehti thi.
          action.recheckLocationPermissions();
          // Power Saving mode ON ho to warn (OEM background kill/throttle karta hai is mode me).
          batteryAction.warnIfPowerSaveMode();
          if (webViewRef.current) {
            webViewRef.current.postMessage(JSON.stringify({
              type: 'APP_STATE_ACTIVE'
            }));
          }

        }
        if (isCameraActive.current) {
          isCameraActive.current = false;
          return;
        }

        if (blutoothRef.current) {
          return;
        }

        if (isDialogVisible.current) {
          // Skip reload completely
          return;
        }
        if (netWorkError) {
          return;
        }
        if (isPaymentProcess.current) {
          return;
        }
        // ✅ Reload only if none of the above are active
        // setLoading(true);
        // setWebKey(prevKey => prevKey + 1);
        reconnectBt();
      }

      if (nextAppState.match(/inactive|background/)) {
        // PiP me location band NAHI karni — sirf screen-off / app remove par rukni chahiye.
        // (Screen-off/app-remove native MyTaskService khud handle karti hai.)
        if (!isInPipRef.current) {
          console.log('[LocTrack] ⏸️ app BACKGROUND — JS watch stop (native service background me chalti rahegi)');
          action.stopTracking(locationRef);
        } else {
          console.log('[LocTrack] app BACKGROUND par PiP me hai — JS watch continue');
        }
        stopConnectivityListener();
        setNetWorkError(false);

      }

      appState.current = nextAppState;
    } catch (error) {
      console.log('App state change error:', error);
    }
  };

  const handleSaveTraversalHistory = (history) => {
    action.startSavingTraversalHistory(history);
  };

  // Load-watchdog: webKey (remount) ya loading badalne par chalta hai. Loading ON hai to timer set;
  // load poora hote hi (loading false) clear. Timer expire = load atak gaya → loader hatao + Retry.
  useEffect(() => {
    if (loadTimeoutRef.current) {
      clearTimeout(loadTimeoutRef.current);
      loadTimeoutRef.current = null;
    }
    if (loading) {
      loadTimeoutRef.current = setTimeout(() => {
        console.log('WebView load timeout — endless loader ke bajaye Retry dikha rahe hain');
        setLoading(false);
        setNetWorkError(true);
      }, LOAD_TIMEOUT_MS);
    }
    return () => {
      if (loadTimeoutRef.current) {
        clearTimeout(loadTimeoutRef.current);
        loadTimeoutRef.current = null;
      }
    };
  }, [loading, webKey]);

  const handleStopLoading = () => {
    // Load poora ho gaya — watchdog turant band karo, phir loader hata do.
    if (loadTimeoutRef.current) {
      clearTimeout(loadTimeoutRef.current);
      loadTimeoutRef.current = null;
    }
    setTimeout(() => {
      setLoading(false);
    }, 1000);
    // Reload/recovery ke baad web app khaali hota hai — aakhri known location wapas push kar do taaki
    // avatar dot TURANT dikhe (agle fix ka wait na karna pade, worker stationary ho tab bhi). Ye sirf
    // display (dot) ke liye hai — saved route data par koi asar nahi. Thoda delay taaki web app ka
    // message-listener ready ho jaaye. Uske baad normal on-change updates dot ko fresh rakhte hain.
    if (lastLocationRef.current) {
      setTimeout(() => {
        webViewRef.current?.postMessage(JSON.stringify({
          type: 'Location',
          status: 'success',
          data: lastLocationRef.current,
        }));
      }, 1500);
    }
    startConnectivityListener();


  };
  const startConnectivityListener = () => {
    ConnectivityModule.startMonitoring();
    AppResumeModule?.initLifecycleTracking?.();
  };
  const stopConnectivityListener = () => {
    ConnectivityModule.stopMonitoring();

  };

  const handleRetry = () => {
    setLoading(true);
    setWebKey(prevKey => prevKey + 1);
    setNetWorkError(false);
  };
  // WebView ka renderer process mar gaya (PiP/background me memory pressure par Android Chromium
  // renderer ko kill kar deta hai) — surface blank/white ho jaata hai aur khud recover nahi hota.
  // `key` badal kar WebView ko remount karte hain taaki page dobara load ho; warna PiP se fullscreen
  // aane par bhi wahi mara hua (white) WebView dikhta rehta hai.
  const handleWebViewCrash = syntheticEvent => {
    const didCrash = syntheticEvent?.nativeEvent?.didCrash;
    console.log('WebView renderer gone — remounting. didCrash:', didCrash);
    setLoading(true);
    setWebKey(prevKey => prevKey + 1);
    return true; // crash handle kar liya — system ko app terminate mat karne do
  };
  const handleMessage = event => {
    action.readWebViewMessage(
      event,
      webViewRef,
      locationRef,
      isCameraActive,
      setShowCamera,
      setIsVisible,
      setBluetoothEvent,
      setBtConnectionRequest,
      setWebData,
      BackgroundTaskModule,
      blutoothRef,
      isPaymentProcess,
      AppResumeModule
    );
  };
  return (
    <SafeAreaProvider>
      {/* Ek-baar ka background-permission setup (Vivo/Oppo/Xiaomi ke liye). Component khud decide karta
          hai dikhana hai ya nahi; zaroorat na ho to turant chhup jaata hai. PiP-safe. */}
      <PermissionOnboarding onDone={() => {}} />
      <SafeAreaView style={styles.safeContainer}>
        {loading && <LoadingScreen />}
        {netWorkError && <NetworkErrorScreen handleRetry={handleRetry} />}
        {status.networkStatus && <NetworkOffScreen handleRetry={handleRetry} />}
        {status.locationStatus && <LoadingOffScreen handleRetry={handleRetry} />}
        {showCamera && (
          <CameraComponent
            loader={loader}
            setLoader={setLoader}
            isCameraActive={isCameraActive}
            isVisible={isVisible}
            setIsVisible={setIsVisible}
            setBase64Image={setBase64Image}
            setShowCamera={setShowCamera}
            webViewRef={webViewRef}
            base64Image={base64Image}
            locationRef={locationRef}
          />
        )}
        {/* ✅ Improved KeyboardAvoidingView */}
        <KeyboardAvoidingView
          behavior={Platform.OS === 'ios' ? 'padding' : undefined}
          style={{ flex: 1 }}
          enabled
          keyboardVerticalOffset={Platform.OS === 'ios' ? 0 : 0}>
          <WebView
            key={webKey}
            ref={webViewRef}
            onMessage={handleMessage}
            source={{ uri: 'https://testing-cde04.web.app' }}
            style={{ flex: 1, minHeight: '100%' }} // ✅ Ensure full height
            geolocationEnabled={true}
            mediaPlaybackRequiresUserAction={false}
            javaScriptEnabled={true}
            domStorageEnabled={true}
            setBuiltInZoomControls={false}
            setDisplayZoomControls={false}
            onLoadEnd={handleStopLoading}
            pullToRefreshEnabled={true}
            onError={() => { setNetWorkError(true); setLoading(false); }}
            onRenderProcessGone={handleWebViewCrash}
          />
        </KeyboardAvoidingView>
      </SafeAreaView>
      {/* {bluetoothEvent && ( */}
      <BluetoothModule
        btEvent={bluetoothEvent}
        setBtEvent={setBluetoothEvent}
        webViewRef={webViewRef}
        btConnectionRequest={btConnectionRequest}
        setBtConnectionRequest={setBtConnectionRequest}
        blutoothRef={blutoothRef}
      />
      {/* )} */}
    </SafeAreaProvider>
  );
};

const styles = StyleSheet.create({
  safeContainer: {
    flex: 1,
    backgroundColor: 'black',
  },
  container: {
    flex: 1,
  },
});

export default WebViewPage;
