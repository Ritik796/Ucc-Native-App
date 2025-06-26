import React, { useEffect, useRef, useState } from "react";
import WebView from "react-native-webview";
import {
  StyleSheet,
  AppState,
  KeyboardAvoidingView,
  Platform,
} from "react-native";
import { SafeAreaProvider, SafeAreaView } from "react-native-safe-area-context";

import LoadingScreen from "./LoadingScreen";
import * as action from "../Action/WebViewPageAction/WebViewPageAction";

const WebViewPage = () => {
  const appState = useRef(AppState.currentState);
  const [loading, setLoading] = useState(true);
  const [webKey, setWebKey] = useState(0);
  const webViewRef = useRef(null);
  const locationRef = useRef(null);

  useEffect(() => {
    action.requestLocationPermission();
    const subscription = AppState.addEventListener("change", handleAppStateChange);
    return () => subscription.remove();
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


  const handleAppStateChange = async (nextAppState) => {
    console.log("AppState changed:", nextAppState);

    if (appState.current.match(/inactive|background/) || nextAppState === "active") {
      console.log("App returned to foreground — reloading WebView");
      setLoading(true); // Mark as loading
      setWebKey((prevKey) => prevKey + 1); // Delay ensures loading gets shown
    }

    if (nextAppState.match(/inactive|background/)) {
      console.log("App moved to background/inactive. Stopping location tracking.", locationRef.current);
      action.stopLocationTracking(locationRef);
    }

    appState.current = nextAppState;
  };

  const handleStopLoading = () => {
    setTimeout(() => setLoading(false), 1000);
  };

  const handleMessage = (event) => {
    action.readWebViewMessage(event, webViewRef, locationRef);
  };




  return (
    <SafeAreaProvider>
      <SafeAreaView style={styles.safeContainer}>
        {loading && <LoadingScreen />}

        {/* ✅ Improved KeyboardAvoidingView */}
        <KeyboardAvoidingView
          behavior={Platform.OS === "ios" ? "padding" : undefined}
          style={{ flex: 1 }}
          enabled
          keyboardVerticalOffset={Platform.OS === "ios" ? 0 : 0}
        >
          <WebView
            key={webKey}
            ref={webViewRef}
            onMessage={handleMessage}
            source={{ uri: "https://ucc-payment-app.web.app" }}
            style={{ flex: 1, minHeight: "100%" }} // ✅ Ensure full height
            geolocationEnabled={true}
            mediaPlaybackRequiresUserAction={false}
            javaScriptEnabled={true}
            domStorageEnabled={true}
            setBuiltInZoomControls={false}
            setDisplayZoomControls={false}
            onLoadEnd={handleStopLoading}
          />
        </KeyboardAvoidingView>
      </SafeAreaView>
    </SafeAreaProvider>
  );
};

const styles = StyleSheet.create({
  safeContainer: {
    flex: 1,
    backgroundColor: "black"
  },
  container: {
    flex: 1,
  },
});

export default WebViewPage;
