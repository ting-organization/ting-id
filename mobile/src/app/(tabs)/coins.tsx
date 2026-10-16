// Every coin on the chain, with the coin tools switched on in Settings: the
// listed ones first, then the ones you follow, then the newest. A coin opens
// its details; Create coin deploys a new one.
import React, { useMemo, useState } from 'react';
import { View } from 'react-native';
import { Redirect, useRouter } from 'expo-router';
import { hex } from '@/chain/bytes';
import { formatAmount } from '@/chain/format';
import { knownCoinsFor } from '@/config/known-coins';
import { useCoinIndex } from '@/state/coinIndex';
import { CoinInfo, useCoinInfos } from '@/state/coins';
import { useStore } from '@/state/store';
import { useWorkspace } from '@/state/workspace';
import { CoinMark } from '@/ui/CoinPicker';
import { capped, circulating, minterAccount, originalOf, supplyModel } from '@/ui/coinFacts';
import { Banner, Button, Caption, Card, Empty, Field, ListItem, Loading, Pill, Row, Screen, Segmented } from '@/ui/components';
import { sentence } from '@/ui/describe';
import { spacing, useTheme } from '@/ui/theme';

type Filter = 'all' | 'following' | 'yours';

export default function CoinsScreen() {
  const coinTools = useStore((s) => !!s.settings.coinTools);
  // Without the coin tools there is no such screen.
  if (!coinTools) return <Redirect href="/" />;
  return <CoinBrowser />;
}

function CoinBrowser() {
  const t = useTheme();
  const router = useRouter();
  const w = useWorkspace();
  const followed = useStore((s) => s.settings.coins);
  const chainHex = w.info ? hex(w.info.status.chainId) : null;
  const height = w.info?.status.height;
  const index = useCoinIndex(chainHex, height);
  // Followed coins count even before the index reaches their block.
  const ids = useMemo(() => [...new Set([...index.coins, ...followed])], [index.coins, followed]);
  const { infos, loading } = useCoinInfos(ids);
  const [query, setQuery] = useState('');
  const [filter, setFilter] = useState<Filter>('all');

  const listedIds = chainHex ? knownCoinsFor(chainHex).map((k) => k.contract) : [];
  // Every coin in the order deployed, to tell an original from its copies.
  const all = ids.map((id) => infos.get(id)).filter((c): c is CoinInfo => !!c);
  const q = query.trim().toLowerCase();
  const rank = (c: CoinInfo) => (listedIds.includes(c.hex) ? 0 : followed.includes(c.hex) ? 1 : 2);
  const rows = [...infos.values()]
    .filter((c): c is CoinInfo => !!c)
    .filter((c) => filter === 'all' || (filter === 'following' ? followed.includes(c.hex) : minterAccount(c, w.accounts) !== null))
    .filter((c) => !q || c.config.name.toLowerCase().includes(q) || c.config.symbol.toLowerCase().includes(q) || c.hex.startsWith(q))
    .sort((a, b) => rank(a) - rank(b) || Number(b.config.deployHeight - a.config.deployHeight));

  return (
    <Screen title="Coins" right={<Button title="Create coin" small icon="add" onPress={() => router.push('/coins/new')} disabled={!w.user || !w.info} />}>
      <Field value={query} onChangeText={setQuery} placeholder="Search by name, symbol or contract ID" autoCapitalize="none" autoCorrect={false} />
      <Segmented
        options={[
          { value: 'all', label: 'All' },
          { value: 'following', label: 'Following' },
          { value: 'yours', label: 'You mint' },
        ]}
        value={filter}
        onChange={setFilter}
      />
      {index.error ? <Banner tone="danger">{sentence(index.error)}</Banner> : null}
      {rows.length > 0 ? (
        <Card padded={false} style={{ gap: 0 }}>
          {rows.map((c, i) => {
            const copy = originalOf(c, all, listedIds);
            const mine = minterAccount(c, w.accounts);
            const supply = `${formatAmount(circulating(c), c.config.decimals)}${capped(c) ? ` of ${formatAmount(c.config.maxSupply, c.config.decimals)}` : ''}`;
            return (
              <View key={c.hex} style={{ borderTopWidth: i === 0 ? 0 : 1, borderTopColor: t.border }}>
                <ListItem
                  left={<CoinMark coin={c} size={36} />}
                  title={`${c.config.name} · ${c.config.symbol}`}
                  subtitle={`${supplyModel(c)} · ${supply}`}
                  right={
                    <Row gap={4} style={{ flexWrap: 'wrap', justifyContent: 'flex-end', maxWidth: 140 }}>
                      {listedIds.includes(c.hex) ? <Pill label="listed" tone="success" small /> : null}
                      {copy ? <Pill label="not the original" tone="danger" small /> : null}
                      {mine ? <Pill label="you mint" tone="primary" small /> : null}
                      {followed.includes(c.hex) && !listedIds.includes(c.hex) ? <Pill label="following" small /> : null}
                    </Row>
                  }
                  chevron
                  onPress={() => router.push({ pathname: '/coins/[id]', params: { id: c.hex } })}
                />
              </View>
            );
          })}
        </Card>
      ) : loading || !index.loaded ? (
        <Loading />
      ) : (
        <Empty title={q || filter !== 'all' ? 'No coins match' : 'No coins yet'} icon="layers-outline" />
      )}
      <Caption style={{ paddingHorizontal: spacing.xs }}>
        {index.reading ? `Reading the chain for coins… block ${index.readTo} of ${height ?? '…'}` : `Every coin created up to block ${index.readTo}.`}
      </Caption>
    </Screen>
  );
}
