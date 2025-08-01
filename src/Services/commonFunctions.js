import { Platform, PermissionsAndroid } from 'react-native';
import Geolocation from "@react-native-community/geolocation";
import { postBtWebMessages } from '../Action/Bluetooth/bluetoothModuleAction';

const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
export const getCurrentLocation = async (maxAttempt = 3, gapMs = 1000, webViewRef) => {
    try {
        // Request permission on Android
        if (Platform.OS === 'android') {
            const granted = await PermissionsAndroid.request(
                PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION,
                {
                    title: 'Location Permission',
                    message: 'App needs location access to function properly.',
                    buttonPositive: 'OK',
                }
            );

            if (granted !== PermissionsAndroid.RESULTS.GRANTED) {
                console.warn('❌ Location permission denied');
                return null;
            }
        }
        const results = [];
        for (let i = 0; i < maxAttempt; i++) {
            try {
                const location = await getOnePosition();
                results.push(location);
            } catch (err) {
                console.warn(`⚠️ Attempt ${i + 1} failed:`, err);
            }

            if (i < maxAttempt - 1) {
                await delay(gapMs); // ⏱️ Wait before the next attempt
            }
        }

        if (results.length === 0) return null;
        const mostAccurate = results.reduce((best, current) => current.accuracy < best.accuracy ? current : best);
        postBtWebMessages(webViewRef, 'currentLocation', mostAccurate);
    } catch (error) {
        console.error('🚨 Unexpected location error:', error.message);
        return null;
    }
};
const getOnePosition = () => {
    return new Promise((resolve, reject) => {
        Geolocation.getCurrentPosition(
            (position) => {
                const { accuracy, latitude, longitude } = position.coords || {};
                if (accuracy && latitude && longitude) {
                    resolve({ accuracy, latitude, longitude });
                } else {
                    reject('Invalid coordinates');
                }
            },
            (error) => reject(error),
            {
                enableHighAccuracy: true,
                timeout: 10000,
                // maximumAge: 0,
                forceRequestLocation: true,
                showLocationDialog: true,
            }
        );
    });
};

