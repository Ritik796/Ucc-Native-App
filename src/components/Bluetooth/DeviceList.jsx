import React, {useEffect, useRef} from 'react';
import {
  View,
  Text,
  FlatList,
  TouchableOpacity,
  StyleSheet,
  Modal,
  Animated,
  Dimensions,
  PanResponder,
  ActivityIndicator,
  RefreshControl,
} from 'react-native';

// Device connection status
const CONNECTION_STATUS = {
  CONNECTED: 'connected',
  CONNECTING: 'connecting',
  DISCONNECTED: 'disconnected',
  PAIRING: 'pairing',
};
const {height: screenHeight} = Dimensions.get('window');
const styles = StyleSheet.create({
  backdrop: {
    ...StyleSheet.absoluteFillObject,
    backgroundColor: 'rgba(0, 0, 0, 0.5)',
    zIndex: 999,
  },
  backdropTouchable: {
    flex: 1,
  },
  bottomSheet: {
    position: 'absolute',
    bottom: 0,
    left: 0,
    right: 0,
    backgroundColor: '#FFFFFF',
    borderTopLeftRadius: 20,
    borderTopRightRadius: 20,
    shadowColor: '#000',
    shadowOffset: {
      width: 0,
      height: -2,
    },
    shadowOpacity: 0.25,
    shadowRadius: 10,
    elevation: 10,
    zIndex: 1000,
  },
  handle: {
    width: 40,
    height: 4,
    backgroundColor: '#CCCCCC',
    borderRadius: 2,
    alignSelf: 'center',
    marginTop: 8,
    marginBottom: 8,
  },
  header: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingHorizontal: 20,
    paddingVertical: 16,
    borderBottomWidth: 1,
    borderBottomColor: '#E0E0E0',
  },
  title: {
    fontSize: 20,
    fontWeight: '600',
    color: '#000000',
  },
  closeButton: {
    width: 32,
    height: 32,
    borderRadius: 16,
    backgroundColor: '#F5F5F5',
    justifyContent: 'center',
    alignItems: 'center',
  },
  closeButtonText: {
    fontSize: 16,
    color: '#666666',
  },
  list: {
    flex: 1,
  },
  deviceItem: {
    backgroundColor: '#FFFFFF',
    borderBottomWidth: 1,
    borderBottomColor: '#F0F0F0',
  },
  deviceContent: {
    flexDirection: 'row',
    alignItems: 'center',
    padding: 16,
  },
  bluetoothIcon: {
    fontSize: 24,
    marginRight: 12,
  },
  deviceInfo: {
    flex: 1,
  },
  deviceName: {
    fontSize: 16,
    fontWeight: '500',
    color: '#000000',
    marginBottom: 4,
  },
  deviceAddress: {
    fontSize: 14,
    color: '#666666',
  },
  arrow: {
    fontSize: 18,
    color: '#CCCCCC',
  },
  emptyContainer: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    padding: 32,
  },
  emptyIcon: {
    fontSize: 48,
    marginBottom: 16,
    opacity: 0.3,
  },
  emptyText: {
    fontSize: 16,
    color: '#666666',
    textAlign: 'center',
  },
  retryButton: {
    backgroundColor: '#2196F3',
    paddingHorizontal: 24,
    paddingVertical: 12,
    borderRadius: 24,
  },
  retryButtonText: {
    color: '#FFFFFF',
    fontSize: 16,
    fontWeight: '600',
  },
});
const BluetoothDeviceList = ({
  visible = false,
  devices = [],
  onDevicePress = device => console.log('Device pressed:', device),
  onClose = () => console.log('Bottom sheet closed'),
  title = 'Bluetooth Devices',
  emptyMessage = 'No Bluetooth devices found',
  height = screenHeight * 0.6,
  isScanning = false,
  refresh,
  setRefresh,
}) => {
  const slideAnim = useRef(new Animated.Value(height)).current;
  const backdropOpacity = useRef(new Animated.Value(0)).current;
  useEffect(() => {
    if (visible) {
      // Slide up and fade in backdrop
      Animated.parallel([
        Animated.timing(slideAnim, {
          toValue: 0,
          duration: 300,
          useNativeDriver: true,
        }),
        Animated.timing(backdropOpacity, {
          toValue: 1,
          duration: 300,
          useNativeDriver: true,
        }),
      ]).start();
    } else {
      // Slide down and fade out backdrop
      Animated.parallel([
        Animated.timing(slideAnim, {
          toValue: height,
          duration: 250,
          useNativeDriver: true,
        }),
        Animated.timing(backdropOpacity, {
          toValue: 0,
          duration: 250,
          useNativeDriver: true,
        }),
      ]).start();
    }
  }, [visible, slideAnim, backdropOpacity, height]);

  const panResponder = PanResponder.create({
    onMoveShouldSetPanResponder: (evt, gestureState) => {
      return gestureState.dy > 10;
    },
    onPanResponderMove: (evt, gestureState) => {
      if (gestureState.dy > 0) {
        slideAnim.setValue(gestureState.dy);
      }
    },
    onPanResponderRelease: (evt, gestureState) => {
      if (gestureState.dy > height * 0.3) {
        // Close if dragged down more than 30%
        onClose();
      } else {
        // Snap back to open position
        Animated.timing(slideAnim, {
          toValue: 0,
          duration: 200,
          useNativeDriver: true,
        }).start();
      }
    },
  });

  const handleDevicePress = device => {
    onDevicePress(device);
    // Optionally close the bottom sheet after selection
    // onClose();
  };
  const getDeviceIcon = device => {
    const {deviceClass, name, type, bonded} = device;
    const baseIcons = {
      headset: '🎧',
      smartphone: '📱',
      computer: '💻',
      watch: '⌚',
      keyboard: '⌨️',
      mouse: '🖱️',
      print: '🖨️',
      toy: '🧸',
      default: '📶',
    };

    // Check majorClass first (most reliable)
    if (deviceClass?.majorClass) {
      const majorClass = deviceClass.majorClass;

      switch (majorClass) {
        case 256:
          return baseIcons['computer'];
        case 512:
          return baseIcons['smartphone'];
        case 1024:
          return baseIcons['headset'];
        case 1280:
          return baseIcons['keyboard'];
        case 1536:
          return baseIcons['print']; // Your MPT-II falls here
        case 1792:
          return baseIcons['watch'];
        case 2048:
          return baseIcons['toys'];
        default:
          break;
      }
    }

    // Fallback to name-based detection
    if (name) {
      const deviceName = name.toLowerCase();

      if (deviceName.includes('print') || deviceName.includes('mpt'))
        return baseIcons['print'];
      if (deviceName.includes('headphone') || deviceName.includes('airpods'))
        return baseIcons['headset'];
      if (deviceName.includes('speaker')) return baseIcons['speaker'];
      if (deviceName.includes('phone')) return baseIcons['smartphone'];
      if (deviceName.includes('watch')) return baseIcons['watch'];
      if (deviceName.includes('computer')) return baseIcons['computer'];
    }

    return baseIcons['bluetooth'];
  };

  const renderDeviceItem = ({item}) => (
    <TouchableOpacity
      style={styles.deviceItem}
      onPress={() => handleDevicePress(item)}
      activeOpacity={0.7}>
      <View style={styles.deviceContent}>
        <Text style={styles.bluetoothIcon}>{getDeviceIcon(item)}</Text>
        <View style={styles.deviceInfo}>
          <Text style={styles.deviceName}>{item.name}</Text>
          {/* <Text style={styles.deviceAddress}>{item.address}</Text> */}
        </View>
        {/* <Text style={styles.arrow}>›</Text> */}
      </View>
    </TouchableOpacity>
  );
  const renderEmptyState = () => (
    <View style={styles.emptyContainer}>
      {/* <Text style={styles.emptyIcon}>📶</Text> */}
      <Text style={styles.emptyTitle}>
        {isScanning ? 'Searching...' : 'No Devices Found'}
      </Text>
      <Text style={styles.emptyMessage}>
        {isScanning ? 'Looking for nearby Bluetooth devices' : emptyMessage}
      </Text>
      {!isScanning && (
        <TouchableOpacity
          style={styles.retryButton}
          //   onPress={onToggleScan}
          activeOpacity={0.7}>
          <Text style={styles.retryButtonText}>Scan Again</Text>
        </TouchableOpacity>
      )}
    </View>
  );

  return (
    <Modal
      visible={visible}
      transparent
      animationType="none"
      onRequestClose={onClose}
      statusBarTranslucent={true}
      presentationStyle="overFullScreen">
      {/* Backdrop */}
      <Animated.View style={[styles.backdrop, {opacity: backdropOpacity}]}>
        <TouchableOpacity
          style={styles.backdropTouchable}
          activeOpacity={1}
          onPress={onClose}
        />
      </Animated.View>

      {/* Bottom Sheet */}
      <Animated.View
        style={[
          styles.bottomSheet,
          {
            height: height,
            transform: [{translateY: slideAnim}],
          },
        ]}
        {...panResponder.panHandlers}>
        {/* Handle */}
        <View style={styles.handle} />

        {/* Header */}
        <View style={styles.header}>
          <Text style={styles.title}>
            {title}
            {isScanning && (
              <ActivityIndicator size="small" color="green" marginLeft={5} />
            )}
          </Text>

          <TouchableOpacity
            style={styles.closeButton}
            onPress={onClose}
            activeOpacity={0.7}>
            <Text style={styles.closeButtonText}>✕</Text>
          </TouchableOpacity>
        </View>

        {/* Device List */}
        <FlatList
          data={devices}
          renderItem={renderDeviceItem}
          keyExtractor={item => item.id}
          ListEmptyComponent={renderEmptyState}
          style={styles.list}
          showsVerticalScrollIndicator={false}
          bounces={false}
          refreshControl={
            <RefreshControl
              refreshing={refresh}
              onRefresh={() => setRefresh(true)}
              colors={['#2196F3']}
              tintColor="#2196F3"
            />
          }
        />
      </Animated.View>
    </Modal>
  );
};

export default BluetoothDeviceList;

// Usage Example:
/*
import React, { useState } from 'react';
import { View, TouchableOpacity, Text, StyleSheet, Alert } from 'react-native';
import BluetoothBottomSheet from './BluetoothBottomSheet';

const App = () => {
  const [showBottomSheet, setShowBottomSheet] = useState(false);

  const handleDevicePress = (device) => {
    Alert.alert(
      'Connect Device',
      `Connect to ${device.name}?`,
      [
        { text: 'Cancel', style: 'cancel' },
        { 
          text: 'Connect', 
          onPress: () => {
            console.log('Connecting to:', device);
            setShowBottomSheet(false); // Close bottom sheet after connecting
            // Add your connection logic here
          }
        },
      ]
    );
  };

  return (
    <View style={styles.container}>
      <TouchableOpacity
        style={styles.openButton}
        onPress={() => setShowBottomSheet(true)}
      >
        <Text style={styles.openButtonText}>Show Bluetooth Devices</Text>
      </TouchableOpacity>

      <BluetoothBottomSheet
        visible={showBottomSheet}
        onClose={() => setShowBottomSheet(false)}
        onDevicePress={handleDevicePress}
        title="Choose Device"
      />
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    backgroundColor: '#F5F5F5',
  },
  openButton: {
    backgroundColor: '#007AFF',
    paddingHorizontal: 24,
    paddingVertical: 12,
    borderRadius: 8,
  },
  openButtonText: {
    color: '#FFFFFF',
    fontSize: 16,
    fontWeight: '600',
  },
});

export default App;
*/
// const BluetoothDeviceList = ({
//   devices = sampleDevices,
//   isScanning = false,
//   onDevicePress = device => console.log('Device pressed:', device),
//   onRefresh = () => console.log('Refreshing...'),
//   onToggleScan = () => console.log('Toggle scan'),
//   showRSSI = true,
//   showAddress = true,
//   emptyMessage = 'No Bluetooth devices found',
//   scanningMessage = 'Searching for devices...',
// }) => {
//   const [refreshing, setRefreshing] = useState(false);
//   console.log(devices);

//   const handleRefresh = async () => {
//     setRefreshing(true);
//     await onRefresh();
//     setRefreshing(false);
//   };

//   const getDeviceIcon = (deviceType, status) => {
//     const baseIcons = {
//       audio: '🎧',
//       phone: '📱',
//       computer: '💻',
//       watch: '⌚',
//       speaker: '🔊',
//       headset: '🎤',
//       keyboard: '⌨️',
//       mouse: '🖱️',
//       default: '📶',
//     };

//     if (status === CONNECTION_STATUS.CONNECTED) {
//       return baseIcons[deviceType] || baseIcons.default;
//     }
//     return baseIcons[deviceType] || baseIcons.default;
//   };

//   const getStatusColor = status => {
//     switch (status) {
//       case CONNECTION_STATUS.CONNECTED:
//         return '#4CAF50';
//       case CONNECTION_STATUS.CONNECTING:
//       case CONNECTION_STATUS.PAIRING:
//         return '#FF9800';
//       case CONNECTION_STATUS.DISCONNECTED:
//         return '#9E9E9E';
//       default:
//         return '#9E9E9E';
//     }
//   };

//   const getStatusText = status => {
//     switch (status) {
//       case CONNECTION_STATUS.CONNECTED:
//         return 'Connected';
//       case CONNECTION_STATUS.CONNECTING:
//         return 'Connecting...';
//       case CONNECTION_STATUS.PAIRING:
//         return 'Pairing...';
//       case CONNECTION_STATUS.DISCONNECTED:
//         return 'Available';
//       default:
//         return 'Unknown';
//     }
//   };

//   const getSignalStrength = rssi => {
//     if (rssi >= -50)
//       return {strength: 'Excellent', bars: '📶', color: '#4CAF50'};
//     if (rssi >= -60) return {strength: 'Good', bars: '📶', color: '#8BC34A'};
//     if (rssi >= -70) return {strength: 'Fair', bars: '📶', color: '#FF9800'};
//     return {strength: 'Weak', bars: '📶', color: '#F44336'};
//   };

//   const renderDeviceItem = ({item}) => {
//     const signal = getSignalStrength(item.rssi);
//     const statusColor = getStatusColor(item.status);
//     const isConnecting =
//       item.status === CONNECTION_STATUS.CONNECTING ||
//       item.status === CONNECTION_STATUS.PAIRING;

//     return (
//       <TouchableOpacity
//         style={[
//           styles.deviceItem,
//           item.status === CONNECTION_STATUS.CONNECTED && styles.connectedDevice,
//         ]}
//         onPress={() => onDevicePress(item)}
//         activeOpacity={0.7}>
//         <View style={styles.deviceContent}>
//           {/* Device Icon and Info */}
//           <View style={styles.deviceInfo}>
//             <View style={styles.iconContainer}>
//               <Text style={styles.deviceIcon}>
//                 {getDeviceIcon(item.deviceType, item.status)}
//               </Text>
//               {item.isPaired && (
//                 <View style={styles.pairedIndicator}>
//                   <Text style={styles.pairedIcon}>🔗</Text>
//                 </View>
//               )}
//             </View>

//             <View style={styles.deviceDetails}>
//               <Text style={styles.deviceName} numberOfLines={1}>
//                 {item.name || 'Unknown Device'}
//               </Text>

//               {showAddress && (
//                 <Text style={styles.deviceAddress} numberOfLines={1}>
//                   {item.address}
//                 </Text>
//               )}

//               <View style={styles.statusContainer}>
//                 <View style={styles.statusBadge}>
//                   <View
//                     style={[styles.statusDot, {backgroundColor: statusColor}]}
//                   />
//                   <Text style={[styles.statusText, {color: statusColor}]}>
//                     {getStatusText(item.status)}
//                   </Text>
//                 </View>

//                 {item.isPaired && <Text style={styles.pairedText}>Paired</Text>}
//               </View>
//             </View>
//           </View>

//           {/* Signal Strength and Connection Indicator */}
//           <View style={styles.deviceActions}>
//             {showRSSI && (
//               <View style={styles.signalContainer}>
//                 <Text style={[styles.signalBars, {color: signal.color}]}>
//                   {signal.bars}
//                 </Text>
//                 <Text style={styles.rssiText}>{item.rssi} dBm</Text>
//               </View>
//             )}

//             {isConnecting && (
//               <ActivityIndicator
//                 size="small"
//                 color={statusColor}
//                 style={styles.connectingSpinner}
//               />
//             )}

//             <Text style={styles.chevron}>›</Text>
//           </View>
//         </View>
//       </TouchableOpacity>
//     );
//   };

//   const renderHeader = () => (
//     <View style={styles.header}>
//       <View style={styles.headerTop}>
//         <Text style={styles.headerTitle}>Bluetooth Devices</Text>
//         <TouchableOpacity
//           style={[styles.scanButton, isScanning && styles.scanningButton]}
//           onPress={onToggleScan}
//           activeOpacity={0.7}>
//           {isScanning ? (
//             <ActivityIndicator size="small" color="#FFFFFF" />
//           ) : (
//             <Text style={styles.scanButtonText}>
//               {devices.length > 0 ? 'Refresh' : 'Scan'}
//             </Text>
//           )}
//         </TouchableOpacity>
//       </View>

//       {isScanning && (
//         <View style={styles.scanningIndicator}>
//           <ActivityIndicator size="small" color="#2196F3" />
//           <Text style={styles.scanningText}>{scanningMessage}</Text>
//         </View>
//       )}
//     </View>
//   );

//   const renderEmptyState = () => (
//     <View style={styles.emptyContainer}>
//       <Text style={styles.emptyIcon}>📶</Text>
//       <Text style={styles.emptyTitle}>
//         {isScanning ? 'Searching...' : 'No Devices Found'}
//       </Text>
//       <Text style={styles.emptyMessage}>
//         {isScanning ? 'Looking for nearby Bluetooth devices' : emptyMessage}
//       </Text>
//       {!isScanning && (
//         <TouchableOpacity
//           style={styles.retryButton}
//           onPress={onToggleScan}
//           activeOpacity={0.7}>
//           <Text style={styles.retryButtonText}>Start Scanning</Text>
//         </TouchableOpacity>
//       )}
//     </View>
//   );

//   return (
//     <View style={styles.container}>
//       <FlatList
//         data={devices}
//         renderItem={renderDeviceItem}
//         keyExtractor={item => item.id}
//         ListHeaderComponent={renderHeader}
//         ListEmptyComponent={renderEmptyState}
//         refreshControl={
//           <RefreshControl
//             refreshing={refreshing}
//             onRefresh={handleRefresh}
//             colors={['#2196F3']}
//             tintColor="#2196F3"
//           />
//         }
//         contentContainerStyle={[
//           styles.listContent,
//           devices.length === 0 && styles.emptyListContent,
//         ]}
//         showsVerticalScrollIndicator={false}
//       />
//     </View>
//   );
// };

// const styles = StyleSheet.create({
//   container: {
//     flex: 1,
//     backgroundColor: '#F5F5F5',
//   },
//   listContent: {
//     paddingBottom: 20,
//   },
//   emptyListContent: {
//     flexGrow: 1,
//   },
//   header: {
//     backgroundColor: '#FFFFFF',
//     paddingHorizontal: 16,
//     paddingTop: 16,
//     paddingBottom: 12,
//     borderBottomWidth: 1,
//     borderBottomColor: '#E0E0E0',
//     marginBottom: 8,
//   },
//   headerTop: {
//     flexDirection: 'row',
//     justifyContent: 'space-between',
//     alignItems: 'center',
//     marginBottom: 8,
//   },
//   headerTitle: {
//     fontSize: 24,
//     fontWeight: '600',
//     color: '#212121',
//   },
//   scanButton: {
//     backgroundColor: '#2196F3',
//     paddingHorizontal: 16,
//     paddingVertical: 8,
//     borderRadius: 20,
//     minWidth: 80,
//     alignItems: 'center',
//     justifyContent: 'center',
//   },
//   scanningButton: {
//     backgroundColor: '#FF9800',
//   },
//   scanButtonText: {
//     color: '#FFFFFF',
//     fontSize: 14,
//     fontWeight: '600',
//   },
//   scanningIndicator: {
//     flexDirection: 'row',
//     alignItems: 'center',
//     paddingTop: 8,
//   },
//   scanningText: {
//     marginLeft: 8,
//     fontSize: 14,
//     color: '#666666',
//     fontStyle: 'italic',
//   },
//   deviceItem: {
//     backgroundColor: '#FFFFFF',
//     marginHorizontal: 16,
//     marginVertical: 4,
//     borderRadius: 12,
//     elevation: 2,
//     shadowColor: '#000',
//     shadowOffset: {
//       width: 0,
//       height: 1,
//     },
//     shadowOpacity: 0.1,
//     shadowRadius: 2,
//   },
//   connectedDevice: {
//     borderLeftWidth: 4,
//     borderLeftColor: '#4CAF50',
//   },
//   deviceContent: {
//     flexDirection: 'row',
//     padding: 16,
//     alignItems: 'center',
//   },
//   deviceInfo: {
//     flex: 1,
//     flexDirection: 'row',
//     alignItems: 'center',
//   },
//   iconContainer: {
//     position: 'relative',
//     marginRight: 12,
//   },
//   deviceIcon: {
//     fontSize: 32,
//   },
//   pairedIndicator: {
//     position: 'absolute',
//     top: -4,
//     right: -4,
//     backgroundColor: '#FFFFFF',
//     borderRadius: 8,
//     width: 16,
//     height: 16,
//     alignItems: 'center',
//     justifyContent: 'center',
//   },
//   pairedIcon: {
//     fontSize: 10,
//   },
//   deviceDetails: {
//     flex: 1,
//   },
//   deviceName: {
//     fontSize: 16,
//     fontWeight: '600',
//     color: '#212121',
//     marginBottom: 4,
//   },
//   deviceAddress: {
//     fontSize: 12,
//     color: '#757575',
//     fontFamily: 'monospace',
//     marginBottom: 6,
//   },
//   statusContainer: {
//     flexDirection: 'row',
//     alignItems: 'center',
//   },
//   statusBadge: {
//     flexDirection: 'row',
//     alignItems: 'center',
//     marginRight: 12,
//   },
//   statusDot: {
//     width: 8,
//     height: 8,
//     borderRadius: 4,
//     marginRight: 6,
//   },
//   statusText: {
//     fontSize: 12,
//     fontWeight: '500',
//   },
//   pairedText: {
//     fontSize: 11,
//     color: '#4CAF50',
//     backgroundColor: '#E8F5E8',
//     paddingHorizontal: 6,
//     paddingVertical: 2,
//     borderRadius: 4,
//     fontWeight: '500',
//   },
//   deviceActions: {
//     alignItems: 'center',
//   },
//   signalContainer: {
//     alignItems: 'center',
//     marginBottom: 4,
//   },
//   signalBars: {
//     fontSize: 16,
//   },
//   rssiText: {
//     fontSize: 10,
//     color: '#757575',
//     fontFamily: 'monospace',
//   },
//   connectingSpinner: {
//     marginVertical: 4,
//   },
//   chevron: {
//     fontSize: 20,
//     color: '#BDBDBD',
//     marginTop: 4,
//   },
//   emptyContainer: {
//     flex: 1,
//     justifyContent: 'center',
//     alignItems: 'center',
//     paddingHorizontal: 32,
//   },
//   emptyIcon: {
//     fontSize: 64,
//     marginBottom: 16,
//     opacity: 0.5,
//   },
//   emptyTitle: {
//     fontSize: 20,
//     fontWeight: '600',
//     color: '#424242',
//     marginBottom: 8,
//     textAlign: 'center',
//   },
//   emptyMessage: {
//     fontSize: 16,
//     color: '#757575',
//     textAlign: 'center',
//     lineHeight: 22,
//     marginBottom: 24,
//   },
//   retryButton: {
//     backgroundColor: '#2196F3',
//     paddingHorizontal: 24,
//     paddingVertical: 12,
//     borderRadius: 24,
//   },
//   retryButtonText: {
//     color: '#FFFFFF',
//     fontSize: 16,
//     fontWeight: '600',
//   },
// });
// export default BluetoothDeviceList;
// // Usage Example:
// /*
// const App = () => {
//   const [devices, setDevices] = useState([]);
//   const [isScanning, setIsScanning] = useState(false);

//   const handleDevicePress = (device) => {
//     Alert.alert(
//       'Device Selected',
//       `Do you want to connect to ${device.name}?`,
//       [
//         { text: 'Cancel', style: 'cancel' },
//         { text: 'Connect', onPress: () => connectToDevice(device) },
//       ]
//     );
//   };

//   const handleRefresh = async () => {
//     // Implement your Bluetooth scanning logic here
//     console.log('Refreshing device list...');
//   };

//   const handleToggleScan = () => {
//     setIsScanning(!isScanning);
//     // Start/stop Bluetooth scanning
//   };

//   return (
//     <BluetoothDeviceList
//       devices={devices}
//       isScanning={isScanning}
//       onDevicePress={handleDevicePress}
//       onRefresh={handleRefresh}
//       onToggleScan={handleToggleScan}
//       showRSSI={true}
//       showAddress={true}
//     />
//   );
// };
// */
