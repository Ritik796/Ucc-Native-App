import Geolocation from "@react-native-community/geolocation";
import * as db from '../Services/dbService'
import { getCurrentDatabase } from "../Firebase";
let successStatus = 'success';
let failStatus = 'fail';
let traversalHistory = [];
let maxDistanceCanCover = 0; // Initial allowed max distance
const maxDistance = 15;       // Used to increase tolerance if spike happens
let previousLat = null;
let previousLng = null;

/*
    Function name : getCurrentPosition();
    Description  : This function is working for get current location of user;
    Written By : Ritik Parmar
    Writeen date : 10 Jul 2025
 */
export const getCurrentPosition = () => {
    return new Promise((resolve) => {
        try {
            Geolocation.getCurrentPosition(
                (pos) => {
                    const { latitude, longitude, accuracy } = pos.coords;
                    if (accuracy != null && accuracy <= 15) {
                        resolve({ status: successStatus, data: { latitude, longitude } });
                    }
                    else {
                        console.log("Skipped low-accuracy position:", accuracy);
                    }
                },
                (error) => {
                    console.log(error);
                    resolve({ status: failStatus, data: {} });
                },
                {
                    enableHighAccuracy: true,
                    maximumAge: 0,
                    timeout: 5000,
                }
            );
        } catch (error) {
            console.log(error);
            resolve({ status: failStatus, data: {} });
        }
    });
};

/*
    Function name : getDistance();
    Description  : This function is working for get distance in meter between two positions;
    Written By : Ritik Parmar
    Writeen date : 10 Jul 2025
 */
export const getDistance = (lat1, lon1, lat2, lon2) => {
    const toRad = (value) => (value * Math.PI) / 180;
    const R = 6371000; // in meters

    const dLat = toRad(lat2 - lat1);
    const dLon = toRad(lon2 - lon1);

    const a = Math.sin(dLat / 2) ** 2 +
        Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) *
        Math.sin(dLon / 2) ** 2;

    const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    return Math.round(R * c);
};

/*
    Function name : getCurrentPosition();
    Description  : This function is working for get distance in meter between two positions;
    Written By : Ritik Parmar
    Writeen date : 10 Jul 2025
 */
export const getTotalCoverDistance = (traversalArray) => {
    try {
        if (!Array.isArray(traversalArray) || traversalArray.length < 2) {
            return { status: successStatus, data: { totalDistance: 0, traversalArray } };
        }

        const toRad = (value) => (value * Math.PI) / 180;

        const calculateDistanceInMeters = (lat1, lon1, lat2, lon2) => {
            const R = 6371000; // Radius of Earth in meters
            const dLat = toRad(lat2 - lat1);
            const dLon = toRad(lon2 - lon1);

            const a =
                Math.sin(dLat / 2) ** 2 +
                Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) *
                Math.sin(dLon / 2) ** 2;

            const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
            return R * c;
        };

        let totalDistance = 0;

        for (let i = 1; i < traversalArray.length; i++) {
            const prev = traversalArray[i - 1];
            const current = traversalArray[i];

            if (
                prev.latitude && prev.longitude &&
                current.latitude && current.longitude
            ) {
                totalDistance += calculateDistanceInMeters(
                    prev.latitude, prev.longitude,
                    current.latitude, current.longitude
                );
            }
        }

        return {
            status: successStatus,
            data: {
                totalDistance: totalDistance, // total in meters
                traversalArray
            }
        };
    } catch (error) {
        return { status: failStatus, data: { error } };
    }
};

/*
    Function name : saveLocationHistory();
    Description  : This function is working for save location history of payment collector
    Written By : Ritik Parmar
    Writeen date : 10 Jul 2025
 */
export const saveLocationHistory = async (traversalHistory, minuteDistance, currentTime, userId, dbPath) => {
    try {
        if (!traversalHistory || traversalHistory.length === 0 || !userId || !dbPath ) {
            return failStatus;
        }
        let database = await getCurrentDatabase(dbPath)
        const now = new Date();
        const year = now.getFullYear();
        const month = now.toLocaleString("default", { month: "long" });
        const todayDate = now.toISOString().split("T")[0];

        const path = `PaymentCollectionInfo/PaymentCollectorLocationHistory/${userId}/${year}/${month}/${todayDate}`;

        // Fetch and calculate distances in parallel
        let coveredDistance = await db.getData(`${path}/TotalCoveredDistance`, database)

        const totalDistance = Number(minuteDistance ?? 0) + Number(coveredDistance ?? 0);
       

        // Save distance and lat-lng, and update summary info concurrently
        await Promise.all([
            db.saveData(`${path}/${currentTime}`, {
                'distance-in-meter': minuteDistance,
                'lat-lng': traversalHistory
            },database),
            db.saveData(`${path}`, {
                TotalCoveredDistance: totalDistance,
                'last-update-time': currentTime

            },database)
        ]);

        return successStatus;
    } catch (err) {
        console.error("Error saving location history:", err);
        return failStatus;
    }
};


