import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  Modal,
  View,
  Text,
  ScrollView,
  TouchableOpacity,
  StyleSheet,
  AppState,
  Platform,
  NativeModules,
} from 'react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';

const { BatteryOptimizationModule, PipModule } = NativeModules;

// OEMs jinpe apna background-kill engine hota hai (Vivo abe/pem, MIUI, ColorOS...). Inpe extra
// manual steps chahiye — Android API se ye set/verify nahi ho sakte.
const AGGRESSIVE_OEMS = [
  'xiaomi', 'redmi', 'poco',
  'oppo', 'realme', 'oneplus',
  'vivo', 'iqoo',
  'huawei', 'honor',
  'letv', 'meizu',
  // Transsion (itel/Tecno/Infinix) — sabse aggressive + low-end (Android Go), background apps ko
  // strong throttle/kill karta hai. Samsung bhi (Sleeping apps / Deep sleep). Inke chhutne se
  // onboarding hi nahi khulti thi (itel A05s pe yahi hua — GPS throttle → sparse points → jumps).
  'itel', 'tecno', 'infinix', 'transsion',
  'samsung',
];

const DONE_KEY = '@perm_onboarding_done';

// OEM-specific manual steps. Har step: id, title, steps[], opener ('autostart' | 'battery' | null).
const oemSteps = (m) => {
  if (m.includes('vivo') || m.includes('iqoo')) {
    return [
      {
        id: 'autostart',
        title: 'Autostart ON karein',
        steps: ['"Kholo" dabayein', 'List me is app ka toggle ON karein'],
        opener: 'autostart',
      },
      {
        id: 'highpower',
        title: 'High background power → Allow',
        steps: [
          '"Kholo" se Battery settings khulegi',
          'Usme "High background power consumption" dhoondhein',
          'Is app ko Allow / "Don\'t restrict" karein',
        ],
        opener: 'battery',
      },
      {
        id: 'lockrecents',
        title: 'App ko Recents me LOCK karein',
        steps: [
          'Recent apps kholein',
          'Is app ke card ko neeche kheenchein',
          'Lock icon (🔒) dabayein',
        ],
        opener: null,
      },
    ];
  }
  if (m.includes('xiaomi') || m.includes('redmi') || m.includes('poco')) {
    return [
      { id: 'autostart', title: 'Autostart ON karein', steps: ['"Kholo" dabayein', 'App ka toggle ON karein'], opener: 'autostart' },
      { id: 'highpower', title: 'Battery → No restrictions', steps: ['"Kholo" se Battery settings', 'Is app → "No restrictions"'], opener: 'battery' },
      { id: 'lockrecents', title: 'Recents me app LOCK karein', steps: ['Recents kholein', 'App card neeche kheench ke LOCK karein'], opener: null },
    ];
  }
  if (m.includes('oppo') || m.includes('realme') || m.includes('oneplus')) {
    return [
      { id: 'autostart', title: 'Auto Launch ON karein', steps: ['"Kholo" dabayein', 'App ka toggle ON karein'], opener: 'autostart' },
      { id: 'highpower', title: 'Battery → Allow background', steps: ['"Kholo" se Battery settings', 'Is app → "Allow background activity"'], opener: 'battery' },
      { id: 'lockrecents', title: 'Recents me app LOCK karein', steps: ['Recents kholein', 'App card LOCK karein'], opener: null },
    ];
  }
  if (m.includes('huawei') || m.includes('honor')) {
    return [
      { id: 'autostart', title: 'Auto-launch + Run in background', steps: ['"Kholo" dabayein', 'Manage manually → Auto-launch + Run in background ON'], opener: 'autostart' },
      { id: 'highpower', title: 'Battery → Don\'t optimize', steps: ['"Kholo" se Battery settings', 'Is app → Don\'t optimize'], opener: 'battery' },
    ];
  }
  if (m.includes('samsung')) {
    return [
      { id: 'highpower', title: 'Battery → Unrestricted', steps: ['"Kholo" se Battery settings', 'Is app → Battery → "Unrestricted"'], opener: 'battery' },
      { id: 'nosleep', title: 'Sleeping apps se HATAO', steps: ['Settings → Battery → "Background usage limits"', 'Is app ko "Sleeping/Deep sleeping apps" se hatao', '"Never sleeping apps" me add karo'], opener: null },
    ];
  }
  if (m.includes('itel') || m.includes('tecno') || m.includes('infinix') || m.includes('transsion')) {
    // Transsion (itel/Tecno/Infinix) — aksar low-end Android Go, alag battery manager ("Power Master").
    return [
      { id: 'autostart', title: 'Auto-start / Background ON', steps: ['"Kholo" dabayein', 'Is app ka auto-start / background toggle ON karein'], opener: 'autostart' },
      { id: 'highpower', title: 'Battery → No restriction', steps: ['"Kholo" se Battery settings', 'Is app → "No restriction" / "Allow background"', 'Power Master / battery saver me app ko allow karo'], opener: 'battery' },
      { id: 'lockrecents', title: 'Recents me app LOCK karein', steps: ['Recent apps kholein', 'App card ko LOCK karo (jahan possible ho)'], opener: null },
    ];
  }
  return [
    { id: 'autostart', title: 'Auto Start ON karein', steps: ['"Kholo" dabayein', 'App ka toggle ON karein'], opener: 'autostart' },
    { id: 'highpower', title: 'Battery → Unrestricted', steps: ['"Kholo" se Battery settings', 'Is app → Unrestricted'], opener: 'battery' },
  ];
};

const PermissionOnboarding = ({ onDone }) => {
  const [visible, setVisible] = useState(false);
  const [batteryOk, setBatteryOk] = useState(false);
  const [steps, setSteps] = useState([]);
  const [acked, setAcked] = useState({}); // manual acknowledgements { id: true }
  const finishedRef = useRef(false);

  const done = useCallback(async (opened) => {
    if (finishedRef.current) return;
    finishedRef.current = true;
    try { PipModule?.setPipAllowed(true); } catch (e) {}
    if (opened) {
      try { await AsyncStorage.setItem(DONE_KEY, 'true'); } catch (e) {}
    }
    setVisible(false);
    onDone?.();
  }, [onDone]);

  // Mount: decide whether to show
  useEffect(() => {
    let mounted = true;
    (async () => {
      if (Platform.OS !== 'android' || !BatteryOptimizationModule) { onDone?.(); return; }
      try {
        const m = ((await BatteryOptimizationModule.getManufacturer()) || '').toLowerCase();
        const battery = await BatteryOptimizationModule.isIgnoringBatteryOptimizations();
        const wasDone = await AsyncStorage.getItem(DONE_KEY);
        const isAggressive = AGGRESSIVE_OEMS.some((x) => m.includes(x));
        if (!mounted) return;

        // Skip: pehle complete ho chuka AUR battery abhi bhi theek hai (non-OEM ya sab set).
        if (wasDone === 'true' && battery) { onDone?.(); return; }
        // Non-aggressive OEM + battery already theek -> kuch dikhane ki zaroorat nahi.
        if (!isAggressive && battery) { onDone?.(); return; }

        setBatteryOk(battery);
        setSteps(isAggressive ? oemSteps(m) : []);
        // Onboarding ke dauraan PiP band rakho (warna permission dialog par app shrink ho jaati hai).
        try { PipModule?.setPipAllowed(false); } catch (e) {}
        setVisible(true);
      } catch (e) {
        onDone?.();
      }
    })();
    return () => { mounted = false; };
  }, [onDone]);

  // Settings se wapas aane par battery status refresh karo (live tick).
  useEffect(() => {
    const sub = AppState.addEventListener('change', (s) => {
      if (s === 'active' && visible && BatteryOptimizationModule) {
        BatteryOptimizationModule.isIgnoringBatteryOptimizations()
          .then((b) => setBatteryOk(!!b))
          .catch(() => {});
      }
    });
    return () => sub.remove();
  }, [visible]);

  const openFor = (opener) => {
    try {
      if (opener === 'autostart') BatteryOptimizationModule.openAutoStartSettings();
      else if (opener === 'battery') BatteryOptimizationModule.openBatterySettings();
    } catch (e) {}
  };

  const allAcked = steps.every((s) => acked[s.id]);
  const canContinue = batteryOk && allAcked;

  return (
    <Modal visible={visible} animationType="slide" transparent={false} onRequestClose={() => {}}>
      <View style={styles.container}>
        <View style={styles.header}>
          <Text style={styles.title}>Location band na ho — setup</Text>
          <Text style={styles.subtitle}>
            Aapke phone ka power manager app ko band kar deta hai jisse location ruk jaati hai. Neeche di
            settings ON karein (ek-baar ka setup).
          </Text>
        </View>

        <ScrollView style={styles.body} contentContainerStyle={styles.bodyContent}>
          {/* Battery — auto-verified */}
          <View style={styles.card}>
            <View style={styles.cardTop}>
              <Text style={styles.cardTitle}>1. Battery optimization OFF</Text>
              <View style={[styles.pill, batteryOk ? styles.pillOk : styles.pillPending]}>
                <Text style={styles.pillText}>{batteryOk ? '✓ Done' : 'Pending'}</Text>
              </View>
            </View>
            <Text style={styles.cardDesc}>App ko background me chalne ki permission.</Text>
            {!batteryOk && (
              <TouchableOpacity
                style={styles.actionBtn}
                onPress={() => {
                  try { BatteryOptimizationModule.requestIgnoreBatteryOptimizations(); } catch (e) {}
                }}
              >
                <Text style={styles.actionBtnText}>Allow karein</Text>
              </TouchableOpacity>
            )}
          </View>

          {/* OEM manual steps */}
          {steps.map((s, i) => (
            <View style={styles.card} key={s.id}>
              <View style={styles.cardTop}>
                <Text style={styles.cardTitle}>{i + 2}. {s.title}</Text>
                <View style={[styles.pill, acked[s.id] ? styles.pillOk : styles.pillPending]}>
                  <Text style={styles.pillText}>{acked[s.id] ? '✓ Done' : 'Pending'}</Text>
                </View>
              </View>
              {s.steps.map((line, idx) => (
                <Text style={styles.step} key={idx}>• {line}</Text>
              ))}
              <View style={styles.btnRow}>
                {s.opener && (
                  <TouchableOpacity style={styles.openBtn} onPress={() => openFor(s.opener)}>
                    <Text style={styles.openBtnText}>Kholo</Text>
                  </TouchableOpacity>
                )}
                <TouchableOpacity
                  style={[styles.ackBtn, acked[s.id] && styles.ackBtnOn]}
                  onPress={() => setAcked((p) => ({ ...p, [s.id]: !p[s.id] }))}
                >
                  <Text style={[styles.ackBtnText, acked[s.id] && styles.ackBtnTextOn]}>
                    {acked[s.id] ? '✓ Ho gaya' : 'Ho gaya?'}
                  </Text>
                </TouchableOpacity>
              </View>
            </View>
          ))}
        </ScrollView>

        <View style={styles.footer}>
          {!canContinue && (
            <Text style={styles.footerHint}>
              {!batteryOk ? 'Battery permission zaroori hai' : 'Sab steps "Ho gaya" mark karein'}
            </Text>
          )}
          <TouchableOpacity
            style={[styles.continueBtn, !canContinue && styles.continueBtnDisabled]}
            disabled={!canContinue}
            onPress={() => done(true)}
          >
            <Text style={styles.continueText}>Aage badhein</Text>
          </TouchableOpacity>
          <TouchableOpacity onPress={() => done(false)}>
            <Text style={styles.skip}>Abhi skip karein</Text>
          </TouchableOpacity>
        </View>
      </View>
    </Modal>
  );
};

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#0f172a' },
  header: { paddingHorizontal: 20, paddingTop: 24, paddingBottom: 12 },
  title: { fontSize: 22, fontWeight: '700', color: '#fff' },
  subtitle: { fontSize: 14, color: '#cbd5e1', marginTop: 8, lineHeight: 20 },
  body: { flex: 1 },
  bodyContent: { paddingHorizontal: 16, paddingBottom: 16 },
  card: { backgroundColor: '#1e293b', borderRadius: 14, padding: 16, marginTop: 12 },
  cardTop: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  cardTitle: { fontSize: 16, fontWeight: '600', color: '#fff', flex: 1, paddingRight: 8 },
  cardDesc: { fontSize: 13, color: '#94a3b8', marginTop: 6 },
  step: { fontSize: 13, color: '#cbd5e1', marginTop: 6, lineHeight: 19 },
  pill: { paddingHorizontal: 10, paddingVertical: 4, borderRadius: 20 },
  pillOk: { backgroundColor: '#16a34a' },
  pillPending: { backgroundColor: '#475569' },
  pillText: { color: '#fff', fontSize: 12, fontWeight: '600' },
  actionBtn: { backgroundColor: '#22c55e', borderRadius: 10, paddingVertical: 12, alignItems: 'center', marginTop: 12 },
  actionBtnText: { color: '#062e12', fontSize: 15, fontWeight: '700' },
  btnRow: { flexDirection: 'row', marginTop: 12, gap: 10 },
  openBtn: { backgroundColor: '#3b82f6', borderRadius: 10, paddingVertical: 10, paddingHorizontal: 20 },
  openBtnText: { color: '#fff', fontSize: 14, fontWeight: '600' },
  ackBtn: { borderWidth: 1.5, borderColor: '#475569', borderRadius: 10, paddingVertical: 10, paddingHorizontal: 18, flex: 1, alignItems: 'center' },
  ackBtnOn: { backgroundColor: '#16a34a', borderColor: '#16a34a' },
  ackBtnText: { color: '#cbd5e1', fontSize: 14, fontWeight: '600' },
  ackBtnTextOn: { color: '#fff' },
  footer: { padding: 16, borderTopWidth: 1, borderTopColor: '#1e293b' },
  footerHint: { color: '#f59e0b', fontSize: 13, textAlign: 'center', marginBottom: 8 },
  continueBtn: { backgroundColor: '#22c55e', borderRadius: 12, paddingVertical: 15, alignItems: 'center' },
  continueBtnDisabled: { backgroundColor: '#334155' },
  continueText: { color: '#fff', fontSize: 16, fontWeight: '700' },
  skip: { color: '#64748b', fontSize: 13, textAlign: 'center', marginTop: 12 },
});

export default PermissionOnboarding;
