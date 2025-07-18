import axios from "axios";
import { PermissionsAndroid, Platform, DeviceEventEmitter } from "react-native";
import Geolocation from "@react-native-community/geolocation";
import DeviceInfo from "react-native-device-info";
import * as locationService from '../../Services/LocationServices';
import AsyncStorage from "@react-native-async-storage/async-storage";

export const requestLocationPermission = async () => {
    try {
        if (Platform.OS !== "android") return true; // iOS handled differently

        let isPermission = true;

        // Step 1: Request all permissions EXCEPT background location
        const granted = await PermissionsAndroid.requestMultiple([
            PermissionsAndroid.PERMISSIONS.CAMERA,
            PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION,
            PermissionsAndroid.PERMISSIONS.READ_EXTERNAL_STORAGE,
            PermissionsAndroid.PERMISSIONS.READ_MEDIA_IMAGES,
            PermissionsAndroid.PERMISSIONS.ACCESS_MEDIA_LOCATION,
            PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS
        ]);

        if (
            granted[PermissionsAndroid.PERMISSIONS.CAMERA] !==
            PermissionsAndroid.RESULTS.GRANTED
        ) {
            isPermission = false;
        }

        if (
            granted[PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION] !==
            PermissionsAndroid.RESULTS.GRANTED
        ) {
            isPermission = false;
        }
        if (
            granted[PermissionsAndroid.PERMISSIONS.READ_EXTERNAL_STORAGE] !==
            PermissionsAndroid.RESULTS.GRANTED
        ) {
            isPermission = false;
        }
        if (
            granted[PermissionsAndroid.PERMISSIONS.READ_MEDIA_IMAGES] !==
            PermissionsAndroid.RESULTS.GRANTED
        ) {
            isPermission = false;
        }
        if (
            granted[PermissionsAndroid.PERMISSIONS.ACCESS_MEDIA_LOCATION] !==
            PermissionsAndroid.RESULTS.GRANTED
        ) {
            isPermission = false;
        }
        if (
            granted[PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS] !==
            PermissionsAndroid.RESULTS.GRANTED
        ) {
            isPermission = false;
        }

        // Step 2: Request background location permission separately
        const bgGranted = await PermissionsAndroid.request(
            PermissionsAndroid.PERMISSIONS.ACCESS_BACKGROUND_LOCATION
        );

        if (bgGranted !== PermissionsAndroid.RESULTS.GRANTED) {
            isPermission = false;
        }

        return isPermission;
    } catch (err) {
        console.warn("Permission error:", err);
        return false;
    }
};

export const startLocationTracking = async (locationRef, webViewRef) => {
    try {
        // console.log('StartLocationTracking')
        const watchId = Geolocation.watchPosition(
            (position) => {
                const { latitude, longitude, accuracy } = position.coords;

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
                useSignificantChanges: false,
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
        await Geolocation.clearWatch(locationRef.current);
        locationRef.current = null;
    }
};

export const readWebViewMessage = async (event, webViewRef, locationRef, isCameraActive, setShowCamera, setIsVisible, setBluetoothEvent, setBtConnectionRequest, setWebData, BackgroundTaskModule, blutoothRef) => {
    let data = event?.nativeEvent?.data;
    try {
        let msg = JSON.parse(data);
        switch (msg?.type) {
            case 'startLocationTracking':
                startLocationTracking(locationRef, webViewRef);

                break;
            case 'openCamera':
                const isLocationEnabled = await DeviceInfo.isLocationEnabled();
                if (isLocationEnabled) {
                    isCameraActive.current = true;
                    setShowCamera(true);
                    setIsVisible(true);
                } else {
                    webViewRef.current?.postMessage(JSON.stringify({ type: "Location_Status",data:{isLocationOn:false} }));
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
                setWebData(pre => ({ ...pre, userId: msg?.data?.userId || "", dbPath: msg?.data?.dbPath || "" }));
                StartBackgroundTask(msg.data.userId, msg.data.dbPath, BackgroundTaskModule);
                await AsyncStorage.setItem('userId', msg?.data?.userId || "");
                await AsyncStorage.setItem('dbPath', msg?.data?.dbPath || "");
                break;
            case 'Logout':
                StopBackGroundTask(BackgroundTaskModule);
                break;
            case 'message':
                // console.log(msg.type, msg.data)
                break;
            case 'payment':
                sendPaymentRequestToUrl(msg?.data?.paymentData, msg?.data?.url, webViewRef);
                break;
            case 'paymentStatus':
                getPaymentStatusFromApi(webViewRef, msg?.data?.url, msg?.data?.payloadData);
                break;
            default:
                break;
        }
    } catch (error) {
        return;
    }
};
const StartBackgroundTask = (userId, dbPath, BackgroundTaskModule) => {
    BackgroundTaskModule.startBackgroundTask({
        USER_ID: userId || "",
        DB_PATH: dbPath || "",
    });
};
const StopBackGroundTask = (BackgroundTaskModule) => {
    BackgroundTaskModule.stopBackgroundTask();
};
export const startSavingTraversalHistory = async (history) => {
    let data = JSON.parse(history);
    locationService.saveLocationHistory(data.path, data.distance, data.time, data.userId, data.dbPath);
};


const sendPaymentRequestToUrl = async (paymentPayload, url, webViewRef) => {
    try {
        const response = await axios.post(url, paymentPayload, {
            headers: { 'Content-Type': 'application/json' }
        });
        let injectedJS;
        if (response.status === 200 && response.data) {
            injectedJS = `
            window.dispatchEvent(new MessageEvent('message', {
                data: JSON.stringify({
                    type: 'payment-success',
                    status: 'success',
                    data: ${JSON.stringify(response.data)}
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

export const listenAndroidMessages = (refContext, webViewRef,BackgroundTaskModule,locationRef) => {
    refContext.current.traversalUpdate = DeviceEventEmitter.addListener(
        'onTraversalUpdate',
        history => {
            // startSavingTraversalHistory(history);
        }
    );

    refContext.current.networkStatus = DeviceEventEmitter.addListener(
        'onConnectivityStatus',
        mobile => {
           
            sendNetWorkStatus(mobile, webViewRef);
        }
    );

    refContext.current.locationStatus = DeviceEventEmitter.addListener(
        'onLocationStatus',
        location => {

            sendLocationStatus(location, webViewRef,BackgroundTaskModule,locationRef);
        }
    );

    return () => {
        refContext?.current?.traversalUpdate?.remove();
        refContext?.current?.networkStatus?.remove();
        refContext?.current?.locationStatus?.remove();
    };
};



const sendNetWorkStatus = (mobile, webViewRef) => {
    webViewRef?.current?.postMessage(JSON.stringify({ type: "Mobile_Data", data: mobile }));
};
const sendLocationStatus = async (location, webViewRef,BackgroundTaskModule,locationRef) => {
    webViewRef?.current?.postMessage(JSON.stringify({ type: "Location_Status", data: location }));
    let data = await Promise.all([
        AsyncStorage.getItem('userId'),
        AsyncStorage.getItem('dbPath'),
    ]);
    let [userId, dbPath] = data;
    if (userId && dbPath && location?.isLocationOn === false) {
        StopBackGroundTask(BackgroundTaskModule);
        stopTracking(locationRef)
    }
    else if (userId && dbPath && location?.isLocationOn === true) {
        StartBackgroundTask(userId,dbPath,BackgroundTaskModule);
        startLocationTracking(locationRef,webViewRef)
    }
};

// const sendPaymentRequestToUrl = async (paymentPayload, url, webViewRef) => {
//     try {
//         const response = await axios.post(url, paymentPayload, {
//             headers: { 'Content-Type': 'application/json' }
//         });
//         let injectedJS;
//         if (response.status === 200 && response.data) {
//             injectedJS = `
//             window.dispatchEvent(new MessageEvent('message', {
//                 data: JSON.stringify({
//                     type: 'payment-success',
//                     status: 'success',
//                     data: ${JSON.stringify(response.data)}
//                 })
//             }));
//         `;
//         } else {
//             injectedJS = `
//             window.dispatchEvent(new MessageEvent('message', {
//                 data: JSON.stringify({
//                     type: 'payment-error',
//                     status: 'fail',
//                     data: ${JSON.stringify(response.data)}
//                 })
//             }));
//         `;
//         }
//         webViewRef.current?.injectJavaScript(injectedJS);

//     } catch (error) {
//         const errorJS = `
//             window.dispatchEvent(new MessageEvent('message', {
//                 data: JSON.stringify({
//                     type: 'payment-catch-error',
//                     error: ${JSON.stringify(error.message)}
//                 })
//             }));
//         `;
//         webViewRef.current?.injectJavaScript(errorJS);
//     }
// };

const getPaymentStatusFromApi = (webViewRef, url, payloadData) => {
    let attempt = 1;
    const interval = setInterval(async () => {
        try {
            const response = await axios.post(url, payloadData, {
                headers: { 'Content-Type': 'application/json' }
            });
            // console.log('Payment Status Response:', response?.data);

            const { ResponseCode, ResponseMessage } = response.data;
            const code = Number(ResponseCode);

            if (code === 0) {
                // console.log('✅ Payment Success');
                const successJS = `
                window.dispatchEvent(new MessageEvent('message', {
                    data: JSON.stringify({
                        type: 'paymentStatus-success',
                        data: ${JSON.stringify(response.data)}
                    })
                }));
            `;
                webViewRef.current?.injectJavaScript(successJS);
                clearInterval(interval);
            } else if (code === 1 || code === 1052) {
                // console.log('❌ Payment Failed');
                const failJS = `
                window.dispatchEvent(new MessageEvent('message', {
                    data: JSON.stringify({
                        type: 'paymentStatus-error',
                        message: ${JSON.stringify(response.data)}
                    })
                }));
            `;
                webViewRef.current?.injectJavaScript(failJS);
                clearInterval(interval);
            } else {
                // console.log(`⚠️ Retry after 6 sec... ResponseCode=${code}`);
            }
        } catch (error) {
            // console.log('❌ Error fetching payment status:', error.message);
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
        }
        attempt++;
    }, 6000);
};

// const getPaymentStatusFromApi = (webViewRef, url, payloadData) => {
//     let attempt = 1;
//     const interval = setInterval(async () => {
//         console.log(`🔁 Payment Status Check: Attempt ${attempt}`);
//         try {
//             const response = await axios.post(url, payloadData, {
//                 headers: { 'Content-Type': 'application/json' }
//             });
//             console.log('Payment Status Response:', response?.data);

//             const { ResponseCode, ResponseMessage } = response.data;
//             const code = Number(ResponseCode);

//             if (code === 0) {
//                 console.log('✅ Payment Success');
//                 const successJS = `
//                 window.dispatchEvent(new MessageEvent('message', {
//                     data: JSON.stringify({
//                         type: 'paymentStatus-success',
//                         data: ${JSON.stringify(response.data)}
//                     })
//                 }));
//             `;
//                 webViewRef.current?.injectJavaScript(successJS);
//                 clearInterval(interval);
//             } else if (code === 1 || code === 1052) {
//                 console.log('❌ Payment Failed');
//                 const failJS = `
//                 window.dispatchEvent(new MessageEvent('message', {
//                     data: JSON.stringify({
//                         type: 'paymentStatus-error',
//                         message: ${JSON.stringify(response.data)}
//                     })
//                 }));
//             `;
//                 webViewRef.current?.injectJavaScript(failJS);
//                 clearInterval(interval);
//             } else {
//                 console.log(`⚠️ Retry after 6 sec... ResponseCode=${code}`);
//             }
//         } catch (error) {
//             console.log('❌ Error fetching payment status:', error.message);
//             const errorJS = `
//                 window.dispatchEvent(new MessageEvent('message', {
//                     data: JSON.stringify({
//                         type: 'paymentStatus-catch-error',
//                         message: ${JSON.stringify(error.message)}
//                     })
//                 }));
//             `;
//             webViewRef.current?.injectJavaScript(errorJS);
//             clearInterval(interval);
//         }
//         attempt++;
//     }, 6000);
// };


// const sendPaymentRequestToUrl = async (paymentPayload, url, webViewRef) => {
//     try {
//         const response = await axios.post(url, paymentPayload, {
//             headers: { 'Content-Type': 'application/json' }
//         });
//         console.log('response:', response?.data);
//         let injectedJS;
//         if (response.status === 200 && response.data) {
//             injectedJS = `
//             window.dispatchEvent(new MessageEvent('message', {
//                 data: JSON.stringify({
//                     type: 'payment-success',
//                     status: 'success',
//                     data: ${JSON.stringify(response.data)}
//                 })
//             }));
//         `;
//         } else {
//             injectedJS = `
//             window.dispatchEvent(new MessageEvent('message', {
//                 data: JSON.stringify({
//                     type: 'payment-error',
//                     status: 'fail',
//                     data: ${JSON.stringify(response.data)}
//                 })
//             }));
//         `;
//         }
//         webViewRef.current?.injectJavaScript(injectedJS);

//     } catch (error) {
//         console.log('Payment failed:', error.message);
//         const errorJS = `
//             window.dispatchEvent(new MessageEvent('message', {
//                 data: JSON.stringify({
//                     type: 'payment-error',
//                     error: ${JSON.stringify(error.message)}
//                 })
//             }));
//         `;
//         webViewRef.current?.injectJavaScript(errorJS);
//     }
// };


// const getPaymentStatusFromApi = (webViewRef, url, payloadData) => {
//     let attempt = 1;
//     const interval = setInterval(async () => {
//         console.log(`🔁 Payment Status Check: Attempt ${attempt}`);
//         try {
//             const response = await axios.post(url, payloadData, {
//                 headers: { 'Content-Type': 'application/json' }
//             });
//             console.log('Payment Status Response:', response?.data);

//             const { ResponseCode, ResponseMessage } = response.data;
//             const code = Number(ResponseCode);

//             if (code === 0) {
//                 console.log('✅ Payment Success');
//                 const successJS = `
//                     window.dispatchEvent(new MessageEvent('message', {
//                         data: JSON.stringify({
//                             type: 'paymentStatus-success',
//                             orderId: '${orderId}',
//                             amount: '${amount}',
//                             data: ${JSON.stringify(response.data)}
//                         })
//                     }));
//                 `;
//                 webViewRef.current?.injectJavaScript(successJS);
//                 clearInterval(interval);
//             } else if (code === 1 || code === 1052) {
//                 console.log('❌ Payment Failed');
//                 const failJS = `
//                     window.dispatchEvent(new MessageEvent('message', {
//                         data: JSON.stringify({
//                             type: 'paymentStatus-error',
//                             message: '${ResponseMessage}'
//                         })
//                     }));
//                 `;
//                 webViewRef.current?.injectJavaScript(failJS);
//                 clearInterval(interval);
//             } else {
//                 console.log(`⚠️ Retry after 6 sec... ResponseCode=${code}`);
//             }
//         } catch (error) {
//             console.log('❌ Error fetching payment status:', error.message);
//             const errorJS = `
//                 window.dispatchEvent(new MessageEvent('message', {
//                     data: JSON.stringify({
//                         type: 'paymentStatus-error',
//                         message: ${JSON.stringify(error.message)}
//                     })
//                 }));
//             `;
//             webViewRef.current?.injectJavaScript(errorJS);
//             clearInterval(interval);
//         }
//         attempt++;
//     }, 6000);
// };
