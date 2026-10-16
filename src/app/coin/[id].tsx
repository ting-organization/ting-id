// One account in one coin, as opened from the wallet: what it holds, its
// scheduled actions and activity; what the coin itself is opens in a sheet.
import React, { useState } from 'react';
import { Text, View } from 'react-native';
import { Stack, useLocalSearchParams, useRouter } from 'expo-router';
import { equal, hex } from '@/chain/bytes';
import * as coin from '@/chain/coin';
import { formatAmount } from '@/chain/format';
import { useActivity } from '@/state/activity';
import { useAccountViews } from '@/state/coins';
import { useStore } from '@/state/store';
import { useWorkspace } from '@/state/workspace';
import { ScheduledSection, scheduledOf } from '@/ui/Accounts';
import { ActivityList } from '@/ui/ActivityList';
import { PendingList } from '@/ui/PendingList';
import { onTheWay, usePending } from '@/state/pending';
import { Banner, Button, Detail, DetailCard, DetailValue, IconButton, IdChip, InkHeader, Loading, Pill, Row, Screen, SectionHeader, Sheet } from '@/ui/components';
import { lockOf } from '@/ui/describe';
import { useConfirm } from '@/ui/confirm';
import { ReceiveSheet } from '@/ui/Qr';
import { font, spacing, type, useTheme } from '@/ui/theme';

export default function CoinScreen() {
  const t = useTheme();
  const router = useRouter();
  const params = useLocalSearchParams<{ id: string; slot?: string }>();
  const w = useWorkspace();
  const c = (params.id && w.coinById.get(params.id)) || null;
  const setActiveSlot = useStore((s) => s.setActiveSlot);
  const setActiveCoin = useStore((s) => s.setActiveCoin);
  const removeCoin = useStore((s) => s.removeCoin);
  const coinTools = useStore((s) => !!s.settings.coinTools);
  // The account the wallet opened; Spending when the link names none.
  const account = w.accounts.find((a) => a.slot === Number(params.slot ?? 0)) ?? w.accounts[0] ?? null;
  const coins = c ? [c] : [];
  const accounts = account ? [account] : [];
  const { views, loading } = useAccountViews(coins, accounts);
  const view = (c && views.get(c.hex)?.[0]) || null;
  const activity = useActivity(w.info?.status.height, w.scope, {
    coins: coins.map((x) => x.id),
    accounts: accounts.map((a) => a.account),
    identity: false,
  });
  const pending = usePending(w.info?.status.height);
  const [receiving, setReceiving] = useState(false);
  const [about, setAbout] = useState(false);
  const confirm = useConfirm();
  const scheduled = scheduledOf(coins, views);

  if (!c || !account) {
    return (
      <Screen title={c?.config.name ?? 'Coin'}>
        <Stack.Screen options={{ headerShown: false }} />
        {w.coinsLoading || !w.user ? <Loading /> : <Banner tone="warning">{c ? 'This account is not available.' : 'This coin is not followed any more.'}</Banner>}
      </Screen>
    );
  }

  const locked = account.delay > 0;
  // This account's submissions in this coin that no block has included yet.
  const accountHex = hex(account.account);
  const waiting = pending.items.filter((p) => p.tx.coin === c.hex && p.tx.account === accountHex);
  const out = onTheWay(waiting, c.hex, accountHex);
  const available = view ? coin.available(view.record) : 0n;
  const reserved = view?.record.locked ?? 0n;
  const decimals = c.config.decimals;
  const symbol = c.config.symbol;

  // Send and Schedule start from this account.
  const send = () => {
    setActiveCoin(c.hex);
    setActiveSlot(account.slot);
    router.push({
      pathname: '/send',
      params: { coin: c.hex, slot: String(account.slot) },
    });
  };

  const header = (
    <InkHeader>
      <Row gap={10}>
        <IconButton icon="chevron-back" label="Back" kind="raised" size={36} onPress={() => (router.canGoBack() ? router.back() : router.replace('/'))} />
        <Text style={{ color: t.onHeader, fontSize: 15, flex: 1, ...font.bold }} numberOfLines={1}>
          {c.config.name} · {account.label}
        </Text>
        <Button title="About" kind="raised" small icon="layers-outline" onPress={() => setAbout(true)} />
      </Row>
      <View style={{ gap: 6 }}>
        <Text style={{ color: t.onHeaderMuted, fontSize: 12.5, ...font.semibold }}>Available</Text>
        <Row gap={8} style={{ alignItems: 'baseline' }}>
          <Text style={[type.amount, { color: t.onHeader }]}>{loading && !view ? '…' : formatAmount(available, decimals)}</Text>
          <Text style={{ color: t.onHeaderMuted, fontSize: 16, ...font.bold }}>{symbol}</Text>
        </Row>
        <Row gap={6} style={{ flexWrap: 'wrap' }}>
          <Pill light small label={locked ? lockOf(account.delay) : 'Instant'} />
          {reserved > 0n ? <Pill light small tone="warning" label={`${formatAmount(reserved, decimals)} reserved`} /> : null}
          {out.count > 0 ? <Pill light small label={`${formatAmount(out.amount, decimals)} on the way`} /> : null}
        </Row>
      </View>
      <Row gap={8}>
        <Button title="Send" kind="light" icon="arrow-up" onPress={send} disabled={!w.user} style={{ flex: 1 }} />
        <Button title="Receive" kind="raised" icon="arrow-down" onPress={() => setReceiving(true)} style={{ flex: 1 }} />
      </Row>
    </InkHeader>
  );

  return (
    <Screen header={header} onEndReached={activity.showMore}>
      <Stack.Screen options={{ headerShown: false }} />
      <ScheduledSection w={w} entries={scheduled} acting={{ coin: c, account }} />

      {w.user ? <PendingList items={waiting} onRetry={pending.retry} onDismiss={pending.dismiss} /> : null}

      {w.user ? (
        <>
          <SectionHeader title="Activity" />
          <ActivityList activity={activity} coins={w.coinById} names={w.names} mine={accounts.map((a) => a.account)} />
        </>
      ) : null}

      <Sheet visible={about} onClose={() => setAbout(false)} title={c.config.name}>
        <DetailCard>
          <Detail label="Symbol" first>
            <DetailValue>
              {symbol} · {decimals} decimals
            </DetailValue>
          </Detail>
          <Detail label="Circulating">
            <DetailValue>{formatAmount(c.state.issued - c.state.burned, decimals)}</DetailValue>
          </Detail>
          <Detail label="Supply cap">
            <DetailValue>{c.config.maxSupply === coin.UNCAPPED ? 'None' : formatAmount(c.config.maxSupply, decimals)}</DetailValue>
          </Detail>
          {c.config.initialReward > 0n ? (
            <Detail label="Block reward">
              <DetailValue>{formatAmount(c.config.initialReward, decimals)}</DetailValue>
            </Detail>
          ) : null}
          <Detail label="Contract">
            <IdChip value={c.hex} />
          </Detail>
        </DetailCard>
        {coinTools ? (
          <Button
            title={equal(c.state.minter, account.account) ? 'Coin details and minting' : 'Coin details'}
            kind="secondary"
            icon="layers-outline"
            onPress={() => {
              setAbout(false);
              router.push({ pathname: '/coins/[id]', params: { id: c.hex } });
            }}
          />
        ) : null}
        <Button
          title="Unfollow this coin"
          kind="danger"
          icon="eye-off-outline"
          onPress={async () => {
            setAbout(false);
            const yes = await confirm({
              title: `Unfollow ${c.config.name}?`,
              body: 'It disappears from this wallet. What you hold stays on chain, and you can follow it again from Settings.',
              confirm: 'Unfollow',
              back: 'Keep following',
              danger: true,
            });
            if (!yes) return;
            removeCoin(c.hex);
            router.back();
          }}
        />
      </Sheet>

      <ReceiveSheet accounts={accounts} slot={receiving ? account.slot : null} onSlot={() => {}} onClose={() => setReceiving(false)} coin={c} />
      <View style={{ height: spacing.lg }} />
    </Screen>
  );
}
