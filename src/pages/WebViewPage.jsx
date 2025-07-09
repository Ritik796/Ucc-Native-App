import React, {useEffect, useRef, useState} from 'react';
import WebView from 'react-native-webview';
import {
  StyleSheet,
  AppState,
  KeyboardAvoidingView,
  Platform,
} from 'react-native';
import {SafeAreaProvider, SafeAreaView} from 'react-native-safe-area-context';

import LoadingScreen from './LoadingScreen';
import * as action from '../Action/WebViewPageAction/WebViewPageAction';
import CameraComponent from '../components/Camera/Camera';
import BluetoothModule from '../components/Bluetooth/BluetoothModule';
import {reconnectBt} from '../Action/Bluetooth/bluetoothModuleAction';

const WebViewPage = () => {
  const appState = useRef(AppState.currentState);
  const [loading, setLoading] = useState(true);
  const [webKey, setWebKey] = useState(0);
  const [isVisible, setIsVisible] = useState(false);
  const [showCamera, setShowCamera] = useState(false);
  const [base64Image, setBase64Image] = useState('');
  const [loader, setLoader] = useState(false);
  const webViewRef = useRef(null);
  const locationRef = useRef(null);
  const isCameraActive = useRef(null);
  // Bluetooth States
  const [bluetoothEvent, setBluetoothEvent] = useState(null);
  const [btConnectionRequest, setBtConnectionRequest] = useState(null);

  useEffect(() => {
    action.requestLocationPermission();
    const subscription = AppState.addEventListener(
      'change',
      handleAppStateChange,
    );
    return () => subscription.remove();
    // eslint-disable-next-line
  }, []);

  // useEffect(() => {
  //   const backAction = () => {
  //     console.log("backAction");
  //     webViewRef.current?.postMessage(JSON.stringify({ type: "EXIT_REQUEST" }));
  //     return true;
  //   };

  //   const backHandler = BackHandler.addEventListener("hardwareBackPress", backAction);
  //   return () => backHandler.remove();
  // }, []);

  const handleAppStateChange = async nextAppState => {
    console.log('AppState changed:', nextAppState);
    isCameraActive.current = false;
    try {
      if (
        appState.current.match(/inactive|background/) ||
        nextAppState === 'active'
      ) {
        if (isCameraActive?.current) {
          console.log('Back from camera, skipping reload.');
          isCameraActive.current = false;
        } else if (bluetoothEvent) {
          console.log('Wait,Bluetooth module is working..');
        } else {
          console.log('App returned to foreground — reloading WebView');
          setLoading(true); // Mark as loading
          setWebKey(prevKey => prevKey + 1); // Delay ensures loading gets shown
          reconnectBt();
        }
      }

      if (nextAppState.match(/inactive|background/)) {
        console.log(
          'App moved to background/inactive. Stopping location tracking.',
          locationRef.current,
        );
        action.stopTracking(locationRef);
      }

      appState.current = nextAppState;
    } catch (error) {
      console.log(error);
    }
  };

  const handleStopLoading = () => {
    setTimeout(() => setLoading(false), 1000);
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
          style={{flex: 1}}
          enabled
          keyboardVerticalOffset={Platform.OS === 'ios' ? 0 : 0}>
          <WebView
            key={webKey}
            ref={webViewRef}
            onMessage={handleMessage}
            source={{uri: 'http://192.168.29.181:3000'}}
            style={{flex: 1, minHeight: '100%'}} // ✅ Ensure full height
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
