import React, { useEffect, useMemo, useState } from 'react';
import {
  connectionEstablish,
  handleBluetoothConnection,
  handleDevicePairing,
  postBtWebMessages,
} from '../../Action/Bluetooth/bluetoothModuleAction';

const BluetoothModule = ({
  btEvent,
  setBtEvent,
  webViewRef,
  btConnectionRequest,
  setBtConnectionRequest,
  blutoothRef,
}) => {
  const [error, setError] = useState({ type: null, visible: false, msg: '' });
  const [deviceList, setDeviceList] = useState([]);
  const [isDeviceConnected, setIsDeviceConnected] = useState(false);
  const [isBluetoothProcessDone, setIsBluetoothProcessDone] = useState(false);
    useEffect(() => {
    if (btEvent?.type === 'print-receipt') {
      handleBluetoothConnection(
        setError,
        webViewRef,
        setDeviceList,
        setIsDeviceConnected,
        isDeviceConnected
      );
    }
  }, [btEvent]);

  // Step 2: Handle Device Pairing
  useMemo(() => {
    if (btConnectionRequest) {
      handleDevicePairing(
        btConnectionRequest?.data,
        setError,
        deviceList,
        setIsDeviceConnected
      );
    }
  }, [btConnectionRequest]);

  // Step 3: Post Error to WebView and clear
  useEffect(() => {
    if (error && error?.msg && webViewRef?.current) {
      postBtWebMessages(webViewRef, 'nativeError', error);
      setBtConnectionRequest(null);
      setError(null);
    }
  }, [error]);

  // Step 4: Finalize Bluetooth Connection
  useEffect(() => {
    if (isDeviceConnected) {
      connectionEstablish(
        webViewRef,
        isDeviceConnected,
        deviceList,
        btEvent,
        setBtEvent
      );

      // After establishing connection, mark process as done
      setIsBluetoothProcessDone(true);
    }
  }, [isDeviceConnected]);

  // Step 5: Cleanup when everything is done
  useEffect(() => {
    if (isBluetoothProcessDone) {
      blutoothRef.current = false;
      setIsBluetoothProcessDone(false);
    }
  }, [isBluetoothProcessDone]);

  return <></>;
};

export default BluetoothModule;
