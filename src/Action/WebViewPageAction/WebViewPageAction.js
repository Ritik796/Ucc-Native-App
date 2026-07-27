import axios from "axios";
import { PermissionsAndroid, Platform, DeviceEventEmitter, BackHandler, Alert, Linking } from "react-native";
import Geolocation from "@react-native-community/geolocation";
import DeviceInfo from "react-native-device-info";
import * as locationService from '../../Services/LocationServices';
import { getCurrentLocation } from "../../Services/commonFunctions";
import RNRestart from 'react-native-restart';
import * as pipAction from '../Pip/pipAction';
import AsyncStorage from '@react-native-async-storage/async-storage';

// Background-location ("Allow all the time") ka guidance alert har launch par blocking na ho — ise
// sirf EK BAAR dikhate hain. Isके baad agar user ne "all the time" na diya ho to bhi chup-chaap ek
// silent request-attempt karke aage badh jaate hain (app block nahi hoti). Revoke/re-nudge foreground
// ke recheckLocationPermissions se handle hota hai (jo sirf tracking-desired par nudge karta hai).
const BG_LOC_PROMPTED_KEY = '@bg_location_prompted';

// User ne location tracking ON karwaya hai ya nahi. GPS off→on / app active / PiP me JS watch ko
// sahi se resume karne ke liye — taaki band hua watch chup-chaap dobara chaalu ho jaaye, aur logout
// ke baad galti se na chale.
let isTrackingDesired = false;

// Ek permission ko GRANTED hone tak ensure karta hai — jab tak grant na ho tab tak nudge karta hai.
// DENIED par "Retry", permanently-deny (NEVER_ASK_AGAIN) par "Open Settings" guidance. cancelable:false
// isliye worker skip nahi kar sakta — yahi "make sure sari permission on ho" ka core hai.
const ensurePermissionGranted = async (permission, title, message) => {
    try {
        if (await PermissionsAndroid.check(permission)) return true;
        while (true) {
            const result = await PermissionsAndroid.request(permission);
            if (result === PermissionsAndroid.RESULTS.GRANTED) return true;

            if (result === PermissionsAndroid.RESULTS.NEVER_ASK_AGAIN) {
                await new Promise((resolve) => {
                    Alert.alert(
                        title,
                        message + '\n\nPermission permanently deny ho gayi hai — Settings se manually ON karein.',
                        [{ text: 'Open Settings', onPress: async () => { await Linking.openSettings(); resolve(true); } }],
                        { cancelable: false }
                    );
                });
                // Settings se wapas aaye — agar ab grant hai to done, warna dobara guide.
                if (await PermissionsAndroid.check(permission)) return true;
            } else {
                // DENIED — Retry karwao, phir loop dobara request karega.
                await new Promise((resolve) => {
                    Alert.alert(
                        title,
                        message,
                        [{ text: 'Retry', onPress: () => resolve(true) }],
                        { cancelable: false }
                    );
                });
            }
        }
    } catch (e) {
        console.warn('ensurePermissionGranted error:', e);
        return false;
    }
};

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

        // ✅ Step 1.5: FINE_LOCATION critical hai — background location ("Allow all the time") isi ke
        // baad hi milti hai. Isliye pehle ise grant hone tak ensure karo, warna neeche wala background
        // step bekaar chala jaata tha (deny hone par bhi app aage badh jaati thi).
        await ensurePermissionGranted(
            PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION,
            'Location permission zaruri',
            'Is app ko location tracking ke liye location permission chahiye. Kripya "Allow" karein.'
        );

        // ✅ Step 1.6: Android 13+ par notification permission — foreground service ki notification
        // dikhane ke liye. Ye tracking rokti nahi (deny hone par bhi service chalti hai), isliye
        // best-effort ek baar maang lete hain, hard-block nahi.
        if (Platform.Version >= 33) {
            const notifGranted = await PermissionsAndroid.check(
                PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS
            );
            if (!notifGranted) {
                await PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS);
            }
        }

        // ✅ Step 2: Background location ("Allow all the time") — sirf Android 10+ (API 29+)
        if (Platform.Version >= 29) {
            const alreadyGranted = await PermissionsAndroid.check(
                PermissionsAndroid.PERMISSIONS.ACCESS_BACKGROUND_LOCATION
            );

            if (alreadyGranted) {
                // Grant ho gaya — flag reset, taaki aage kabhi revoke ho to phir se ek baar guide kar sakein.
                await AsyncStorage.removeItem(BG_LOC_PROMPTED_KEY).catch(() => { });
                return isPermission;
            }

            // Pehle hi guide kar chuke? To har launch blocking alert NAHI — chup-chaap ek silent request
            // try karo (system NEVER_ASK_AGAIN pe bina dialog ke return kar dega) aur aage badh jao.
            const alreadyPrompted = await AsyncStorage.getItem(BG_LOC_PROMPTED_KEY);
            if (alreadyPrompted === 'true') {
                const bgSilent = await PermissionsAndroid.request(
                    PermissionsAndroid.PERMISSIONS.ACCESS_BACKGROUND_LOCATION
                );
                if (bgSilent !== PermissionsAndroid.RESULTS.GRANTED) isPermission = false;
                return isPermission;
            }

            // Pehli baar — guidance ek baar dikhao, phir ek baar request. Koi infinite loop nahi.
            await new Promise((resolve) => {
                Alert.alert(
                    'Background Location Required',
                    'To continue, please allow background location access and select "Allow all the time" on the next screen.',
                    [{ text: 'Continue', onPress: () => resolve(true) }],
                    { cancelable: false }
                );
            });

            const bgGranted = await PermissionsAndroid.request(
                PermissionsAndroid.PERMISSIONS.ACCESS_BACKGROUND_LOCATION
            );
            // Flag set — chahe grant ho ya na ho, dobara har launch nag na ho.
            await AsyncStorage.setItem(BG_LOC_PROMPTED_KEY, 'true').catch(() => { });

            if (bgGranted !== PermissionsAndroid.RESULTS.GRANTED) isPermission = false;
            return isPermission;
        }

        return isPermission;
    } catch (err) {
        console.warn('Permission error:', err);
        return false;
    }
};

// App foreground aane par location permissions dobara check karo — agar shift ke beech revoke ho
// gayi (Android 11+ auto-reset / user ne manually band ki) to turant nudge karo. Sirf tab jab
// tracking desired ho (worker logged in), warna logout ke baad bhi permission force ho jaati.
export const recheckLocationPermissions = async () => {
    if (Platform.OS !== 'android' || !isTrackingDesired) return;
    try {
        const fineGranted = await PermissionsAndroid.check(
            PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION
        );
        if (!fineGranted) {
            console.log('[LocTrack] ⚠️ FINE_LOCATION revoke ho gayi — dobara nudge');
            await ensurePermissionGranted(
                PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION,
                'Location permission zaruri',
                'Location tracking ke liye location permission chahiye. Kripya "Allow" karein.'
            );
        }
        if (Platform.Version >= 29) {
            const bgGranted = await PermissionsAndroid.check(
                PermissionsAndroid.PERMISSIONS.ACCESS_BACKGROUND_LOCATION
            );
            if (!bgGranted) {
                console.log('[LocTrack] ⚠️ BACKGROUND_LOCATION revoke ho gayi — dobara nudge');
                await ensurePermissionGranted(
                    PermissionsAndroid.PERMISSIONS.ACCESS_BACKGROUND_LOCATION,
                    'Background location zaruri',
                    'Screen off / background me tracking ke liye location ko "Allow all the time" par set karein.'
                );
            }
        }
    } catch (e) {
        console.warn('recheckLocationPermissions error:', e);
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
// JS watch ko sirf tab resume karo jab tracking desired ho aur abhi chal nahi raha ho.
// (GPS off→on, app active, PiP — sab jagah safe resume ke liye.)
export const resumeTrackingIfDesired = (locationRef, webViewRef) => {
    if (isTrackingDesired && locationRef?.current == null) {
        startLocationTracking(locationRef, webViewRef);
    }
};
export const readWebViewMessage = async (event, webViewRef, locationRef, isCameraActive, setShowCamera, setIsVisible, setBluetoothEvent, setBtConnectionRequest, setWebData, BackgroundTaskModule, blutoothRef, isPaymentProcess, AppResumeModule) => {
    let data = event?.nativeEvent?.data;
    try {
        let msg = JSON.parse(data);
        switch (msg?.type) {
            case 'startLocationTracking':
                console.log('[LocTrack] ▶️ START: web app ne startLocationTracking bheja — JS watch on');
                isTrackingDesired = true;
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
                console.log('[LocTrack] ▶️ START: web app ne background location service start kiya —', JSON.stringify({ acc: msg?.data?.locationAccuracy, interval: msg?.data?.locationUpdateInterval, dist: msg?.data?.locationUpdateDistance, dbPath: msg?.data?.dbPath ? 'ok' : 'EMPTY!' }));
                checkAppVersion(msg?.data?.version, webViewRef, BackgroundTaskModule, AppResumeModule);
                StartBackgroundTask(msg.data.locationAccuracy, msg.data.locationUpdateInterval, msg.data.locationUpdateDistance, msg.data.locationSendInterval, BackgroundTaskModule, msg.data.dbPath, msg.data.serverTimePath, msg.data.lockHistoryPath, msg.data.travelPath);

                break;
            case 'Logout':
                console.log('[LocTrack] ⛔ STOP: LOGOUT — background service + JS watch dono band');
                isTrackingDesired = false;
                StopBackGroundTask(BackgroundTaskModule, AppResumeModule);
                stopTracking(locationRef);
                break;
            case 'Exit_App':
                handleExitApp();
                break;
            case 'message':
                console.log(msg.type, msg.data);
                break;
            case 'payment':
                isPaymentProcess.current = true;
                if (msg?.data?.deviceType === 'sbi') {
                    sendPaymentRequestToSbi(msg?.data?.paymentData, msg?.data?.url, webViewRef, msg?.data?.tokenData)
                }
                else {
                    sendPaymentRequestToUrl(msg?.data?.paymentData, msg?.data?.url, msg?.data?.deviceType, webViewRef);
                }
                break;
            case 'Payment_Process_Done':
                setTimeout(() => {
                    isPaymentProcess.current = false;
                }, 2000);
                break;
            case 'paymentStatus':
                getPaymentStatusFromApi(webViewRef, msg?.data?.url, msg?.data?.payloadData, msg?.data?.deviceType, msg?.data?.checkDelay, msg?.data?.serverTimeout, msg?.data?.maxAttempts, msg?.data?.orangeTimeout, msg?.data?.tokenData);
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
                reloadApplication(webViewRef, msg?.data);
                break;
            default:
                break;
        }
    } catch (error) {
        return;
    }
};
const handleBackGroundListners = async (msg, BackgroundTaskModule) => {
    let { locationAccuracy, locationUpdateInterval, locationUpdateDistance, locationSendInterval, dbPath, serverTimePath, lockHistoryPath, travelPath } = msg?.data;
    if (locationAccuracy && locationUpdateInterval && locationUpdateDistance && locationSendInterval && serverTimePath && dbPath) {

        checkBackgroundTaskStarted(BackgroundTaskModule, locationAccuracy, locationUpdateInterval, locationUpdateDistance, locationSendInterval, dbPath, serverTimePath, lockHistoryPath, travelPath);
    }


};
export const checkAppVersion = async (version, webViewRef) => {
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

const StartBackgroundTask = (locationAccuracy, locationUpdateInterval, locationUpdateDistance, locationSendInterval, BackgroundTaskModule, dbPath, serverTimePath, lockHistoryPath, travelPath) => {
    BackgroundTaskModule.startBackgroundTask({
        LOCATION_ACCURACY: locationAccuracy || "",
        LOCATION_UPDATE_INTERVAL: locationUpdateInterval || "",
        LOCATION_UPDATE_DISTANCE: locationUpdateDistance || "",
        LOCATION_SEND_INTERVAL: locationSendInterval || "",
        SERVER_TIME_PATH: serverTimePath || "",
        DB_PATH: dbPath || "",
        LOCK_HISTORY_PATH: lockHistoryPath || "",
        TRAVEL_PATH: travelPath || ""
    });
    // Tracking shuru hone par hi PiP permission guide karo (ek baar) — yahi wo moment hai jab
    // floating window matter karta hai. Startup par alert-stacking se bachne ke liye yahin rakha hai.
    pipAction.ensurePipPermission();
};


const StopBackGroundTask = (BackgroundTaskModule, AppResumeModule) => {
    BackgroundTaskModule.stopBackgroundTask();
    AppResumeModule?.stopLifecycleTracking?.();
};
export const startSavingTraversalHistory = async (history) => {
    try {
        let data = JSON.parse(history);
        locationService.saveLocationHistory(data.path, data.distance, data.time, data.userId, data.travelPath, data.dbPath);
    } catch (e) {
        console.log('startSavingTraversalHistory error:', e);
    }
};
const checkBackgroundTaskStarted = (BackgroundTaskModule, locationAccuracy, locationUpdateInterval, locationUpdateDistance, locationSendInterval, dbPath, serverTimePath, lockHistoryPath, travelPath) => {
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
        DB_PATH: dbPath || "",
        LOCK_HISTORY_PATH: lockHistoryPath || "",
        TRAVEL_PATH: travelPath || ""
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
        console.log('[LocTrack] ⛔ BREAK: GPS/Location device pe OFF ho gaya — JS watch stopped');
        stopTracking(locationRef);
    }
    // GPS wapas ON: stopTracking ne current=null kar diya hota hai, isliye '== null' check sahi hai
    // (pehle yahan '!== null' tha jis se watch kabhi resume hi nahi hota tha).
    else if (location?.isLocationOn === true && isTrackingDesired && locationRef?.current == null) {
        console.log('[LocTrack] ▶️ RESUME: GPS/Location wapas ON — JS watch dobara start');
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
            let paymentType = 'payment-success';
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
            else if (deviceType === 'paytm') {
                const head = response?.data?.head || {};
                const body = response?.data?.body || {};
                let bodyContent = { ...body };
                delete bodyContent?.resultInfo;
                if (body?.resultInfo?.resultCode === 'A') {
                    responseData = {
                        ...head, ...bodyContent,
                        ResponseCode: 0,
                        ResponseMessage: 'APPROVED',
                        ResponseStatus: body?.resultInfo?.resultStatus,
                        ResponseCodeId: body?.resultInfo?.resultCodeId,
                        deviceType: deviceType
                    };
                }
                else {
                    paymentType = 'payment-error'
                    responseData = {
                        ...head, ...bodyContent,
                        ResponseCode: body?.resultInfo?.resultCode,
                        ResponseMessage: body?.resultInfo?.resultMsg || '',
                        ResponseStatus: body?.resultInfo?.resultStatus,
                        ResponseCodeId: body?.resultInfo?.resultCodeId,
                        deviceType: deviceType
                    };
                }
            }
            injectedJS = `
            window.dispatchEvent(new MessageEvent('message', {
                data: JSON.stringify({
                    type: '${paymentType}',
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
const getPaymentStatusFromApi = (webViewRef, url, payloadData, deviceType, checkDelay = 6000, serverTimeout = 120500, maxAttempts = 35, orangeTimeout = 110500, tokenData) => {
    if (deviceType === 'pine') {
        checkPineTransactionStatus(webViewRef, url, payloadData, checkDelay, maxAttempts);
    } else if (deviceType === 'paytm') {
        checkPaytmTransactionStatus(webViewRef, url, payloadData, checkDelay, maxAttempts);
    } else if (deviceType === 'sbi') {
        checkSbiTransactionStatus(webViewRef, url, payloadData, checkDelay, maxAttempts, tokenData);
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
        webViewRef?.current?.postMessage(JSON.stringify({ type: "Location", status: "success", data: { lat: data.latitude, lng: data.longitude, acc: data.Accuracy } }));
    }
    if (type === 'history') {
        webViewRef?.current?.postMessage(JSON.stringify({ type: "travelHistory", data: { history: data.history || "", time: data.time || "", back_history: data?.back_history?.length > 0 ? data.back_history : [], type: data.type } }));
    }

};
export const handleSaveLockHistory = (data, webViewRef) => {
    // Optional-chaining: agar data me lock_history na ho to pehle crash (undefined.length) hota tha.
    const lockHistory = data?.lock_history?.length > 0 ? data.lock_history : [];
    webViewRef?.current?.postMessage(JSON.stringify({ type: "lockHistory", data: { lock_history: lockHistory } }));
};

const reloadApplication = (webViewRef, type) => {
    if (webViewRef.current && type === 'reloadWeb') {
        console.log('reload web view');
        webViewRef.current.reload();
    } else {
        console.log('reload whole application');
        RNRestart.restart();
    }
};
const checkPaytmTransactionStatus = (webViewRef, url, payloadData, checkDelay, maxAttempts) => {
    let attempt = 1;
    const interval = setInterval(async () => {
        try {
            const response = await axios.post(url, payloadData, {
                headers: { 'Content-Type': 'application/json' },
                timeout: 30000 // 30 second timeout
            });

            const head = response?.data?.head || {};
            const body = response?.data?.body || {};
            let bodyContent = { ...body };
            delete bodyContent?.resultInfo;

            const resultCode = body?.resultInfo?.resultCode;
            const resultStatus = body?.resultInfo?.resultStatus;
            const resultMsg = body?.resultInfo?.resultMsg;
            const resultCodeId = body?.resultInfo?.resultCodeId;

            const responseData = {
                ...head,
                ...bodyContent,
                ResponseCode: resultCode,
                ResponseMessage: resultMsg || '',
                ResponseStatus: resultStatus,
                ResponseCodeId: resultCodeId,
                deviceType: 'paytm'
            };

            // Handle Success cases
            if (resultCodeId === '0000' || resultCodeId === '0009') {
                responseData['ResponseCode'] = 0;
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
            }
            // Handle Pending cases
            else if (resultCodeId === '0010' || resultCodeId === '0030') {
                // Continue polling - do nothing here
                console.log(`Transaction pending. Status: ${resultStatus}, Message: ${resultMsg}`);
            }
            // Handle Failure cases
            else if (['0012', '0330', '0404', '0011', '0090', '0180'].includes(resultCodeId)) {
                const failJS = `
                    window.dispatchEvent(new MessageEvent('message', {
                        data: JSON.stringify({
                            type: 'paymentStatus-error',
                            message: ${JSON.stringify(resultMsg)}
                        })
                    }));
                `;
                webViewRef.current?.injectJavaScript(failJS);
                clearInterval(interval);
                return;
            }

        } catch (error) {
            const errorMessage = error.code === 'ECONNABORTED'
                ? 'Request timeout'
                : error.message === 'Network Error'
                    ? 'Network connection error'
                    : error.message;

            const errorJS = `
                window.dispatchEvent(new MessageEvent('message', {
                    data: JSON.stringify({
                        type: 'paymentStatus-catch-error',
                        message: ${JSON.stringify(errorMessage)}
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

    // Return cleanup function
    return () => {
        if (interval) {
            clearInterval(interval);
        }
    };
};
const sendPaymentRequestToSbi = async (paymentPayload, url, webViewRef, tokenData) => {
    try {
        const tokenResponse = await axios.post(tokenData?.url, tokenData?.data, { headers: { 'Content-Type': 'application/json' }, timeout: 30000 });
        let injectedJS;
        let responseData;
        if (tokenResponse?.status === 200 && tokenResponse?.data?.ResponseCode === '000' && tokenResponse?.data?.APIToken) {
            const token = tokenResponse?.data?.APIToken;
            paymentPayload = { ...paymentPayload, "APIToken": token }
            const paymentResp = await axios.post(url, paymentPayload, { headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${token}` }, timeout: 30000 });
            if (paymentResp?.status === 200 && paymentResp?.data?.ResponseCode === "000") {
                responseData = { ...paymentResp?.data, ResponseCode: 0, ResponseMessage: 'APPROVED' };
                injectedJS = `window.dispatchEvent(new MessageEvent('message', {data: JSON.stringify({ type: 'payment-success',status: 'success',data: ${JSON.stringify(responseData)}})}));`;
            } else {
                responseData = {
                    ...paymentResp?.data,
                    ResponseCode: Number(paymentResp?.data?.ResponseCode),
                    ResponseMessage: paymentResp?.data?.ResponseDescription
                };
                injectedJS = `window.dispatchEvent(new MessageEvent('message', {data: JSON.stringify({type: 'payment-error',status: 'fail',data: ${JSON.stringify(responseData)}})}));`;
            }
        }
        else {
            responseData = {
                ...tokenResponse?.data,
                ResponseCode: Number(tokenResponse?.data?.ResponseCode),
                ResponseMessage: tokenResponse?.data?.ResponseDescription
            };
            injectedJS = `window.dispatchEvent(new MessageEvent('message', {data: JSON.stringify({type: 'payment-error',status: 'fail',data: ${JSON.stringify(responseData)}})}));`;
        }
        webViewRef.current?.injectJavaScript(injectedJS);

    } catch (error) {
        console.error(error)
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
const checkSbiTransactionStatus = async (webViewRef, url, payloadData, checkDelay, maxAttempts, tokenData) => {
    let attempt = 1;
    let token = await generateSbiApiToken(tokenData);
    const interval = setInterval(async () => {
        try {
            payloadData = { ...payloadData, "APIToken": token }
            const statusResp = await axios.post(url, payloadData, { headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${token}` }, timeout: 30000 });

            const responseData = { ...statusResp?.data };
            const { AuthCode, RRN, TxnStatus } = responseData;
            // Handle Success cases1
            if (Number(AuthCode) && Number(RRN) !== 0 && (TxnStatus?.includes('Successful') || TxnStatus?.includes('Approved'))) {
                responseData['ResponseCode'] = 0;
                const successJS = `window.dispatchEvent(new MessageEvent('message', {data: JSON.stringify({type: 'paymentStatus-success',data: ${JSON.stringify(responseData)}})}));`;

                webViewRef.current?.injectJavaScript(successJS);
                clearInterval(interval);
                return;
            }
        } catch (error) {
            console.error('status error', error?.status)
            console.error('status error', error)
            if (error?.status === 401) {
                token = await generateSbiApiToken(tokenData);
            }
            else {
                const errorMessage = error.code === 'ECONNABORTED' ? 'Request timeout' : error.message === 'Network Error' ? 'Network connection error' : error.message;
                const errorJS = `window.dispatchEvent(new MessageEvent('message', {data: JSON.stringify({type: 'paymentStatus-catch-error',message: ${JSON.stringify(errorMessage)}})}));`;
                webViewRef.current?.injectJavaScript(errorJS);
                clearInterval(interval);
                return;
            }
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

    // Return cleanup function
    return () => {
        if (interval) {
            clearInterval(interval);
        }
    };
};
const generateSbiApiToken = async (tokenData) => {
    try {
        const tokenResponse = await axios.post(tokenData?.url, tokenData?.data, { headers: { 'Content-Type': 'application/json' }, timeout: 30000 });
        if (tokenResponse?.status === 200 && tokenResponse?.data?.ResponseCode === '000' && tokenResponse?.data?.APIToken) {
            return tokenResponse?.data?.APIToken;
        }
        else {
            return '';
        }
    } catch (err) {
        return '';
    }
}