import axios from "axios";
import { PermissionsAndroid, Platform, DeviceEventEmitter, BackHandler, Alert, Linking } from "react-native";
import Geolocation from "@react-native-community/geolocation";
import DeviceInfo from "react-native-device-info";
import * as locationService from '../../Services/LocationServices';
import { getCurrentLocation } from "../../Services/commonFunctions";
import RNRestart from 'react-native-restart';


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
            PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS,
            PermissionsAndroid.PERMISSIONS.BLUETOOTH_SCAN,
            PermissionsAndroid.PERMISSIONS.BLUETOOTH_CONNECT,
            PermissionsAndroid.PERMISSIONS.BLUETOOTH_ADVERTISE,

        ]);

        const checkPermission = (perm) =>
            granted[perm] === PermissionsAndroid.RESULTS.GRANTED;

        if (
            !checkPermission(PermissionsAndroid.PERMISSIONS.CAMERA) ||
            !checkPermission(PermissionsAndroid.PERMISSIONS.READ_EXTERNAL_STORAGE) ||
            !checkPermission(PermissionsAndroid.PERMISSIONS.READ_MEDIA_IMAGES) ||
            !checkPermission(PermissionsAndroid.PERMISSIONS.ACCESS_MEDIA_LOCATION) ||
            !checkPermission(PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION) ||
            !checkPermission(PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS) ||
            !checkPermission(PermissionsAndroid.PERMISSIONS.BLUETOOTH_SCAN) ||
            !checkPermission(PermissionsAndroid.PERMISSIONS.BLUETOOTH_CONNECT) ||
            !checkPermission(PermissionsAndroid.PERMISSIONS.BLUETOOTH_ADVERTISE)
        ) {
            isPermission = false;
        }

        // ✅ Step 2: Background location check only on Android 10+ (API 29+)
        if (Platform.Version >= 29) {
            const alreadyGranted = await PermissionsAndroid.check(
                PermissionsAndroid.PERMISSIONS.ACCESS_BACKGROUND_LOCATION
            );

            if (alreadyGranted) {
                return isPermission;
            }

            // Step 3: Show guidance alert once
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

            // Step 4: Request background location in a loop
            let loop = true;
            while (loop) {
                const bgGranted = await PermissionsAndroid.request(
                    PermissionsAndroid.PERMISSIONS.ACCESS_BACKGROUND_LOCATION
                );

                if (bgGranted === PermissionsAndroid.RESULTS.GRANTED) {
                    loop = false;
                    return isPermission;
                } else if (bgGranted === PermissionsAndroid.RESULTS.DENIED) {
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
        }

        return isPermission;
    } catch (err) {
        console.warn('Permission error:', err);
        return false;
    }
};
export const startLocationTracking = async (locationRef, webViewRef) => {
    try {
        console.log('StartLocationTracking');
        const watchId = Geolocation.watchPosition(
            (position) => {
                const { latitude, longitude, accuracy } = position.coords;
                console.log('Location:', latitude, longitude, accuracy);
                if (accuracy != null && accuracy <= 20) {
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
        console.log(`Start location tracking... Watch ID: ${watchId}`);
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
export const readWebViewMessage = async (event, webViewRef, locationRef, isCameraActive, setShowCamera, setIsVisible, setBluetoothEvent, setBtConnectionRequest, setWebData, BackgroundTaskModule, blutoothRef, isPaymentProcess, AppResumeModule) => {
    let data = event?.nativeEvent?.data;
    try {
        let msg = JSON.parse(data);
        switch (msg?.type) {
            case 'startLocationTracking':
                startLocationTracking(locationRef, webViewRef);
                // checkBackgroundTaskStarted(BackgroundTaskModule, msg?.data?.userId, msg?.data?.dbPath, msg?.data?.travelPath);
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
                console.log('Starting background task...', msg.data);
                checkAppVersion(msg?.data?.version, webViewRef, BackgroundTaskModule, AppResumeModule);
                StartBackgroundTask(msg.data.locationAccuracy, msg.data.locationUpdateInterval, msg.data.locationUpdateDistance, msg.data.locationSendInterval, BackgroundTaskModule, msg.data.dbPath, msg.data.serverTimePath);

                break;
            case 'Logout':
                StopBackGroundTask(BackgroundTaskModule, AppResumeModule);
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
                getPaymentStatusFromApi(webViewRef, msg?.data?.url, msg?.data?.payloadData, msg?.data?.deviceType, msg?.data?.checkDelay, msg?.data?.serverTimeout, msg?.data?.maxAttempts, msg?.data?.orangeTimeout);
                break;
            case 'check-location':
                checkUserLocation(webViewRef);
                break;
            case 'check-version':
                checkAppVersion(msg?.data?.version, webViewRef, BackgroundTaskModule, AppResumeModule);
                break;
            case 'App_Active':
                checkAppVersion(msg?.data?.version, webViewRef, BackgroundTaskModule, AppResumeModule);
                handleBackGroundListners(msg, BackgroundTaskModule);
                break;
            case 'getCurrentLocation':
                getCurrentLocation(msg?.attempt, msg?.delay, webViewRef);
                break;
            case 'reload':
                reloadApplication(webViewRef);
                break;
            default:
                break;
        }
    } catch (error) {
        return;
    }
};
const handleBackGroundListners = async (msg, BackgroundTaskModule) => {
    console.log(msg)
    let { locationAccuracy, locationUpdateInterval, locationUpdateDistance, locationSendInterval, dbPath, serverTimePath } = msg?.data;
    if (locationAccuracy && locationUpdateInterval && locationUpdateDistance && locationSendInterval && serverTimePath && dbPath) {

        checkBackgroundTaskStarted(BackgroundTaskModule, locationAccuracy, locationUpdateInterval, locationUpdateDistance, locationSendInterval, dbPath, serverTimePath);
    }


};
export const checkAppVersion = async (version, webViewRef) => {
    console.log('version', version);
    if (version) {
        const currentVersion = await DeviceInfo.getVersion();
        const required = version?.toString()?.trim();
        if (required === currentVersion?.toString()?.trim()) {
            webViewRef.current?.postMessage(JSON.stringify({ type: "Version_Valid" }));
            return true;
        } else {
            // Step 1: Just inform JS, don’t close app yet
            webViewRef.current?.postMessage(JSON.stringify({ type: "Version_Expired" }));
            return false;
        }
    } else {
        webViewRef.current?.postMessage(JSON.stringify({ type: "Version_Expired" }));
        return false;
    }
};

const StartBackgroundTask = (locationAccuracy, locationUpdateInterval, locationUpdateDistance, locationSendInterval, BackgroundTaskModule, dbPath, serverTimePath) => {
    BackgroundTaskModule.startBackgroundTask({
        LOCATION_ACCURACY: locationAccuracy || "",
        LOCATION_UPDATE_INTERVAL: locationUpdateInterval || "",
        LOCATION_UPDATE_DISTANCE: locationUpdateDistance || "",
        LOCATION_SEND_INTERVAL: locationSendInterval || "",
        SERVER_TIME_PATH: serverTimePath || "",
        DB_PATH: dbPath || ""
    });
};


const StopBackGroundTask = (BackgroundTaskModule, AppResumeModule) => {
    BackgroundTaskModule.stopBackgroundTask();
    AppResumeModule?.stopLifecycleTracking?.();
};
export const startSavingTraversalHistory = async (history) => {
    let data = JSON.parse(history);
    locationService.saveLocationHistory(data.path, data.distance, data.time, data.userId, data.travelPath, data.dbPath);
};
const checkBackgroundTaskStarted = (BackgroundTaskModule, locationAccuracy, locationUpdateInterval, locationUpdateDistance, locationSendInterval, dbPath, serverTimePath) => {
    if (!locationAccuracy || !locationUpdateInterval || !locationUpdateDistance || !locationSendInterval || !dbPath || !serverTimePath) {
        console.warn("Location Accuracy, Update Interval, Update Distance or Send Interval is undefined, skipping background task check.");
        return;

    }
    BackgroundTaskModule.checkAndRestartBackgroundTask({
        LOCATION_ACCURACY: locationAccuracy || "",
        LOCATION_UPDATE_INTERVAL: locationUpdateInterval || "",
        LOCATION_UPDATE_DISTANCE: locationUpdateDistance || "",
        LOCATION_SEND_INTERVAL: locationSendInterval || "",
        SERVER_TIME_PATH: serverTimePath || "",
        DB_PATH: dbPath || ""
    });
    return;
};
export const listenAndroidMessages = (refContext, webViewRef, locationRef, isDialogVisible, setStatus) => {

    refContext.current.networkStatus = DeviceEventEmitter.addListener(
        'onConnectivityStatus',
        mobile => {
            sendNetWorkStatus(mobile, webViewRef, setStatus);
        }
    );

    refContext.current.locationStatus = DeviceEventEmitter.addListener(
        'onLocationStatus',
        location => {

            sendLocationStatus(location, webViewRef, locationRef, setStatus);
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
    refContext.current.serverTime = DeviceEventEmitter.addListener(
        'onServerTimeStatus',
        serverTime => {

            if (serverTime === 'true') {
                webViewRef?.current.postMessage(JSON.stringify({ type: "serverTime", data: { serverStatus: true } }));
            } else {
                webViewRef?.current.postMessage(JSON.stringify({ type: "serverTime", data: { serverStatus: false } }));
            }

        }
    );


    return () => {
        refContext?.current?.networkStatus?.remove();
        refContext?.current?.locationStatus?.remove();
        refContext?.current?.appStatus?.remove();
        refContext?.current?.serverTime?.remove();
    };
};
const sendNetWorkStatus = (mobile, webViewRef, setStatus) => {
    setStatus((prev) => ({ ...prev, networkStatus: !mobile?.isMobileDataOn }));
};
const sendLocationStatus = (location, webViewRef, locationRef, setStatus) => {
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
const getPaymentStatusFromApi = (webViewRef, url, payloadData, deviceType, checkDelay = 6000, serverTimeout = 120500, maxAttempts = 35, orangeTimeout = 110500) => {
    if (deviceType === 'pine') {
        checkPineTransactionStatus(webViewRef, url, payloadData, checkDelay, maxAttempts);
    } else {
        const startTime = Date.now();
        const control = { sent: false }; // ✅ this object is shared by reference

        const intervalId = setInterval(() => {
            const elapsedTime = Date.now() - startTime;

            if (elapsedTime < serverTimeout && !control.sent) {
                checkOrangeTransactionStatus(webViewRef, url, payloadData, elapsedTime, control, orangeTimeout);
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
        }, checkDelay);

        return () => clearInterval(intervalId);
    }
};
const checkPineTransactionStatus = (webViewRef, url, payloadData, checkDelay, maxAttempts) => {
    let attempt = 1;
    // const maxAttempts = 35;
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
    }, checkDelay);
};
const checkOrangeTransactionStatus = async (webViewRef, url, payloadData, elapsedTime, control, orangeTimeout) => {
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
            if (elapsedTime > orangeTimeout) {
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
export const handleTravelHistory = (type, data, webViewRef) => {

    if (type === 'avatar') {
        webViewRef?.current?.postMessage(JSON.stringify({ type: "Location", status: "success", data: { lat: data.latitude, lng: data.longitude } }));
    }
    if (type === 'history') {
        webViewRef?.current?.postMessage(JSON.stringify({ type: "travelHistory", data: { history: data.history || "", time: data.time || "", back_history: data?.back_history?.length > 0 ? data.back_history : [], type: data.type } }));
    }

};
export const handleSaveLockHistory = (data, webViewRef) => {
    webViewRef?.current?.postMessage(JSON.stringify({ type: "lockHistory", data: { lock_history: data.lock_history.length > 0 ? data.lock_history : [] } }));
};

const reloadApplication = (webViewRef, type) => {
    if (webViewRef.current && type === 'reloadWeb') {
        console.log('reload web view');
        webViewRef.current.reload();
    } else {
        console.log('reload whole application');
        RNRestart.restart();
    }
}