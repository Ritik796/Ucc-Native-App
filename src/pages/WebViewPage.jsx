import React, { useEffect, useRef, useState } from 'react';
import WebView from 'react-native-webview';
import {
  StyleSheet,
  AppState,
  KeyboardAvoidingView,
  Platform,
  NativeModules,
  BackHandler

} from 'react-native';
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context';

import LoadingScreen from './LoadingScreen';
import * as action from '../Action/WebViewPageAction/WebViewPageAction';
import CameraComponent from '../components/Camera/Camera';
import BluetoothModule from '../components/Bluetooth/BluetoothModule';
import { reconnectBt } from '../Action/Bluetooth/bluetoothModuleAction';

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
  const blutoothRef = useRef(false);
  const refContext = useRef({ traversalUpdate: null, networkStatus: null, locationStatus: null });

useEffect(() => {
  // Request location permission
  action.requestLocationPermission();

  // Add AppState change listener
  const subscription = AppState.addEventListener('change', handleAppStateChange);

  // Start Android listeners
  const androidListener = action.listenAndroidMessages(refContext, webViewRef, BackgroundTaskModule, locationRef);

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


  const handleAppStateChange = async nextAppState => {
    isCameraActive.current = false;
    try {
      if (
        appState.current.match(/inactive|background/) ||
        nextAppState === 'active'
      ) {
        if (isCameraActive?.current) {
          isCameraActive.current = false;
          return;
        } else if (blutoothRef?.current) {
          return;
        } else {
          setLoading(true); // Mark as loading
          setWebKey(prevKey => prevKey + 1); // Delay ensures loading gets shown
          reconnectBt();
        }
      }

      if (nextAppState.match(/inactive|background/)) {
        action.stopTracking(locationRef);
        stopConnectivityListener();
      }

      appState.current = nextAppState;
    } catch (error) {
      return;
    }
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
      blutoothRef
    );
  };
  return (
    <SafeAreaProvider>
      <SafeAreaView style={styles.safeContainer}>
        {loading && <LoadingScreen />}
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
            source={{ uri: 'http://192.168.12.144:3000/' }}
            style={{ flex: 1, minHeight: '100%' }} // ✅ Ensure full height
            geolocationEnabled={true}
            mediaPlaybackRequiresUserAction={false}
            javaScriptEnabled={true}
            domStorageEnabled={true}
            setBuiltInZoomControls={false}
            setDisplayZoomControls={false}
            onLoadEnd={handleStopLoading}
            pullToRefreshEnabled={true}
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
