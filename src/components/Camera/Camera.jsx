import React, { useRef, useState, useEffect, useCallback } from 'react';
import { Modal, View, Text, TouchableOpacity, Image } from 'react-native';
import { Camera, useCameraDevice } from 'react-native-vision-camera';
import { cameraStyle as styles } from './CameraStyle';
import RNFS from 'react-native-fs';
import ImageResizer from 'react-native-image-resizer';
import Loader from '../Loader/Loader';
import * as webAction from '../../Action/WebViewPageAction/WebViewPageAction';

const CameraComponent = ({
  isVisible,
  onClose,
  setBase64Image,
  webViewRef,
  base64Image,
  setShowCamera,
  setIsVisible,
  setLoader,
  loader,
  locationRef
}) => {
  const cameraRef = useRef(null);
  const [cameraOpen, setCameraOpen] = useState(true);
  const [showPreview, setShowPreview] = useState(false);
  const [isLayoutReady, setIsLayoutReady] = useState(false);
  const [cameraData, setCameraData] = useState({ photoUri: "", resizedUri: "" });
  const device = useCameraDevice('back');
  const lastSent = useRef(0);
  useEffect(() => {
    const manageLocationTracking = async () => {
      if (isVisible) {
        console.log('Pausing location tracking (async)');
        await webAction.stopTracking(locationRef);
        setTimeout(() => {
          setCameraOpen(true);
        }, 500);
      }

    };

    manageLocationTracking();


    return async () => {
      console.log('Cleanup: resuming location tracking');
      await webAction.startLocationTracking(locationRef, webViewRef);
      setCameraOpen(false);
    };
  }, [isVisible]);
  const deleteFile = async (filePath) => {
    try {
      const exists = await RNFS.exists(filePath);
      if (exists) {
        await RNFS.unlink(filePath);
      }
    } catch (error) {
      console.log('Failed to delete file:', error);
    }
  };
  const captureImage = useCallback(async () => {
    if (cameraRef.current) {
      setLoader(true);
      try {
        const photo = await cameraRef.current.takePhoto({ qualityPrioritization: 'balanced' });
        const resizedImage = await ImageResizer.createResizedImage(
          photo.path,
          800,
          800,
          'JPEG',
          100,
          0
        );
        const base64Data = await RNFS.readFile(resizedImage.uri, 'base64');
        const formattedBase64 = `data:image/jpeg;base64,${base64Data}`;
        setCameraData(pre => ({ ...pre, photoUri: photo.path, resizedUri: resizedImage.uri }));
        setBase64Image(formattedBase64);
        setCameraOpen(false);
        setShowPreview(true);

      } catch (err) {
        console.error('Error capturing or resizing image:', err);
      }
      setLoader(false);
    }
  }, [setBase64Image, setLoader]);

  const confirmPhoto = async () => {
    setLoader(true);
    try {
      const now = Date.now();
      if (base64Image && now - lastSent.current > 3000) {
        lastSent.current = now;
        const messageData = { type: "image", image: base64Image };
        webViewRef.current?.postMessage(JSON.stringify(messageData));
      }
      // Clean up local files if cameraData exists
      if (cameraData?.photoUri) {
        await deleteFile(cameraData.photoUri);
      }
      if (cameraData?.resizedUri) {
        await deleteFile(cameraData.resizedUri);
      }

      // Clear stored image data
      setBase64Image(null);
      setCameraData({ photoUri: null, resizedUri: null });
    } catch (error) {
      console.log('Error during photo confirmation or cleanup:', error);
    }

    setLoader(false);
    handleClose();
  };


  const handleClose = () => {
    setCameraOpen(false);
    setIsVisible(false);
    setTimeout(() => {
      setShowCamera(false);
      setShowPreview(false);
    }, 300);
  };

  return (
    <Modal visible={isVisible} animationType="slide">
      <View style={styles.safeContainer}>
        <View style={styles.container}>
          {loader && <Loader />}

          <View style={styles.header}>
            <TouchableOpacity style={styles.headerLeft} onPress={onClose}>
              <Text style={styles.headerText}>
                {showPreview ? 'Proceed to save' : 'Capture Photo'}
              </Text>
            </TouchableOpacity>
          </View>

          <View style={styles.body}>
            <View
              style={styles.cameraPreviewContainer}
              onLayout={() => setIsLayoutReady(true)}
            >
              {showPreview ? (
                <Image
                  source={{ uri: base64Image }}
                  style={{ width: '100%', height: '100%', resizeMode: 'contain' }}
                  onLoadEnd={() => setLoader(false)}
                />
              ) : (
                isLayoutReady &&
                device &&
                cameraOpen && (
                  <Camera
                    ref={cameraRef}
                    style={styles.cameraContainer}
                    device={device}
                    isActive={cameraOpen}
                    photo={true}
                  />
                )
              )}
            </View>

            <View style={styles.footerBotttom}>
              <TouchableOpacity style={styles.cancelBtn} onPress={handleClose}>
                <Text style={styles.captureTxt}>Cancel</Text>
              </TouchableOpacity>

              <TouchableOpacity
                style={styles.captureBtn}
                onPress={showPreview ? confirmPhoto : captureImage}
              >
                <Text style={styles.captureTxt}>
                  {showPreview ? 'Proceed' : 'Capture'}
                </Text>
              </TouchableOpacity>
            </View>
          </View>
        </View>
      </View>
    </Modal>
  );
};

export default CameraComponent;