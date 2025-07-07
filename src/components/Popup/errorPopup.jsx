import React from 'react';
import {
  Modal,
  View,
  Text,
  TouchableOpacity,
  StyleSheet,
  Dimensions,
  StatusBar,
} from 'react-native';

const {width} = Dimensions.get('window');

// Error types with predefined configurations
export const ERROR_TYPES = {
  BLUETOOTH: {
    title: 'Bluetooth Error',
    icon: '📶',
    color: '#FF6B6B',
    defaultMessage:
      'Bluetooth connection failed. Please check your Bluetooth settings.',
  },
  DEVICE_CONNECTION: {
    title: 'Device Not Connected',
    icon: '🔌',
    color: '#FF9F43',
    defaultMessage:
      'Unable to connect to the device. Please ensure the device is powered on and in range.',
  },
  NETWORK: {
    title: 'Network Error',
    icon: '🌐',
    color: '#FF6B6B',
    defaultMessage:
      'Network connection failed. Please check your internet connection.',
  },
  SYSTEM: {
    title: 'System Error',
    icon: '⚠️',
    color: '#FF4757',
    defaultMessage: 'A system error occurred. Please try again later.',
  },
  PERMISSION: {
    title: 'Permission Required',
    icon: '🔒',
    color: '#FFA502',
    defaultMessage:
      'Required permissions are not granted. Please enable permissions in settings.',
  },
  TIMEOUT: {
    title: 'Connection Timeout',
    icon: '⏱️',
    color: '#FF6348',
    defaultMessage: 'The operation timed out. Please try again.',
  },
};

const ErrorPopup = ({
  visible = false,
  errorType = 'SYSTEM',
  customTitle = null,
  customMessage = null,
  customIcon = null,
  customColor = null,
  onDismiss = () => {},
  onRetry = null,
  onSettings = null,
  dismissText = 'OK',
  retryText = 'Retry',
  settingsText = 'Settings',
  autoHide = false,
  autoHideDelay = 3000,
}) => {
  const errorConfig = ERROR_TYPES[errorType] || ERROR_TYPES.SYSTEM;

  React.useEffect(() => {
    if (visible && autoHide) {
      const timer = setTimeout(() => {
        onDismiss();
      }, autoHideDelay);
      return () => clearTimeout(timer);
    }
  }, [visible, autoHide, autoHideDelay, onDismiss]);

  const title = customTitle || errorConfig.title;
  const message = customMessage || errorConfig.defaultMessage;
  const icon = customIcon || errorConfig.icon;
  const color = customColor || errorConfig.color;

  return (
    <Modal
      visible={visible}
      transparent
      animationType="fade"
      statusBarTranslucent
      onRequestClose={onDismiss}>
      <StatusBar backgroundColor="rgba(0,0,0,0.5)" barStyle="light-content" />
      <View style={styles.overlay}>
        <View style={[styles.popup, {borderTopColor: color}]}>
          {/* Icon and Title */}
          <View style={styles.header}>
            <Text style={styles.icon}>{icon}</Text>
            <Text style={[styles.title, {color}]}>{title}</Text>
          </View>

          {/* Message */}
          <Text style={styles.message}>{message}</Text>

          {/* Action Buttons */}
          <View style={styles.buttonContainer}>
            {onSettings && (
              <TouchableOpacity
                style={[styles.button, styles.settingsButton]}
                onPress={onSettings}
                activeOpacity={0.7}>
                <Text style={styles.settingsButtonText}>{settingsText}</Text>
              </TouchableOpacity>
            )}

            {onRetry && (
              <TouchableOpacity
                style={[
                  styles.button,
                  styles.retryButton,
                  {backgroundColor: color},
                ]}
                onPress={onRetry}
                activeOpacity={0.7}>
                <Text style={styles.buttonText}>{retryText}</Text>
              </TouchableOpacity>
            )}

            <TouchableOpacity
              style={[
                styles.button,
                onRetry || onSettings
                  ? styles.secondaryButton
                  : [styles.primaryButton, {backgroundColor: color}],
              ]}
              onPress={onDismiss}
              activeOpacity={0.7}>
              <Text
                style={[
                  onRetry || onSettings
                    ? styles.secondaryButtonText
                    : styles.buttonText,
                ]}>
                {dismissText}
              </Text>
            </TouchableOpacity>
          </View>
        </View>
      </View>
    </Modal>
  );
};

const styles = StyleSheet.create({
  overlay: {
    flex: 1,
    backgroundColor: 'rgba(0, 0, 0, 0.5)',
    justifyContent: 'center',
    alignItems: 'center',
    paddingHorizontal: 20,
  },
  popup: {
    backgroundColor: '#FFFFFF',
    borderRadius: 12,
    width: width * 0.85,
    maxWidth: 400,
    borderTopWidth: 4,
    elevation: 10,
    shadowColor: '#000',
    shadowOffset: {
      width: 0,
      height: 4,
    },
    shadowOpacity: 0.25,
    shadowRadius: 8,
  },
  header: {
    alignItems: 'center',
    paddingTop: 24,
    paddingHorizontal: 24,
  },
  icon: {
    fontSize: 48,
    marginBottom: 12,
  },
  title: {
    fontSize: 20,
    fontWeight: '600',
    textAlign: 'center',
    marginBottom: 8,
  },
  message: {
    fontSize: 16,
    color: '#666666',
    textAlign: 'center',
    lineHeight: 22,
    paddingHorizontal: 24,
    marginBottom: 24,
  },
  buttonContainer: {
    flexDirection: 'row',
    paddingHorizontal: 16,
    paddingBottom: 16,
    gap: 8,
  },
  button: {
    flex: 1,
    paddingVertical: 12,
    paddingHorizontal: 16,
    borderRadius: 8,
    alignItems: 'center',
    justifyContent: 'center',
    minHeight: 44,
  },
  primaryButton: {
    // backgroundColor set dynamically
  },
  secondaryButton: {
    backgroundColor: 'transparent',
    borderWidth: 1,
    borderColor: '#DDDDDD',
  },
  retryButton: {
    // backgroundColor set dynamically
  },
  settingsButton: {
    backgroundColor: '#F8F9FA',
    borderWidth: 1,
    borderColor: '#E9ECEF',
  },
  buttonText: {
    color: '#FFFFFF',
    fontSize: 16,
    fontWeight: '600',
  },
  secondaryButtonText: {
    color: '#666666',
    fontSize: 16,
    fontWeight: '500',
  },
  settingsButtonText: {
    color: '#495057',
    fontSize: 16,
    fontWeight: '500',
  },
});

export default ErrorPopup;

// Usage Examples:
/*

// Basic Bluetooth Error
<ErrorPopup
  visible={showBluetoothError}
  errorType="BLUETOOTH"
  onDismiss={() => setShowBluetoothError(false)}
/>

// Device Connection Error with Retry
<ErrorPopup
  visible={showDeviceError}
  errorType="DEVICE_CONNECTION"
  onDismiss={() => setShowDeviceError(false)}
  onRetry={handleRetryConnection}
/>

// Custom Error with Settings
<ErrorPopup
  visible={showPermissionError}
  errorType="PERMISSION"
  customMessage="Camera permission is required to scan QR codes."
  onDismiss={() => setShowPermissionError(false)}
  onSettings={() => {
    setShowPermissionError(false);
    // Open app settings
    Linking.openSettings();
  }}
/>

// System Error with Auto Hide
<ErrorPopup
  visible={showSystemError}
  errorType="SYSTEM"
  customMessage="Unable to save data. Please try again."
  onDismiss={() => setShowSystemError(false)}
  autoHide={true}
  autoHideDelay={5000}
/>

// Completely Custom Error
<ErrorPopup
  visible={showCustomError}
  customTitle="Upload Failed"
  customMessage="The file you're trying to upload is too large."
  customIcon="📁"
  customColor="#9C27B0"
  onDismiss={() => setShowCustomError(false)}
  dismissText="Got it"
/>

*/
