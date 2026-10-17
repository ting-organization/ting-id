// Settings → Fees: the coin every operation pays its fee in, and what the
// automatic fee comes to now.
import React, { useState } from 'react';
import { OPERATION_SHARE, SAMPLE, feeFor, medianRate } from '@/chain/feeMarket';
import { formatAmount } from '@/chain/format';
import { useFeeRates } from '@/state/feeRates';
import { useFeeCoin } from '@/state/fees';
import { useStore } from '@/state/store';
import type { useWorkspace } from '@/state/workspace';
import { CoinMark } from './CoinPicker';
import { Button, Caption, Card, ListItem, Pill, SectionHeader, Sheet } from './components';

export const FEE_SETTINGS_INFO =
  'Miners take transactions by what they pay for their room in a block, in the coins they accept. Every operation pays its fee in this coin, whatever coin it moves, from Spending unless you choose another account. The amount follows what recent transactions paid for the same room; each operation shows it, and you can change it there.';

export function FeeSettings({ w }: { w: ReturnType<typeof useWorkspace> }) {
  const setSettings = useStore((s) => s.setSettings);
  const feeCoin = useFeeCoin(w);
  const rates = useFeeRates(w.node, w.info?.status.chainId ?? null, w.info?.status.height ?? null);
  const [picking, setPicking] = useState(false);
  if (!feeCoin) return null;
  const symbol = feeCoin.config.symbol;
  const rate = rates ? medianRate(rates, feeCoin.hex) : null;
  const paying = rates ? Math.min(SAMPLE, rates.filter((r) => r.coin === feeCoin.hex).length) : 0;
  const amount = (share: number) => `${formatAmount(feeFor(rate, share), feeCoin.config.decimals)} ${symbol}`;
  return (
    <>
      <SectionHeader title="Fees" info={FEE_SETTINGS_INFO} />
      <Card padded={false} style={{ gap: 0 }}>
        <ListItem
          left={<CoinMark coin={feeCoin} size={36} />}
          title="Fee coin"
          subtitle={`${feeCoin.config.name} · ${symbol}`}
          right={w.coins.length > 1 ? <Button title="Change" kind="ghost" small onPress={() => setPicking(true)} /> : null}
        />
      </Card>
      <Card>
        <Caption>
          {rates === null
            ? 'Reading what recent transactions paid…'
            : paying > 0
              ? `Automatic, from the median of the last ${paying} paying transaction${paying === 1 ? '' : 's'}: a payment now pays ${amount(OPERATION_SHARE.own)}; a payment in another coin, or an identity change, ${amount(OPERATION_SHARE.bundled)}.`
              : `Automatic. No recent transaction paid a fee in ${symbol}, so each pays the smallest amount, ${amount(OPERATION_SHARE.own)}, which still goes before those that pay nothing.`}
        </Caption>
      </Card>
      <Sheet visible={picking} onClose={() => setPicking(false)} title="Fee coin">
        {w.coins.map((c) => {
          const on = c.hex === feeCoin.hex;
          return (
            <ListItem
              key={c.hex}
              left={<CoinMark coin={c} size={36} />}
              title={c.config.name}
              subtitle={c.config.symbol}
              selected={on}
              right={on ? <Pill label="pays fees" tone="primary" small /> : null}
              onPress={() => {
                setSettings({ feeCoin: c.hex });
                setPicking(false);
              }}
            />
          );
        })}
      </Sheet>
    </>
  );
}
