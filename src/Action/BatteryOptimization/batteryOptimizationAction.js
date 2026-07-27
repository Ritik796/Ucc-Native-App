import { Alert, AppState, NativeModules, Platform } from 'react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';

const { BatteryOptimizationModule } = NativeModules;

// OEMs jinpe Auto Start / Auto Launch gatekeeper hota hai. Inpe extra guidance dikhate hain.
const AGGRESSIVE_OEMS = [
  'xiaomi', 'redmi', 'poco',
  'oppo', 'realme', 'oneplus',
  'vivo', 'iqoo',
  'huawei', 'honor',
  'letv', 'meizu',
  // Transsion (itel/Tecno/Infinix) + Samsung — ye bhi background apps aggressive throttle/kill karte hain.
  'itel', 'tecno', 'infinix', 'transsion',
  'samsung',
];

// Auto Start ko verify nahi kar sakte (koi API nahi), isliye prompt ek baar hi dikhate hain.
const AUTOSTART_PROMPT_KEY = '@autostart_prompt_shown';

/**
 * App start par call karein. Do cheezein ensure karta hai taaki location service na maare:
 *  1. Battery Optimization OFF (har device par check + system dialog).
 *  2. Aggressive OEMs par Auto Start screen kholna (best-effort, ek baar guide).
 */
export const ensureBackgroundExecution = async () => {
  if (Platform.OS !== 'android' || !BatteryOptimizationModule) return;

  try {
    const ignoring = await BatteryOptimizationModule.isIgnoringBatteryOptimizations();
    if (!ignoring) {
      Alert.alert(
        'Background location allow karein',
        'Location tracking band na ho, iske liye is app ke liye Battery Optimization OFF karna zaruri hai.\n\nAgli screen par is app ke liye "Don\'t optimize / Allow" chunein.',
        [
          {
            text: 'Allow karein',
            onPress: () => {
              BatteryOptimizationModule.requestIgnoreBatteryOptimizations();
              // Battery ka SYSTEM dialog upar aata hai; agar OEM guidance turant dikhaein to wo us
              // dialog ke peeche dab ke KHO jaati hai (fresh tester ko "High background power" wale
              // critical steps kabhi nahi dikhte). Isliye guidance ko tab dikhao jab user battery
              // dialog se WAPAS foreground aaye.
              promptAutoStartOnReturn();
            },
          },
        ],
        { cancelable: false },
      );
    } else {
      // Battery already theek hai -> sirf OEM auto-start guidance (agar pehle nahi dikhaya).
      maybePromptAutoStart();
    }
  } catch (e) {
    // Silent: ye ek best-effort reliability helper hai, app flow block nahi karna.
  }
};

/**
 * OEM-specific extra steps. Battery-optimization exempt hone ke BAAWAJOOD ye OEM apne alag engine
 * (jaise Vivo abe/pem) se app maar dete hain — inhe sirf user manually ON kar sakta hai, koi API nahi.
 * Isliye har aggressive OEM ke liye exact steps text me batate hain.
 */
const oemGuidanceSteps = (manufacturer) => {
  const m = manufacturer;
  if (m.includes('vivo') || m.includes('iqoo')) {
    return (
      '1. Autostart / Background startup → is app ke liye ON karein\n' +
      '2. Settings → Battery → "High background power consumption" → is app ko allow karein\n' +
      '3. Recent apps kholein → is app ke card ko neeche kheenchein → LOCK icon dabayein\n' +
      '4. AI sleep / Sleep mode ON ho to is app ke liye OFF karein'
    );
  }
  if (m.includes('xiaomi') || m.includes('redmi') || m.includes('poco')) {
    return (
      '1. Autostart → is app ke liye ON karein\n' +
      '2. Battery saver → is app → "No restrictions"\n' +
      '3. Recent apps → app card ko neeche kheench ke LOCK karein'
    );
  }
  if (m.includes('oppo') || m.includes('realme') || m.includes('oneplus')) {
    return (
      '1. Auto Launch / Startup manager → ON karein\n' +
      '2. Battery → is app → "Allow background activity" / "Don\'t optimize"\n' +
      '3. Recent apps → app card LOCK karein'
    );
  }
  if (m.includes('huawei') || m.includes('honor')) {
    return (
      '1. App launch → is app ko "Manage manually" karein aur Auto-launch + Run in background ON karein\n' +
      '2. Battery → is app → "Don\'t optimize"'
    );
  }
  // Generic aggressive OEM (letv/meizu/samsung etc.)
  return (
    '1. Auto Start / Background startup → ON karein\n' +
    '2. Battery → is app → "Unrestricted / Don\'t optimize"\n' +
    '3. Recent apps → app card ko LOCK karein (jahan possible ho)'
  );
};

/**
 * OEM guidance ko battery SYSTEM dialog ke BAAD dikhata hai. Battery dialog app ko background bhej
 * deta hai; jab user wapas foreground aaye (background -> active) tab guidance dikhao taaki wo dialog
 * ke peeche dab ke kho na jaaye. Fallback: agar system dialog trigger hi na ho (kuch OEM), 6s baad
 * seedhe dikha do — warna guidance kabhi nahi dikhegi.
 */
const promptAutoStartOnReturn = () => {
  let sawBackground = false;
  let done = false;

  const finish = () => {
    if (done) return;
    done = true;
    try { sub.remove(); } catch (e) {}
    // Chhota delay taaki foreground transition settle ho jaaye, phir alert reliably dikhe.
    setTimeout(() => { maybePromptAutoStart(); }, 500);
  };

  const sub = AppState.addEventListener('change', (state) => {
    if (state === 'background' || state === 'inactive') {
      sawBackground = true;
    } else if (state === 'active' && sawBackground) {
      finish();
    }
  });

  // Fallback: agar 6s me app background gaya hi nahi (system dialog nahi khula), to bhi guidance dikha do.
  setTimeout(() => {
    if (!sawBackground) finish();
  }, 6000);
};

// Ek hi power-save session me baar-baar alert na dikhe iske liye.
let powerSaveWarned = false;

/**
 * Power Saving / Battery Saver mode ON ho to user ko warn karo — is mode me OEM background apps kill/
 * throttle karta hai aur location ruk sakti hai. Off hone par flag reset (agli baar phir warn ho sake).
 * App foreground aane par call karein.
 */
export const warnIfPowerSaveMode = async () => {
  if (Platform.OS !== 'android' || !BatteryOptimizationModule?.isPowerSaveMode) return;
  try {
    const on = await BatteryOptimizationModule.isPowerSaveMode();
    if (on && !powerSaveWarned) {
      powerSaveWarned = true;
      Alert.alert(
        'Power Saving Mode ON hai',
        'Aapka phone Power Saving / Battery Saver mode me hai. Is mode me location beech-beech me ruk sakti hai.\n\nBehtar tracking ke liye Power Saving OFF karein.',
        [{ text: 'Theek hai', style: 'cancel' }],
        { cancelable: true },
      );
    } else if (!on) {
      powerSaveWarned = false;
    }
  } catch (e) {
    // Silent
  }
};

const maybePromptAutoStart = async () => {
  try {
    const manufacturer = (
      (await BatteryOptimizationModule.getManufacturer()) || ''
    ).toLowerCase();

    const isAggressiveOem = AGGRESSIVE_OEMS.some(m => manufacturer.includes(m));
    if (!isAggressiveOem) return;

    const alreadyShown = await AsyncStorage.getItem(AUTOSTART_PROMPT_KEY);
    if (alreadyShown === 'true') return;

    Alert.alert(
      'Location band na ho — ye settings ON karein',
      'Sirf battery permission kaafi nahi hai. Aapke phone ka apna power manager app ko band kar deta hai ' +
        'jisse screen off par location ruk jaati hai. Neeche di gayi settings ON karein:\n\n' +
        oemGuidanceSteps(manufacturer),
      [
        // "Baad mein" par flag SET nahi karte — taaki jab tak worker ek baar settings na khole,
        // har launch par dobara yaad dilaya jaaye. (Pehle flag alert dikhte hi set ho jaata tha,
        // isliye "Baad mein" dabane wale worker ko phir kabhi nahi poochha jaata tha.)
        { text: 'Baad mein', style: 'cancel' },
        {
          text: 'Settings kholein',
          onPress: () => {
            BatteryOptimizationModule.openAutoStartSettings();
            // Flag sirf tab set karo jab worker actually settings khole.
            AsyncStorage.setItem(AUTOSTART_PROMPT_KEY, 'true').catch(() => {});
          },
        },
      ],
      { cancelable: false },
    );
  } catch (e) {
    // Silent
  }
};
