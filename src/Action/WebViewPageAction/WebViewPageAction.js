import { PermissionsAndroid, Platform } from "react-native";
import Geolocation from "@react-native-community/geolocation";
import DeviceInfo from "react-native-device-info";
 
export const requestLocationPermission = async () => {
    try {
        if (Platform.OS !== "android") return true; // iOS handled differently
 
        let isPermission = true;
        const granted = await PermissionsAndroid.requestMultiple([
            PermissionsAndroid.PERMISSIONS.CAMERA,
            PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION,
            PermissionsAndroid.PERMISSIONS.READ_EXTERNAL_STORAGE,
            PermissionsAndroid.PERMISSIONS.READ_MEDIA_IMAGES,
            PermissionsAndroid.PERMISSIONS.ACCESS_MEDIA_LOCATION,
        ]);
 
        if (
            granted[PermissionsAndroid.PERMISSIONS.CAMERA] !==
            PermissionsAndroid.RESULTS.GRANTED
        ) {
            console.log("Camera permission denied");
            isPermission = false;
        }
 
        if (
            granted[PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION] !==
            PermissionsAndroid.RESULTS.GRANTED
        ) {
            console.log("Location permission denied");
            isPermission = false;
        }
        if (
            granted[PermissionsAndroid.PERMISSIONS.READ_EXTERNAL_STORAGE] !==
            PermissionsAndroid.RESULTS.GRANTED
        ) {
            console.log("READ_EXTERNAL_STORAGE permission denied");
            isPermission = false;
        }
        if (
            granted[PermissionsAndroid.PERMISSIONS.READ_MEDIA_IMAGES] !==
            PermissionsAndroid.RESULTS.GRANTED
        ) {
            console.log("READ_MEDIA_IMAGES permission denied");
            isPermission = false;
        }
        if (
            granted[PermissionsAndroid.PERMISSIONS.ACCESS_MEDIA_LOCATION] !==
            PermissionsAndroid.RESULTS.GRANTED
        ) {
            console.log("ACCESS_MEDIA_LOCATION permission denied");
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
        console.log('StartLocationTracking')
        const watchId = Geolocation.watchPosition(
            (position) => {
                const { latitude, longitude, accuracy } = position.coords;
                console.log("watchPosition callback:", latitude, longitude, accuracy);
 
                if (accuracy != null && accuracy <= 15) {
                    webViewRef?.current?.postMessage(JSON.stringify({ status: "success", data: { lat: latitude, lng: longitude } }));
                } else {
                    console.log("Skipped low-accuracy position:", accuracy);
                }
            },
            (error) => {
                webViewRef?.current?.postMessage(JSON.stringify({ status: "fail", data: null }));
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
            console.log("Clearing watchPosition with id:", locationRef.current);
            Geolocation.clearWatch(locationRef.current);
            locationRef.current = null;
        }
    }
};
 
export const stopLocationTracking = (locationRef, setWebData) => {
    if (locationRef?.current) {
        console.log("Location tracking stopped.", locationRef.current);
        Geolocation.clearWatch(locationRef.current);
        locationRef.current = null;
 
        if (setWebData) {
            setWebData((prev) => ({ ...prev, userId: "", city: "" }));
        }
    }
};
 
export const stopTracking = async (locationRef) => {
    if (locationRef?.current) {
        console.log("Location tracking stopped.", locationRef.current);
        await Geolocation.clearWatch(locationRef.current);
        locationRef.current = null;
    }
};
 
export const readWebViewMessage = async(event, webViewRef, locationRef,isCameraActive,setShowCamera,setIsVisible) => {
    let data = event?.nativeEvent?.data;
    try {
        let msg = JSON.parse(data);
        console.log('msg', msg);
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
                    webViewRef.current?.postMessage(JSON.stringify({ type: "Location_Disabled" }));
                    isCameraActive.current = false;
                }
                break;
            case 'location':
 
                break;
            default:
                break;
        }
    } catch (error) {
        return;
    }
};
 