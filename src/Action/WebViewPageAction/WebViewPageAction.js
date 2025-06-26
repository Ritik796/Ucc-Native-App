import { PermissionsAndroid, Platform } from "react-native";
import Geolocation from "@react-native-community/geolocation";
import DeviceInfo from "react-native-device-info";

export const requestLocationPermission = async () => {
    if (Platform.OS !== "android") return true;
    let isPermission = true;
    try {
        const granted = await PermissionsAndroid.requestMultiple(
            [PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION,
            PermissionsAndroid.PERMISSIONS.ACCESS_COARSE_LOCATION
            ]
        );

        if (
            granted[PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION] !==
            PermissionsAndroid.RESULTS.GRANTED
        ) {
            console.log("ACCESS_FINE_LOCATION permission denied");
            isPermission = false;
        }

        if (
            granted[PermissionsAndroid.PERMISSIONS.ACCESS_COARSE_LOCATION] !==
            PermissionsAndroid.RESULTS.GRANTED
        ) {
            console.log("ACCESS_COARSE_LOCATION permission denied");
            isPermission = false;
        }
        return isPermission;
    } catch (err) {
        console.warn('Permission request error:', err);
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

export const stopLocationTracking = (locationRef) => {
  if (locationRef?.current) {
    console.log("Location tracking stopped.", locationRef.current);
    Geolocation.clearWatch(locationRef.current);
    locationRef.current = null;
  }
};

export const readWebViewMessage = (event, webViewRef, locationRef) => {
    let data = event?.nativeEvent?.data;
    try {
        let msg = JSON.parse(data);
        console.log('msg', msg);
        switch (msg?.type) {
            case 'startLocationTracking':
                startLocationTracking(locationRef, webViewRef);
                break;
            case 'message':
                console.log(msg.data, msg.status);
                break;
            case 'location':
                console.log('msg.lat', msg.lat);
                console.log('msg.lng', msg.lng);
                break;
            default:
                break;
        }
    } catch (error) {
        return;
    }
};
