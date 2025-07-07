import React, {useEffect, useMemo, useState} from 'react';
import {
  connectionEstablish,
  handleBluetoothConnection,
  handleDevicePairing,
  postBtWebMessages,
  printReceipt,
} from '../../Action/Bluetooth/bluetoothModuleAction';

const BluetoothModule = ({
  btEvent,
  setBtEvent,
  webViewRef,
  btConnectionRequest,
  setBtConnectionRequest,
}) => {
  const [error, setError] = useState({type: null, visible: false, msg: ''});
  const [deviceList, setDeviceList] = useState([]);
  const [isDeviceConnected, setIsDeviceConnected] = useState(false);
  useEffect(() => {
    if (btEvent?.type === 'print-receipt') {
      handleBluetoothConnection(
        setError,
        webViewRef,
        setDeviceList,
        setIsDeviceConnected,
      );
    }
  }, [btEvent]);
  useEffect(() => {
    if (error && error?.msg && webViewRef?.current) {
      postBtWebMessages(webViewRef, 'nativeError', error);
      // setBtEvent(null);
      setBtConnectionRequest(null);
      setError(null);
    }
  }, [error || error?.msg]);
  useMemo(() => {
    if (btConnectionRequest) {
      handleDevicePairing(
        btConnectionRequest?.data,
        setError,
        deviceList,
        setIsDeviceConnected,
      );
    }
  }, [btConnectionRequest]);
  useEffect(() => {
    connectionEstablish(
      webViewRef,
      isDeviceConnected,
      deviceList,
      btEvent,
      setBtEvent,
    );
  }, [isDeviceConnected]);
  return <></>;
};

export default BluetoothModule;
