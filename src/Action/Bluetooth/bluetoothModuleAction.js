import RNBluetoothClassic from 'react-native-bluetooth-classic';
import { PermissionsAndroid, Platform } from "react-native";
import AsyncStorage from '@react-native-async-storage/async-storage';

export const handleBluetoothConnection = async (setError, webViewRef, setDeviceList, setIsDeviceConnected, isDeviceConnected) => {
    try {
        const bluetoothAccess = await requestBluetoothPermissions(setError);
        if (bluetoothAccess) {
            setupBluetooth(setError, webViewRef, setDeviceList, setIsDeviceConnected, isDeviceConnected);
        }
    } catch (err) {
        console.warn('Permission request error:', err);
        return false;
    }
}
export const requestBluetoothPermissions = async (setError) => {
    if (Platform.OS === 'android') {
        // 1️⃣ Request ACCESS_FINE_LOCATION
        const location = await PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION);

        if (location !== PermissionsAndroid.RESULTS.GRANTED) {
            setError({ visible: true, type: 'PERMISSION', msg: 'Location Permission is required for bluetooth devices' });
            return;
        }
        if (Platform.Version >= 31) {
            const scanRequest = await PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.BLUETOOTH_SCAN);
            const connectRequest = await PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.BLUETOOTH_CONNECT);
            if (connectRequest !== PermissionsAndroid.RESULTS.GRANTED || scanRequest !== PermissionsAndroid.RESULTS.GRANTED) {
                setError({ visible: true, type: 'PERMISSION', msg: 'Please enable bluetooth permission' });
                return;
            }
            return true;
        }
        return true;
    }
}
export const setupBluetooth = async (setError, webViewRef, setDeviceList, setIsDeviceConnected) => {
    try {
        const available = await RNBluetoothClassic.isBluetoothAvailable();
        if (!available) {
            setError({ visible: true, type: 'SYSTEM', msg: 'Bluetooth not supported' });
            return;
        }

        const enabled = await RNBluetoothClassic.isBluetoothEnabled();
        if (!enabled) {
            await RNBluetoothClassic.requestBluetoothEnabled();
        }

        const connectedDevices = await RNBluetoothClassic.getConnectedDevices();
        if (connectedDevices.length > 0) {
            let device = connectedDevices[0];
            handleDevicePairing(device, setError, [], setIsDeviceConnected) // Return the device to use for printing

        } else {
            // setShowDevices(true);
            getBluetoothDeviceList(webViewRef, setDeviceList);
        }
    } catch (err) {
        setError({ visible: true, type: 'BLUETOOTH', msg: err.message?.includes('enable Bluetooth') ? 'Please turn on bluetooth' : 'Bluetooth setup failed' });
    }
}
export const getBluetoothDeviceList = async (webViewRef, setDeviceList) => {
    try {
        // setloader(true);
        const paired = await RNBluetoothClassic.getBondedDevices();
        setDeviceList([...paired]);
        postBtWebMessages(webViewRef, 'bluetooth', { showList: true, loader: true, deviceList: paired });// Only Paired list
        const pairedAddresses = new Set(paired.map(d => d.address));
        const discovered = await RNBluetoothClassic.startDiscovery();
        const newDiscovered = discovered.filter(d => !pairedAddresses.has(d.address));
        setDeviceList(pre => [...pre, ...newDiscovered]);
        postBtWebMessages(webViewRef, 'bluetooth', { showList: true, loader: false, deviceList: [...paired, ...newDiscovered] });// Paired + new scanned list
    }
    catch (e) {
        if (e.toString() !== 'Error: Bluetooth already in discovery mode') {
            setError({ visible: true, type: 'BLUETOOTH', msg: 'Please wait..' });
            // postBtWebMessages(webViewRef, 'bluetooth', { showList: true, loader: true, deviceList: [] });
        }
    }
}
export const handleDevicePairing = async (deviceData, setError, deviceList, setIsDeviceConnected) => {
    if (!deviceData) {
        setError({ visible: true, type: 'BLUETOOTH', msg: 'No device data provided.' });
        return;
    }

    let btDevice = null;

    // ✅ Check if deviceData is already a BluetoothDevice object (has connect method)
    if (typeof deviceData.connect === 'function') {
        btDevice = deviceData;
    } else if (deviceData.address) {
        // ✅ Else, find it in the deviceList using address
        btDevice = deviceList.find(d => d.address === deviceData.address);
    }

    if (!btDevice) {
        setError({
            visible: true,
            type: 'BLUETOOTH',
            msg: `${deviceData?.name || 'Device'} not found.`,
        });
        return;
    }

    try {
        console.log('Attempting connection to:', btDevice?.name);

        const connected = await btDevice.connect();
        if (connected) {
            setIsDeviceConnected(btDevice);
        } else {
            setIsDeviceConnected(null);
            setError({
                visible: true,
                type: 'BLUETOOTH',
                msg: `Could not connect to ${btDevice?.name || 'device'}`,
            });
        }
    } catch (err) {
        console.log('Connection error:', err);
        setIsDeviceConnected(null);
        setError({
            visible: true,
            type: 'BLUETOOTH',
            msg: `Error connecting to ${btDevice?.name || 'device'}. Please try again!`,
        });
    }
};

export const reconnectBt = async () => {
    let lastConnectedDevice = await AsyncStorage.getItem('lastConnectedDevice');
    const isBtOn = await RNBluetoothClassic.isBluetoothAvailable();
    if (lastConnectedDevice && isBtOn) {
        const pairedDevices = await RNBluetoothClassic.getBondedDevices();
        let device = pairedDevices.find(item => item.address === JSON.parse(lastConnectedDevice));

        if (device) {
            await device.connect();
        }
    }
}
export const postBtWebMessages = async (webViewRef, type, data) => {
    webViewRef?.current?.postMessage(JSON.stringify({ type, data }));
}
export const printReceipt = async (device, printData, setBtEvent) => {
    await AsyncStorage.setItem('lastConnectedDevice', JSON.stringify(device.address));
    await device.write(printData + '\n');
    // setBtEvent(null);
}
export const connectionEstablish = async (webViewRef, isDeviceConnected, deviceList, btEvent, setBtEvent) => {
    const { name, address } = isDeviceConnected || {};
    if (isDeviceConnected) {
        postBtWebMessages(webViewRef, 'bluetooth', {
            showList: false,
            loader: false,
            deviceList: deviceList,
            status: 'connected',
            deviceData: { name, address }
        });
        printReceipt(isDeviceConnected, btEvent?.data, setBtEvent);
        return;
    }
    postBtWebMessages(webViewRef, 'bluetooth', {
        showList: false,
        loader: false,
        deviceList: deviceList,
        status: 'failed',
        deviceData: { name, address }
    });
}