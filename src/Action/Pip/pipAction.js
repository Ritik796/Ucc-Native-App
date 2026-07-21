import { Alert, NativeModules, Platform } from 'react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';

const { PipModule } = NativeModules;

// PiP permission verify nahi kar sakte har baar prompt se, isliye guidance ek hi baar dikhate hain.
const PIP_PROMPT_KEY = '@pip_prompt_shown';

/**
 * Camera / payment / bluetooth jaise sensitive flows ke around PiP ko temporarily block/allow karna.
 * Native MainActivity.onUserLeaveHint() isi flag ko padta hai home-press par.
 */
export const setPipAllowed = allowed => {
  if (Platform.OS === 'android' && PipModule?.setPipAllowed) {
    PipModule.setPipAllowed(!!allowed);
  }
};

/**
 * Tracking start hone par ek baar: agar device PiP support karta hai par per-app permission OFF hai,
 * to user ko guide karo. Best-effort hai — koi bhi step fail ho to app flow block nahi hota.
 */
export const ensurePipPermission = async () => {
  if (Platform.OS !== 'android' || !PipModule) return;

  try {
    const supported = await PipModule.isPipSupported();
    if (!supported) return;

    const granted = await PipModule.isPipPermissionGranted();
    if (granted) return;

    const alreadyShown = await AsyncStorage.getItem(PIP_PROMPT_KEY);
    if (alreadyShown === 'true') return;

    Alert.alert(
      'Floating window allow karein',
      'App ko background mein chhoti floating window (Picture-in-Picture) mein chalane ke liye permission ON karein, taaki doosri app use karte waqt bhi location tracking band na ho.\n\nAgli screen par is app ke liye toggle ON karein.',
      [
        { text: 'Baad mein', style: 'cancel' },
        {
          text: 'Settings kholein',
          onPress: () => PipModule.openPipSettings(),
        },
      ],
      { cancelable: false },
    );

    await AsyncStorage.setItem(PIP_PROMPT_KEY, 'true');
  } catch (e) {
    // Silent best-effort.
  }
};
