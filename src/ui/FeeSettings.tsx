// Settings → Fees: the coin every operation pays its fee in, and how much.
import React, { useState } from 'react';
import { feeAmount, useFeeCoin } from '@/state/fees';
import { useStore } from '@/state/store';
import type { useWorkspace } from '@/state/workspace';
import { CoinMark } from './CoinPicker';
import { Button, Card, Field, ListItem, Pill, SectionHeader, Sheet } from './components';

export const FEE_SETTINGS_INFO =
  'Miners take transactions by what they pay, in the coins they accept. Every operation pays this fee in this coin, whatever coin it moves, from Spending unless you choose another account. Sending another coin then takes a second signature, so its transaction needs about 60% more room in a block.';

export function FeeSettings({ w }: { w: ReturnType<typeof useWorkspace> }) {
  const fee = useStore((s) => s.settings.fee);
  const setSettings = useStore((s) => s.setSettings);
  const feeCoin = useFeeCoin(w);
  const [text, setText] = useState(fee);
  const [picking, setPicking] = useState(false);
  if (!feeCoin) return null;
  const symbol = feeCoin.config.symbol;
  const valid = feeAmount(text, feeCoin) !== null;
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
        <Field
          label={`Usual fee (${symbol})`}
          value={text}
          onChangeText={(v) => {
            setText(v);
            if (feeAmount(v, feeCoin) !== null) setSettings({ fee: v.trim() });
          }}
          placeholder="0"
          keyboardType="decimal-pad"
          error={valid ? null : `enter an amount with at most ${feeCoin.config.decimals} decimals`}
          hint="Every operation pays this unless you change it there. 0 for none."
        />
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
