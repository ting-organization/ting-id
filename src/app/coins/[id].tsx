// One coin as the chain has it, with the coin tools switched on: supply,
// issuance, minter and contract, with Follow, and Mint and Move minting when
// one of your accounts is the minter.
import React from 'react';
import { Text, View } from 'react-native';
import { Redirect, Stack, useLocalSearchParams, useRouter } from 'expo-router';
import { hex } from '@/chain/bytes';
import * as coin from '@/chain/coin';
import { formatAmount } from '@/chain/format';
import { knownCoinsFor } from '@/config/known-coins';
import { useCoinIndex } from '@/state/coinIndex';
import { CoinInfo, useCoinInfos } from '@/state/coins';
import { useStore } from '@/state/store';
import { useWorkspace } from '@/state/workspace';
import { CoinMark } from '@/ui/CoinPicker';
import { amountOf, capped, circulating, hasMinter, minterAccount, minterName, originalOf, supplyModel } from '@/ui/coinFacts';
import { Banner, Button, Caption, Detail, DetailCard, DetailValue, IconButton, IdChip, InkHeader, Loading, Pill, Row, Screen, SectionHeader } from '@/ui/components';
import { useConfirm } from '@/ui/confirm';
import { relativeDuration } from '@/ui/describe';
import { font, spacing, type, useTheme } from '@/ui/theme';

/** Seconds per block the chain aims for. */
const BLOCK_SECONDS = 60;

export default function CoinDetailsScreen() {
  const coinTools = useStore((s) => !!s.settings.coinTools);
  if (!coinTools) return <Redirect href="/" />;
  return <CoinDetails />;
}

function CoinDetails() {
  const t = useTheme();
  const router = useRouter();
  const { id = '' } = useLocalSearchParams<{ id: string }>();
  const w = useWorkspace();
  const followed = useStore((s) => s.settings.coins.includes(id));
  const addCoin = useStore((s) => s.addCoin);
  const removeCoin = useStore((s) => s.removeCoin);
  const confirm = useConfirm();
  const chainHex = w.info ? hex(w.info.status.chainId) : null;
  const listedIds = chainHex ? knownCoinsFor(chainHex).map((k) => k.contract) : [];
  const height = w.info?.status.height;
  // Every coin, to tell whether this one copies another.
  const index = useCoinIndex(chainHex, height);
  const { infos, loading } = useCoinInfos([...new Set([...index.coins, id, ...listedIds])]);
  const c = infos.get(id) ?? null;
  const back = () => (router.canGoBack() ? router.back() : router.replace('/coins'));

  if (!c) {
    return (
      <Screen title="Coin">
        <Stack.Screen options={{ headerShown: false }} />
        {loading ? <Loading /> : <Banner tone="warning">This chain has no such coin, or a block has not included it yet.</Banner>}
        <Button title="Back" kind="secondary" onPress={back} />
      </Screen>
    );
  }

  const listed = listedIds.includes(c.hex);
  const copy = originalOf(
    c,
    [...infos.values()].filter((x): x is CoinInfo => !!x),
    listedIds,
  );
  const mine = minterAccount(c, w.accounts);
  const nextReward = height !== undefined ? coin.blockReward(c.config, c.state.issued, BigInt(height + 1)) : 0n;
  const mined = c.config.initialReward > 0n || c.config.tailReward > 0n;
  const left = capped(c) ? c.config.maxSupply - c.state.issued : null;

  const header = (
    <InkHeader>
      <Row gap={10}>
        <IconButton icon="chevron-back" label="Back" kind="raised" size={36} onPress={back} />
        <Text style={{ color: t.onHeader, fontSize: 15, flex: 1, ...font.bold }} numberOfLines={1}>
          {c.config.name}
        </Text>
      </Row>
      <Row gap={12}>
        <CoinMark coin={c} size={40} />
        <View style={{ flex: 1, gap: 4 }}>
          <Text style={{ color: t.onHeaderMuted, fontSize: 12.5, ...font.semibold }}>
            {c.config.symbol} · {supplyModel(c)}
          </Text>
          <Row gap={6} style={{ flexWrap: 'wrap' }}>
            {listed ? <Pill light small label="listed" tone="success" /> : null}
            {mine ? <Pill light small label="you mint" tone="primary" /> : null}
            {followed ? <Pill light small label="following" /> : null}
          </Row>
        </View>
      </Row>
      <View style={{ gap: 6 }}>
        <Text style={{ color: t.onHeaderMuted, fontSize: 12.5, ...font.semibold }}>Circulating</Text>
        <Row gap={8} style={{ alignItems: 'baseline', flexWrap: 'wrap' }}>
          <Text style={[type.amount, { color: t.onHeader }]}>{formatAmount(circulating(c), c.config.decimals)}</Text>
          <Text style={{ color: t.onHeaderMuted, fontSize: 16, ...font.bold }}>{c.config.symbol}</Text>
        </Row>
        <Text style={{ color: t.onHeaderMuted, fontSize: 12.5, ...font.medium }}>{capped(c) ? `of at most ${amountOf(c, c.config.maxSupply)}` : 'no supply cap'}</Text>
      </View>
      <Row gap={8}>
        {mine ? <Button title="Mint" kind="light" icon="add" onPress={() => router.push({ pathname: '/mint/[id]', params: { id: c.hex } })} style={{ flex: 1 }} /> : null}
        {followed ? (
          <Button
            title="Following"
            kind="raised"
            icon="checkmark"
            style={{ flex: 1 }}
            onPress={async () => {
              const yes = await confirm({
                title: `Unfollow ${c.config.name}?`,
                body: 'It disappears from this wallet. What you hold stays on chain, and you can follow it again from Coins.',
                confirm: 'Unfollow',
                back: 'Keep following',
                danger: true,
              });
              if (yes) removeCoin(c.hex);
            }}
          />
        ) : (
          <Button title="Follow" kind={mine ? 'raised' : 'light'} icon="add" onPress={() => addCoin(c.hex)} style={{ flex: 1 }} />
        )}
      </Row>
    </InkHeader>
  );

  return (
    <Screen header={header}>
      <Stack.Screen options={{ headerShown: false }} />
      {copy ? (
        <Banner tone="danger" icon="warning-outline">
          Not the original: {listedIds.includes(copy.hex) ? 'the listed' : 'an earlier'} coin {copy.config.name} ({copy.config.symbol}) has the same ticker and a name
          like this one. Anyone can create a coin with any name; only the contract ID tells coins apart.
        </Banner>
      ) : null}
      {copy ? <Button title="Open the original" kind="secondary" small onPress={() => router.push({ pathname: '/coins/[id]', params: { id: copy.hex } })} /> : null}

      <SectionHeader title="Supply" />
      <DetailCard>
        <Detail label="Issued" first>
          <DetailValue>{amountOf(c, c.state.issued)}</DetailValue>
        </Detail>
        <Detail label="Burned">
          <DetailValue>{amountOf(c, c.state.burned)}</DetailValue>
        </Detail>
        <Detail label="Supply cap">
          <DetailValue>{capped(c) ? amountOf(c, c.config.maxSupply) : 'None'}</DetailValue>
        </Detail>
        {left !== null ? (
          <Detail label="Left to issue">
            <DetailValue>{amountOf(c, left)}</DetailValue>
          </Detail>
        ) : null}
      </DetailCard>

      <SectionHeader
        title="Minting"
        info="The minter is an account. When it belongs to an identity, the right to mint stays with the identity through lost devices and recovery. From a time-locked account every mint waits out its lock and can be cancelled until then."
      />
      <DetailCard>
        <Detail label="Minter" first>
          <DetailValue>{minterName(c, w.accounts, w.names)}</DetailValue>
        </Detail>
        {mine ? (
          <Detail label="Mints">
            <DetailValue>{mine.delay > 0 ? `wait ${relativeDuration(mine.delay)}, can be cancelled` : 'at once'}</DetailValue>
          </Detail>
        ) : null}
      </DetailCard>
      {!hasMinter(c) ? <Caption>No one can mint {c.config.symbol}: new coins come only from mining rewards, if any.</Caption> : null}
      {mine ? <Button title="Move minting" kind="secondary" icon="key-outline" onPress={() => router.push({ pathname: '/minter/[id]', params: { id: c.hex } })} /> : null}

      <SectionHeader title="Mining" />
      {mined ? (
        <DetailCard>
          <Detail label="Reward per block now" first>
            <DetailValue>{amountOf(c, nextReward)}</DetailValue>
          </Detail>
          <Detail label="Halves every">
            <DetailValue>
              {c.config.halvingInterval > 0n
                ? `${formatAmount(c.config.halvingInterval, 0)} blocks · about ${relativeDuration(c.config.halvingInterval * BigInt(BLOCK_SECONDS))}`
                : 'never'}
            </DetailValue>
          </Detail>
          {c.config.tailReward > 0n ? (
            <Detail label="Least reward">
              <DetailValue>{amountOf(c, c.config.tailReward)}</DetailValue>
            </Detail>
          ) : null}
          <Detail label="Last claimed">
            <DetailValue>{c.state.lastClaimed > c.config.deployHeight ? `block ${c.state.lastClaimed}` : 'never'}</DetailValue>
          </Detail>
        </DetailCard>
      ) : (
        <Caption>Mining pays no {c.config.symbol}.</Caption>
      )}

      <SectionHeader title="Coin" info="The app checks the contract's code before it shows a coin: every coin here runs the same standard coin program, and only its options differ." />
      <DetailCard>
        <Detail label="Decimals" first>
          <DetailValue>{String(c.config.decimals)}</DetailValue>
        </Detail>
        <Detail label="Created">
          <DetailValue>{`block ${c.config.deployHeight}`}</DetailValue>
        </Detail>
        <Detail label="Contract">
          <IdChip value={c.hex} />
        </Detail>
      </DetailCard>
      <View style={{ height: spacing.lg }} />
    </Screen>
  );
}
