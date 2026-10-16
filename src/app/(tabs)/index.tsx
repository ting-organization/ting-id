import React, { useState } from 'react';
import { OTHER_CHAIN_USER } from '@/ui/describe';
import { Pressable, Text, View } from 'react-native';
import { useRouter } from 'expo-router';
import Ionicons from '@expo/vector-icons/Ionicons';
import { hex } from '@/chain/bytes';
import { formatAmount } from '@/chain/format';
import { useActivity } from '@/state/activity';
import { coinGroups, useAccountViews, useDiscoveredCoins } from '@/state/coins';
import { useStore } from '@/state/store';
import { useWorkspace } from '@/state/workspace';
import { ActivityList } from '@/ui/ActivityList';
import { PendingList } from '@/ui/PendingList';
import { onTheWay, usePending } from '@/state/pending';
import { ScheduledSection, scheduledOf } from '@/ui/Accounts';
import { CoinGroups, CoinMark } from '@/ui/CoinPicker';
import { Banner, Button, Caption, Card, IconButton, IconCircle, InkHeader, ListItem, Logo, Row, Screen, SectionHeader, Sheet } from '@/ui/components';
import { ProfileButton } from '@/ui/Profile';
import { QrScanner, ReceiveSheet } from '@/ui/Qr';
import { Welcome } from '@/ui/Welcome';
import { isSignCode } from '@/chain/offchain';
import { isPaymentCode } from '@/chain/request';
import { font, useTheme } from '@/ui/theme';

const SCHEDULED_INFO =
  'Actions of your accounts that run later, in every coin. Check them: anything you did not plan can be cancelled until it runs.';

/** The wallet home: actions, the coins you follow with the accounts that hold each, and what happened. An account opens its own screen. */
export default function WalletScreen() {
  const t = useTheme();
  const router = useRouter();
  const coinTools = useStore((s) => !!s.settings.coinTools);
  const w = useWorkspace();
  const activeSlot = useStore((s) => s.activeSlot);
  const addCoin = useStore((s) => s.addCoin);
  const dismissCoin = useStore((s) => s.dismissCoin);
  const dismissed = useStore((s) => s.settings.dismissed);
  const { views, loading } = useAccountViews(w.coins, w.accounts);
  const [receiveSlot, setReceiveSlot] = useState<number | null>(null);
  const [foundSheet, setFoundSheet] = useState(false);
  const [scanning, setScanning] = useState(false);
  const mine = w.accounts.map((a) => a.account);
  const activity = useActivity(w.info?.status.height, w.scope, { coins: w.coins.map((c) => c.id), identity: true });
  const pending = usePending(w.info?.status.height);
  const excluded = [...w.coins.map((c) => c.hex), ...dismissed, ...(w.info ? [hex(w.info.canonical.registry)] : [])];
  const found = useDiscoveredCoins(w.info?.status.height, w.scope, w.accounts, excluded);

  const selected = w.accounts.find((a) => a.slot === activeSlot) ?? w.accounts[0];
  const groups = coinGroups(w.coins, views);

  const header = (
    <InkHeader>
      <Row style={{ justifyContent: 'space-between' }}>
        <Row gap={8}>
          <Logo size={24} />
          <Text style={{ color: t.onHeader, fontSize: 14, ...font.bold }}>Ting ID</Text>
        </Row>
        <ProfileButton size={34} />
      </Row>
      {w.user ? (
        <Row gap={8} style={{ marginTop: 12 }}>
          <Button title="Send" kind="light" icon="arrow-up" onPress={() => router.push('/send')} disabled={w.coins.length === 0} style={{ flex: 1 }} />
          <Button title="Receive" kind="raised" icon="arrow-down" onPress={() => selected && setReceiveSlot(selected.slot)} disabled={!selected} style={{ flex: 1 }} />
          <IconButton icon="time-outline" label="Schedule" kind="raised" onPress={() => router.push({ pathname: '/send', params: { when: 'later' } })} disabled={w.coins.length === 0} />
          <IconButton icon="scan-outline" label="Scan a code" kind="raised" onPress={() => setScanning(true)} />
        </Row>
      ) : (
        <View style={{ gap: 6 }}>
          <Text style={{ color: t.onHeader, fontSize: 28, letterSpacing: -0.6, ...font.extrabold }}>Welcome</Text>
          <Text style={{ color: t.onHeaderMuted, fontSize: 14, lineHeight: 20, ...font.medium }}>Your money and your identity, kept on this phone.</Text>
        </View>
      )}
    </InkHeader>
  );

  return (
    <Screen header={header} onEndReached={activity.showMore}>
      {w.otherChain ? <Banner tone="warning">{OTHER_CHAIN_USER}</Banner> : null}
      {w.user?.phrase && !w.user.phraseBackedUp ? (
        <Banner tone="warning">
          Write down your recovery phrase: until you do, only this phone can restore {w.user.kind === 'identity' ? 'the identity' : 'the account'}.{' '}
          <Text style={{ textDecorationLine: 'underline' }} onPress={() => router.push({ pathname: '/phrase/[id]', params: { id: w.user!.id } })}>
            Write it down
          </Text>
        </Banner>
      ) : null}
      {w.user?.kind === 'identity' && w.info && !w.otherChain && !w.identity && !w.identityLoading ? (
        <Banner tone="warning">Waiting for the next block to create this identity.</Banner>
      ) : null}
      {!w.user ? <Welcome /> : null}

      {w.user ? (
        <>
          <SectionHeader title="Coins" action={coinTools ? 'All coins' : 'Manage'} onAction={() => router.push(coinTools ? '/coins' : '/settings')} />
          {w.coins.length > 0 ? (
            <CoinGroups
              groups={groups}
              loading={loading}
              onOpen={(c, slot) => router.push({ pathname: '/coin/[id]', params: { id: c.hex, slot: String(slot) } })}
              pendingFor={(c, v) => onTheWay(pending.items, c.hex, hex(v.account.account))}
            />
          ) : (
            <Card>
              <Row>
                <IconCircle name="server-outline" color={t.primary} bg={t.primarySoft} />
                <View style={{ flex: 1 }}>
                  <Text style={{ color: t.text, fontSize: 15, ...font.bold }}>{w.coinsLoading ? 'Loading coins…' : 'No coins followed yet'}</Text>
                  <Caption>Add the coins you use in Settings.</Caption>
                </View>
              </Row>
              {!w.coinsLoading ? <Button title="Add a coin" kind="secondary" small icon="add" onPress={() => router.push('/settings')} /> : null}
            </Card>
          )}
        </>
      ) : null}

      {found.length > 0 ? (
        <Pressable onPress={() => setFoundSheet(true)} style={({ pressed }) => ({ opacity: pressed ? 0.7 : 1 })}>
          <Row style={{ justifyContent: 'center' }} gap={6}>
            <Ionicons name="sparkles-outline" size={14} color={t.muted} />
            <Caption>
              You also hold {found.length} other coin{found.length === 1 ? '' : 's'}
            </Caption>
            <Ionicons name="chevron-forward" size={14} color={t.muted} />
          </Row>
        </Pressable>
      ) : null}

      {w.user ? <ScheduledSection w={w} entries={scheduledOf(w.coins, views)} acting={null} info={SCHEDULED_INFO} /> : null}

      {w.user ? <PendingList items={pending.items} onRetry={pending.retry} onDismiss={pending.dismiss} /> : null}

      {w.user && w.coins.length > 0 ? (
        <>
          <SectionHeader title="Activity" />
          <ActivityList activity={activity} coins={w.coinById} names={w.names} mine={mine} />
        </>
      ) : null}

      <ReceiveSheet accounts={w.accounts} slot={receiveSlot} onSlot={setReceiveSlot} onClose={() => setReceiveSlot(null)} />
      <QrScanner
        visible={scanning}
        onClose={() => setScanning(false)}
        title="Scan a code"
        onScan={(text) => {
          // A site's signing request opens for review; a payment code opens Send.
          if (isSignCode(text)) {
            setScanning(false);
            router.push({ pathname: '/sign', params: { code: text } });
            return null;
          }
          if (isPaymentCode(text)) {
            setScanning(false);
            router.push({ pathname: '/send', params: { code: text } });
            return null;
          }
          return 'That is not a Ting code.';
        }}
      />

      <Sheet visible={foundSheet} onClose={() => setFoundSheet(false)} title="Coins you hold">
        <Caption>Coins your accounts hold that you do not follow.</Caption>
        {found.map((f) => (
          <ListItem
            key={f.info.hex}
            left={<CoinMark coin={f.info} />}
            title={f.info.config.name}
            subtitle={`${formatAmount(f.total, f.info.config.decimals)} ${f.info.config.symbol}`}
            right={
              <Row>
                <Button
                  title="Follow"
                  kind="secondary"
                  small
                  onPress={() => {
                    addCoin(f.info.hex);
                    if (found.length === 1) setFoundSheet(false);
                  }}
                />
                <Button
                  title="Hide"
                  kind="ghost"
                  small
                  onPress={() => {
                    dismissCoin(f.info.hex);
                    if (found.length === 1) setFoundSheet(false);
                  }}
                />
              </Row>
            }
          />
        ))}
      </Sheet>
    </Screen>
  );
}
