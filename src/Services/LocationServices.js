
import * as db from '../Services/dbService';
import { getCurrentDatabase } from "../Firebase";
let successStatus = 'success';
let failStatus = 'fail';




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
export const saveLocationHistory = async (
    traversalHistory,
    minuteDistance,
    currentTime,
    userId,
    fullPathFromJS, // <-- you pass this from JS
    dbPath
) => {
    try {
        if (!traversalHistory || traversalHistory.length === 0 || !userId || !dbPath || !fullPathFromJS) {
            return failStatus;
        }

        const database = await getCurrentDatabase(dbPath);

        let coveredDistance = await db.getData(`${fullPathFromJS}/TotalCoveredDistance`, database);
        const totalDistance = Number(minuteDistance ?? 0) + Number(coveredDistance ?? 0);

        await Promise.all([
            db.saveData(`${fullPathFromJS}/${currentTime}`, {
                'distance-in-meter': minuteDistance,
                'lat-lng': traversalHistory
            }, database),
            db.saveData(`${fullPathFromJS}`, {
                TotalCoveredDistance: totalDistance,
                'last-update-time': currentTime
            }, database)
        ]);

        return successStatus;
    } catch (err) {
        console.error("Error saving location history:", err);
        return failStatus;
    }
};


