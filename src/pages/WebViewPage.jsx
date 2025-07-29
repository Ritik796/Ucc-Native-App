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
import CameraComponent from '../components/Camera/Camera';
import BluetoothModule from '../components/Bluetooth/BluetoothModule';
import { reconnectBt } from '../Action/Bluetooth/bluetoothModuleAction';
import NetworkErrorScreen from './NetworkErrorScreen';
import LoadingOffScreen from './LocationOffScreen';
import NetworkOffScreen from './NetWorkOffScreen';

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
  const { BackgroundTaskModule, ConnectivityModule } = NativeModules;
  // Bluetooth States
  const [bluetoothEvent, setBluetoothEvent] = useState(null);
  const [btConnectionRequest, setBtConnectionRequest] = useState(null);
  const [netWorkError, setNetWorkError] = useState(false);
  const blutoothRef = useRef(false);
  const isDialogVisible = useRef(false);
  const isPaymentProcess = useRef(false);
  const refContext = useRef({ traversalUpdate: null, networkStatus: null, locationStatus: null, appStatus: null });
  const [status,setStatus] = useState({networkStatus: false, locationStatus: false});

  useEffect(() => {
    // Request location permission

    action.requestLocationPermission();

    // Add AppState change listener
    const subscription = AppState.addEventListener('change', handleAppStateChange);

    // Start Android listeners
    const androidListener = action.listenAndroidMessages(refContext, webViewRef, locationRef, isDialogVisible,setStatus);

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
    const subscription = DeviceEventEmitter.addListener(
      'onTraversalUpdate',
      history => {
        handleSaveTraversalHistory(history);
      }
    );
    return () => {
      subscription.remove();
    };
  }, []);
  const handleAppStateChange = async nextAppState => {
    try {
      const wasInBackground = appState.current.match(/inactive|background/);
      const isNowActive = nextAppState === 'active';

      if (wasInBackground || isNowActive) {
        if (isNowActive) {
          startConnectivityListener();
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
        setLoading(true);
        setWebKey(prevKey => prevKey + 1);
        reconnectBt();
      }

      if (nextAppState.match(/inactive|background/)) {
        action.stopTracking(locationRef);
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

  const handleStopLoading = () => {
    setTimeout(() => setLoading(false), 1000);
    startConnectivityListener();


  };
  const startConnectivityListener = () => {
    ConnectivityModule.startMonitoring();
  };
  const stopConnectivityListener = () => {
    ConnectivityModule.stopMonitoring();
  };

  const handleRetry = () => {
    setLoading(true);
    setWebKey(prevKey => prevKey + 1);
    setNetWorkError(false);
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
      isPaymentProcess
    );
  };
  return (
    <SafeAreaProvider>
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
            source={{ uri: 'https://fir-project-d59e1.web.app' }}
            style={{ flex: 1, minHeight: '100%' }} // ✅ Ensure full height
            geolocationEnabled={true}
            mediaPlaybackRequiresUserAction={false}
            javaScriptEnabled={true}
            domStorageEnabled={true}
            setBuiltInZoomControls={false}
            setDisplayZoomControls={false}
            onLoadEnd={handleStopLoading}
            pullToRefreshEnabled={true}
            onError={() => setNetWorkError(true)}
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
