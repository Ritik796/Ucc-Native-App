import axios from "axios";
import { PermissionsAndroid, Platform, DeviceEventEmitter, BackHandler, Alert, Linking } from "react-native";
import Geolocation from "@react-native-community/geolocation";
import DeviceInfo from "react-native-device-info";
import * as locationService from '../../Services/LocationServices';


export const requestLocationPermission = async () => {
  try {
    if (Platform.OS !== 'android') return true;

    let isPermission = true;

    // Step 1: Request initial permissions
    const granted = await PermissionsAndroid.requestMultiple([
      PermissionsAndroid.PERMISSIONS.CAMERA,
      PermissionsAndroid.PERMISSIONS.READ_EXTERNAL_STORAGE,
      PermissionsAndroid.PERMISSIONS.READ_MEDIA_IMAGES,
      PermissionsAndroid.PERMISSIONS.ACCESS_MEDIA_LOCATION,
      PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION,
      PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS
    ]);

    const checkPermission = (perm) =>
      granted[perm] === PermissionsAndroid.RESULTS.GRANTED;

    if (
      !checkPermission(PermissionsAndroid.PERMISSIONS.CAMERA) ||
      !checkPermission(PermissionsAndroid.PERMISSIONS.READ_EXTERNAL_STORAGE) ||
      !checkPermission(PermissionsAndroid.PERMISSIONS.READ_MEDIA_IMAGES) ||
      !checkPermission(PermissionsAndroid.PERMISSIONS.ACCESS_MEDIA_LOCATION) ||
      !checkPermission(PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION) ||
      !checkPermission(PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS)
    ) {
      isPermission = false;
    }

    // ✅ Step 2: Check if background location is already granted
    const alreadyGranted = await PermissionsAndroid.check(
      PermissionsAndroid.PERMISSIONS.ACCESS_BACKGROUND_LOCATION
    );

    if (alreadyGranted) {
      return isPermission; // ✅ Don't ask again if already granted
    }

    // Step 3: Show guidance alert ONCE if not granted
    await new Promise((resolve) => {
      Alert.alert(
        'Background Location Required',
        'To continue, please allow background location access and select "Allow all the time" on the next screen.',
        [
          {
            text: 'Continue',
            onPress: () => resolve(true)
          }
        ],
        { cancelable: false }
      );
    });

    // Step 4: Request background location in a loop until granted or user opens settings
    let loop = true;
    while (loop) {
      const bgGranted = await PermissionsAndroid.request(
        PermissionsAndroid.PERMISSIONS.ACCESS_BACKGROUND_LOCATION
      );

      if (bgGranted === PermissionsAndroid.RESULTS.GRANTED) {
        loop = false;
        return isPermission;
      } else if (bgGranted === PermissionsAndroid.RESULTS.DENIED) {
        // Show retry alert
        await new Promise((resolve) => {
          Alert.alert(
            'Permission Needed',
            'Background location is required to proceed. Please allow it.',
            [
              {
                text: 'Retry',
                onPress: () => resolve(true)
              }
            ],
            { cancelable: false }
          );
        });
      } else if (bgGranted === PermissionsAndroid.RESULTS.NEVER_ASK_AGAIN) {
        // Redirect to settings
        await new Promise((resolve) => {
          Alert.alert(
            'Enable Permission',
            'Background location permission has been permanently denied. Please enable it from app settings.',
            [
              {
                text: 'Open Settings',
                onPress: async () => {
                  await Linking.openSettings();
                  resolve(true);
                }
              }
            ],
            { cancelable: false }
          );
        });
      }
    }

    return false;
  } catch (err) {
    console.warn('Permission error:', err);
    return false;
  }
};
export const startLocationTracking = async (locationRef, webViewRef) => {
    try {
        console.log('StartLocationTracking')
        const watchId = Geolocation.watchPosition(
            (position) => {
                const { latitude, longitude, accuracy } = position.coords;
                console.log('Location:', latitude, longitude, accuracy);
                if (accuracy != null && accuracy <= 15) {
                    webViewRef?.current?.postMessage(JSON.stringify({ type: "Location", status: "success", data: { lat: latitude, lng: longitude } }));
                }
            },
            (error) => {
                webViewRef?.current?.postMessage(JSON.stringify({ type: "Location", status: "fail", data: null }));
            },
            {
                enableHighAccuracy: true,
                distanceFilter: 10,          // Trigger every ~10 meter
                interval: 10000,            // Regular update every 10s
                fastestInterval: 6000,      // Minimum interval for updates
                maximumAge: 0
            }

        );

        locationRef.current = watchId;
    } catch (error) {
        if (locationRef.current != null) {
            Geolocation.clearWatch(locationRef.current);
            locationRef.current = null;
        }
    }
};

export const stopLocationTracking = (locationRef, setWebData) => {
    if (locationRef?.current) {
        Geolocation.clearWatch(locationRef.current);
        locationRef.current = null;

        if (setWebData) {
            setWebData((prev) => ({ ...prev, userId: "", city: "" }));
        }
    }
};

export const stopTracking = async (locationRef) => {
    if (locationRef?.current) {
        console.log(`Stopping location tracking... Watch ID: ${locationRef.current}`);
        await Geolocation.clearWatch(locationRef.current);
        locationRef.current = null;
    }
};

export const readWebViewMessage = async (event, webViewRef, locationRef, isCameraActive, setShowCamera, setIsVisible, setBluetoothEvent, setBtConnectionRequest, setWebData, BackgroundTaskModule, blutoothRef, isPaymentProcess) => {
    let data = event?.nativeEvent?.data;
    try {
        let msg = JSON.parse(data);
        switch (msg?.type) {
            case 'startLocationTracking':
                console.log(`Start location tracking... Watch ID: ${locationRef.current}`);
                startLocationTracking(locationRef, webViewRef);

                checkBackgroundTaskStarted(BackgroundTaskModule, msg?.data?.userId, msg?.data?.dbPath, msg?.data?.travelPath);
                break;
            case 'openCamera':
                const isLocationEnabled = await DeviceInfo.isLocationEnabled();
                if (isLocationEnabled) {
                    isCameraActive.current = true;
                    setShowCamera(true);
                    setIsVisible(true);
                } else {
                    webViewRef.current?.postMessage(JSON.stringify({ type: "Location_Status", data: { isLocationOn: false } }));
                    isCameraActive.current = false;
                }
                break;
            case 'print-receipt':
                blutoothRef.current = true;
                setBluetoothEvent(msg);
                break;
            case 'connect-bt':
                blutoothRef.current = true;
                setBtConnectionRequest(msg);
                break;

            case 'StartBackGroundService':
                setWebData(pre => ({ ...pre, userId: msg?.data?.userId || "", dbPath: msg?.data?.dbPath || "", travelPath: msg?.data?.travelPath || "" }));
                StartBackgroundTask(msg.data.userId, msg.data.dbPath, msg.data.travelPath, BackgroundTaskModule);
                break;
            case 'Logout':
                StopBackGroundTask(BackgroundTaskModule);
                stopTracking(locationRef);
                break;
            case 'Exit_App':
                handleExitApp();
                break;
            case 'message':
                // console.log(msg.type, msg.data)
                break;
            case 'payment':
                isPaymentProcess.current = true;
                sendPaymentRequestToUrl(msg?.data?.paymentData, msg?.data?.url, msg?.data?.deviceType, webViewRef);
                break;
            case 'Payment_Process_Done':
                setTimeout(() => {
                    isPaymentProcess.current = false;
                }, 2000);
                break;
            case 'paymentStatus':
                getPaymentStatusFromApi(webViewRef, msg?.data?.url, msg?.data?.payloadData, msg?.data?.deviceType);
                break;
            case 'check-location':
                checkUserLocation(webViewRef);
                break;
            case 'check-version':
                checkAppVersion(msg?.data?.version, webViewRef);
                break;
            default:
                break;
        }
    } catch (error) {
        return;
    }
};

export const checkAppVersion = async (version, webViewRef) => {
    if (version) {
        const currentVersion = await DeviceInfo.getVersion();
        const required = version?.toString()?.trim();
        if (required === currentVersion?.toString()?.trim()) {
            webViewRef.current?.postMessage(JSON.stringify({ type: "Version_Valid" }));
        } else {
            webViewRef.current?.postMessage(JSON.stringify({ type: "Version_Expired" }));
        }
    } else {
        console.warn("No version provided to check.");
    }
}
const StartBackgroundTask = (userId, dbPath, travelPath, BackgroundTaskModule) => {
    BackgroundTaskModule.startBackgroundTask({
        USER_ID: userId || "",
        DB_PATH: dbPath || "",
        TRAVEL_PATH: travelPath || "",
    });
};

const StopBackGroundTask = (BackgroundTaskModule) => {
    BackgroundTaskModule.stopBackgroundTask();
};
export const startSavingTraversalHistory = async (history) => {
    let data = JSON.parse(history);
    locationService.saveLocationHistory(data.path, data.distance, data.time, data.userId, data.travelPath, data.dbPath);
};
const checkBackgroundTaskStarted = (BackgroundTaskModule, userId, dbPath, travelPath) => {
    if (!userId || !dbPath || !travelPath) {
        console.warn("User ID, DB Path or Travel Path is undefined, skipping background task check.");
        return;

    }
    BackgroundTaskModule.checkAndRestartBackgroundTask({
        USER_ID: userId || "",
        DB_PATH: dbPath || "",
        TRAVEL_PATH: travelPath || "",
    });
    return;
};


export const listenAndroidMessages = (refContext, webViewRef, locationRef, isDialogVisible,setStatus) => {

    refContext.current.networkStatus = DeviceEventEmitter.addListener(
        'onConnectivityStatus',
        mobile => {
            sendNetWorkStatus(mobile, webViewRef,setStatus);
        }
    );

    refContext.current.locationStatus = DeviceEventEmitter.addListener(
        'onLocationStatus',
        location => {

            sendLocationStatus(location, webViewRef, locationRef,setStatus);
        }

    );
    refContext.current.appStatus = DeviceEventEmitter.addListener(
        'onSystemDialogStatus',
        appStatus => {
            if (appStatus?.dialog) {
                isDialogVisible.current = true;
            }
            else {
                isDialogVisible.current = false;
            }
        }
    );


    return () => {
        refContext?.current?.networkStatus?.remove();
        refContext?.current?.locationStatus?.remove();
        refContext?.current?.appStatus?.remove();
    };
};



const sendNetWorkStatus = (mobile, webViewRef,setStatus) => {
    setStatus((prev) => ({ ...prev, networkStatus: !mobile?.isMobileDataOn  }));
};
const sendLocationStatus = (location, webViewRef, locationRef,setStatus) => {
    setStatus((prev) => ({ ...prev, locationStatus: !location?.isLocationOn }));    

    if (location?.isLocationOn === false) {
        stopTracking(locationRef);
    }
    else if (location?.isLocationOn === true && locationRef?.current !== null) {
        startLocationTracking(locationRef, webViewRef);
    }
};

const handleExitApp = () => {
    BackHandler.exitApp();
};

const sendPaymentRequestToUrl = async (paymentPayload, url, deviceType, webViewRef) => {
    try {
        const response = await axios.post(url, paymentPayload, {
            headers: { 'Content-Type': 'application/json' }
        });
        let injectedJS;
        if (response.status === 200 && response.data) {
            let responseData;
            if (deviceType === 'pine') {
                responseData = { ...response?.data, deviceType: deviceType };
            } else if (deviceType === 'orange') {
                if (response?.data?.ResponseCode === '00' && response?.data?.ResponseDesc === 'Success') {
                    responseData = {
                        ...response?.data,
                        ResponseCode: 0,
                        ResponseMessage: 'APPROVED',
                        deviceType: deviceType
                    };
                } else {
                    responseData = {
                        ...response?.data,
                        ResponseCode: Number(response?.data?.ResponseCode),
                        ResponseMessage: response?.data?.ResponseDesc,
                        deviceType: deviceType
                    };
                }

            }
            // console.log(responseData)
            injectedJS = `
            window.dispatchEvent(new MessageEvent('message', {
                data: JSON.stringify({
                    type: 'payment-success',
                    status: 'success',
                    data: ${JSON.stringify(responseData)}
                })
            }));
        `;
        } else {
            injectedJS = `
            window.dispatchEvent(new MessageEvent('message', {
                data: JSON.stringify({
                    type: 'payment-error',
                    status: 'fail',
                    data: ${JSON.stringify(response.data)}
                })
            }));
        `;
        }
        webViewRef.current?.injectJavaScript(injectedJS);

    } catch (error) {
        const errorJS = `
            window.dispatchEvent(new MessageEvent('message', {
                data: JSON.stringify({
                    type: 'payment-catch-error',
                    error: ${JSON.stringify(error.message)}
                })
            }));
        `;
        webViewRef.current?.injectJavaScript(errorJS);
    }
};

const getPaymentStatusFromApi = (webViewRef, url, payloadData, deviceType) => {
    if (deviceType === 'pine') {
        checkPineTransactionStatus(webViewRef, url, payloadData);
    } else {
        const startTime = Date.now();
        const control = { sent: false }; // ✅ this object is shared by reference

        const intervalId = setInterval(() => {
            const elapsedTime = Date.now() - startTime;

            if (elapsedTime < 120500 && !control.sent) {
                checkOrangeTransactionStatus(webViewRef, url, payloadData, elapsedTime, control);
            } else if (!control.sent) {
                const errorJS = `
                    window.dispatchEvent(new MessageEvent('message', {
                        data: JSON.stringify({
                            type: 'paymentStatus-catch-error',
                            message: 'Transaction Timeout'
                        })
                    }));
                `;
                webViewRef.current?.injectJavaScript(errorJS);
                control.sent = true;
                clearInterval(intervalId);
            }
        }, 6000);

        return () => clearInterval(intervalId);
    }
};


const checkPineTransactionStatus = (webViewRef, url, payloadData) => {
    let attempt = 1;
    const maxAttempts = 35;
    const interval = setInterval(async () => {
        try {
            const response = await axios.post(url, payloadData, {
                headers: { 'Content-Type': 'application/json' }
            });

            let responseData;
            const { ResponseCode } = response.data;
            const code = Number(ResponseCode);
            responseData = { ...response?.data };
            if (code === 0) {
                const successJS = `
                window.dispatchEvent(new MessageEvent('message', {
                    data: JSON.stringify({
                        type: 'paymentStatus-success',
                        data: ${JSON.stringify(responseData)}
                    })
                }));
            `;
                webViewRef.current?.injectJavaScript(successJS);
                clearInterval(interval);
                return;
            } else if (code === 1 || code === 1052 || code === 2) {
                const failJS = `
                window.dispatchEvent(new MessageEvent('message', {
                    data: JSON.stringify({
                        type: 'paymentStatus-error',
                        message:${JSON.stringify(responseData?.ResponseMessage)}
                    })
                }));
            `;
                webViewRef.current?.injectJavaScript(failJS);
                clearInterval(interval);
                return;
            } else {
                // console.log(`⚠️ Retry after 6 sec... ResponseCode=${code}`);
            }
        } catch (error) {
            const errorJS = `
                window.dispatchEvent(new MessageEvent('message', {
                    data: JSON.stringify({
                        type: 'paymentStatus-catch-error',
                        message: ${JSON.stringify(error.message)}
                    })
                }));
            `;
            webViewRef.current?.injectJavaScript(errorJS);
            clearInterval(interval);
            return;
        }
        attempt++;
        if (attempt > maxAttempts) {
            console.warn('Max payment status attempts reached, stopping polling.');
            const errorJS = `
                window.dispatchEvent(new MessageEvent('message', {
                    data: JSON.stringify({
                        type: 'paymentStatus-catch-error',
                        message: 'Transaction Timeout'
                    })
                }));
            `;
            webViewRef.current?.injectJavaScript(errorJS);
            clearInterval(interval);
            return;
        }
    }, 6000);
};

const checkOrangeTransactionStatus = async (webViewRef, url, payloadData, elapsedTime, control) => {
    try {
        const response = await axios.post(url, payloadData, {
            headers: { 'Content-Type': 'application/json' }
        });

        const { ResponseCode, ResponseDesc } = response.data;
        const code = Number(ResponseCode);
        const responseData = { ...response.data, ResponseCode: code, ResponseMessage: ResponseDesc };

        if (code === 0 && !control.sent) {
            const successJS = `
                window.dispatchEvent(new MessageEvent('message', {
                    data: JSON.stringify({
                        type: 'paymentStatus-success',
                        data: ${JSON.stringify(responseData)}
                    })
                }));
            `;
            webViewRef.current?.injectJavaScript(successJS);
            control.sent = true;
        } else if (code === 2 && !control.sent) {
            const failJS = `
            window.dispatchEvent(new MessageEvent('message', {
                data: JSON.stringify({
                    type: 'paymentStatus-error',
                    message: ${JSON.stringify(ResponseDesc)}
                })
            }));
        `;
            webViewRef.current?.injectJavaScript(failJS);
            control.sent = true;
        } else if ((code === 1 || code === 1052) && !control.sent) {
            if (elapsedTime > 110500) {
                const failJS = `
            window.dispatchEvent(new MessageEvent('message', {
                data: JSON.stringify({
                    type: 'paymentStatus-error',
                    message: ${JSON.stringify(ResponseDesc)}
                })
            }));
        `;
                webViewRef.current?.injectJavaScript(failJS);
                control.sent = true;
            } else {
                // 🕒 Don’t do anything yet — wait for next interval
                // console.log('Failure code received but waiting for more time before sending failure response...');
            }
        }
        // else do nothing (pending response), try again in next interval
    } catch (error) {
        if (!control.sent) {
            const errorJS = `
                window.dispatchEvent(new MessageEvent('message', {
                    data: JSON.stringify({
                        type: 'paymentStatus-catch-error',
                        message: ${JSON.stringify(error.message)}
                    })
                }));
            `;
            webViewRef.current?.injectJavaScript(errorJS);
            control.sent = true;
        }
    }
};

const checkUserLocation = async (webViewRef) => {
    const isLocationEnabled = await DeviceInfo.isLocationEnabled();
    if (isLocationEnabled) {
        webViewRef.current?.postMessage(JSON.stringify({
            type: "Location_Status",
            status: 'success',
            data: { isLocationOn: true }
        }));
    } else {
        webViewRef.current?.postMessage(JSON.stringify({
            type: "Location_Status",
            status: 'fail',
            data: { isLocationOn: false }
        }));
    }
};
