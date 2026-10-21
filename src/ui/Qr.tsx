// QR codes and links (LINKS.md): showing a link to scan or share, and
// scanning or pasting one.
import React, { useEffect, useMemo, useRef, useState } from 'react';
import { ActivityIndicator, Modal, Platform, Pressable, Share, StyleSheet, Text, View } from 'react-native';
import * as Clipboard from 'expo-clipboard';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { CameraView, useCameraPermissions, type BarcodeScanningResult } from 'expo-camera';
import Svg, { Path } from 'react-native-svg';
import Ionicons from '@expo/vector-icons/Ionicons';
import { hex } from '@/chain/bytes';
import { parseAmount } from '@/chain/format';
import { encodeLink, netOf } from '@/chain/links';
import { UserAccount, useChainInfo } from '@/state/chain';
import type { CoinInfo } from '@/state/coins';
import { Button, Caption, Field, IdChip, Row, Segmented, Sheet } from './components';
import { relativeDuration } from './describe';
import { qrShape } from './qrShape';
import { font, radius, spacing, type, useTheme } from './theme';

/** A QR code, dark on white in both themes so every camera reads it. */
export function QrCode({ value, size = 216 }: { value: string; size?: number }) {
  const shape = useMemo(() => qrShape(value), [value]);
  const quiet = 2;
  const box = shape.size + 2 * quiet;
  return (
    <View
      accessibilityRole="image"
      accessibilityLabel="QR code"
      style={{ alignSelf: 'center', backgroundColor: '#FFFFFF', borderRadius: radius.md, padding: 10, borderWidth: StyleSheet.hairlineWidth, borderColor: '#E5E7EB' }}>
      <Svg width={size} height={size} viewBox={`${-quiet} ${-quiet} ${box} ${box}`}>
        <Path d={shape.path} fill="#111827" />
      </Svg>
    </View>
  );
}

/** A link to hand to someone: its QR code, the link, and buttons to copy it or share it in a message. */
export function LinkShare({ link, qr = true }: { link: string; qr?: boolean }) {
  const t = useTheme();
  const [copied, setCopied] = useState(false);
  return (
    <View style={{ gap: spacing.sm }}>
      {qr ? (
        <View style={{ paddingVertical: spacing.xs }}>
          <QrCode value={link} />
        </View>
      ) : null}
      <Text selectable numberOfLines={3} style={[type.mono, { color: t.muted, fontSize: 11.5, lineHeight: 16 }]}>
        {link}
      </Text>
      <Row>
        <Button
          title={copied ? 'Copied' : 'Copy link'}
          kind="secondary"
          small
          icon={copied ? 'checkmark' : 'copy-outline'}
          style={{ flex: 1 }}
          onPress={async () => {
            await Clipboard.setStringAsync(link);
            setCopied(true);
            setTimeout(() => setCopied(false), 1500);
          }}
        />
        <Button title="Share" kind="secondary" small icon="share-outline" style={{ flex: 1 }} onPress={() => Share.share({ message: link }).catch(() => {})} />
      </Row>
    </View>
  );
}

/**
 * The Receive sheet: pick an account, show its pay link as a QR code to scan
 * and a link to share, and the account to copy. An amount can be asked for,
 * in `coin`, or `amountCoin` when the sheet is for any coin.
 */
export function ReceiveSheet({
  accounts,
  slot,
  onSlot,
  onClose,
  coin,
  amountCoin,
}: {
  accounts: UserAccount[];
  slot: number | null;
  onSlot: (slot: number) => void;
  onClose: () => void;
  coin?: CoinInfo;
  amountCoin?: CoinInfo;
}) {
  const t = useTheme();
  const { info } = useChainInfo();
  const [asking, setAsking] = useState(false);
  const [amount, setAmount] = useState('');
  const account = slot === null ? null : (accounts.find((a) => a.slot === slot) ?? null);
  const accountHex = account ? hex(account.account) : null;
  const priced = coin ?? amountCoin ?? null;
  const asked = (() => {
    if (!priced || !amount.trim()) return null;
    try {
      return parseAmount(amount, priced.config.decimals) > 0n ? amount.trim().replace(/[\s_]/g, '').replace(',', '.') : null;
    } catch {
      return null;
    }
  })();
  const link =
    accountHex && info ? encodeLink({ request: 'pay', net: netOf(info.status.chainId), to: accountHex, coin: coin || asked ? priced?.hex : undefined, amount: asked ?? undefined }) : null;
  const close = () => {
    setAsking(false);
    setAmount('');
    onClose();
  };
  return (
    <Sheet visible={account !== null} onClose={close} title={coin ? `Receive ${coin.config.symbol}` : 'Receive'}>
      {accounts.length > 1 ? (
        <Segmented options={accounts.map((a) => ({ value: String(a.slot), label: a.label }))} value={String(slot ?? 0)} onChange={(v) => onSlot(Number(v))} />
      ) : null}
      {link ? <LinkShare link={link} /> : null}
      {priced ? (
        asking ? (
          <Field
            label={`Ask for an amount (${priced.config.symbol})`}
            value={amount}
            onChangeText={setAmount}
            placeholder="0"
            keyboardType="decimal-pad"
            error={amount.trim() && !asked ? `enter an amount with at most ${priced.config.decimals} decimals` : null}
            hint="The link asks for this amount; whoever pays can change it."
          />
        ) : (
          <Button title="Ask for an amount" kind="ghost" small icon="add" onPress={() => setAsking(true)} />
        )
      ) : null}
      {accountHex ? (
        <View style={{ backgroundColor: t.cardAlt, borderRadius: radius.md, padding: spacing.md, gap: 4 }}>
          <Caption>The account, to copy</Caption>
          <IdChip value={accountHex} full />
        </View>
      ) : null}
      {account?.delay ? <Caption>Deposits are instant; withdrawals from {account.label} wait {relativeDuration(account.delay)}.</Caption> : null}
    </Sheet>
  );
}

/**
 * A full-screen camera that reads QR codes, or a link pasted from the
 * clipboard. `onScan` gets each new code and returns null when it took it
 * (the caller then closes the scanner) or a message to show while scanning
 * goes on.
 */
export function QrScanner({ visible, onClose, onScan, title = 'Scan a code' }: { visible: boolean; onClose: () => void; onScan: (text: string) => string | null; title?: string }) {
  // The camera and the scanner's state exist only while it is open.
  return (
    <Modal visible={visible} animationType="slide" onRequestClose={onClose}>
      {visible ? <Scanner onClose={onClose} onScan={onScan} title={title} /> : null}
    </Modal>
  );
}

function Scanner({ onClose, onScan, title }: { onClose: () => void; onScan: (text: string) => string | null; title: string }) {
  const insets = useSafeAreaInsets();
  const [permission, requestPermission] = useCameraPermissions();
  const [message, setMessage] = useState<string | null>(null);
  const last = useRef<string | null>(null);
  const asked = useRef(false);

  // Opening the scanner is the request to use the camera: ask once, as soon
  // as the current permission is known.
  useEffect(() => {
    if (!permission || asked.current) return;
    asked.current = true;
    if (!permission.granted && permission.canAskAgain) requestPermission();
  }, [permission, requestPermission]);

  const scanned = ({ data }: BarcodeScanningResult) => {
    if (data === last.current) return;
    last.current = data;
    setMessage(onScan(data));
  };
  const paste = async () => {
    const text = (await Clipboard.getStringAsync().catch(() => '')).trim();
    setMessage(text ? onScan(text) : 'Nothing to paste: copy the link first.');
  };

  return (
    <>
      <View style={{ flex: 1, backgroundColor: '#000' }}>
        {permission?.granted ? (
          <CameraView style={StyleSheet.absoluteFill} facing="back" barcodeScannerSettings={{ barcodeTypes: ['qr'] }} onBarcodeScanned={scanned} />
        ) : null}
        <View style={[styles.top, { paddingTop: (Platform.OS === 'web' ? 0 : insets.top) + spacing.md }]}>
          <Text style={styles.title}>{title}</Text>
          <Pressable onPress={onClose} hitSlop={12} accessibilityLabel="Close" style={styles.close}>
            <Ionicons name="close" size={22} color="#fff" />
          </Pressable>
        </View>
        <View style={styles.center}>
          {permission?.granted ? (
            <View style={styles.frame} />
          ) : permission === null ? (
            <ActivityIndicator color="#fff" />
          ) : (
            <View style={{ alignItems: 'center', gap: spacing.md, paddingHorizontal: spacing.xl }}>
              <Ionicons name="camera-outline" size={40} color="#fff" />
              <Text style={styles.text}>
                {permission.canAskAgain ? 'The camera is needed to scan a code.' : 'Camera access is turned off for this app. Turn it on in the system settings to scan codes.'}
              </Text>
              {permission.canAskAgain ? <Button title="Allow camera" onPress={requestPermission} /> : null}
            </View>
          )}
        </View>
        <View style={[styles.bottom, { paddingBottom: insets.bottom + spacing.xl }]}>
          {message ? (
            <View style={styles.message}>
              <Ionicons name="alert-circle-outline" size={18} color="#FCD34D" />
              <Text style={[styles.text, { flex: 1, textAlign: 'left' }]}>{message}</Text>
            </View>
          ) : permission?.granted ? (
            <Text style={styles.text}>Point the camera at a Ting QR code, or paste a link someone sent you.</Text>
          ) : null}
          <Button title="Paste a link" kind="raised" icon="clipboard-outline" onPress={paste} style={{ alignSelf: 'stretch', marginTop: spacing.md }} />
        </View>
      </View>
    </>
  );
}

const styles = StyleSheet.create({
  top: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingHorizontal: spacing.lg, paddingBottom: spacing.md, backgroundColor: 'rgba(0,0,0,0.45)' },
  title: { color: '#fff', fontSize: 17, ...font.bold },
  close: { width: 36, height: 36, borderRadius: 18, alignItems: 'center', justifyContent: 'center', backgroundColor: 'rgba(255,255,255,0.16)' },
  center: { flex: 1, alignItems: 'center', justifyContent: 'center' },
  frame: { width: 240, height: 240, borderRadius: 24, borderWidth: 3, borderColor: 'rgba(255,255,255,0.9)' },
  bottom: { paddingHorizontal: spacing.lg, paddingTop: spacing.md, alignItems: 'center', backgroundColor: 'rgba(0,0,0,0.45)' },
  text: { color: '#fff', fontSize: 15, textAlign: 'center', lineHeight: 21 },
  message: { flexDirection: 'row', gap: 10, alignItems: 'flex-start', maxWidth: 480 },
});
