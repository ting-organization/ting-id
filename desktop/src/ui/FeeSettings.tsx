// Settings → Fees: the coin every operation pays its fee in, and how much.
import { useState } from 'react';
import { feeAmount, useFeeCoin } from '@/core/state/fees';
import { useStore } from '@/core/state/store';
import type { useWorkspace } from '@/core/state/workspace';
import { CoinBadge } from './CoinPicker';
import { Button, Dialog, Field, ListItem, Panel, Pill } from './components';

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
      <Panel title="Fees" info={FEE_SETTINGS_INFO}>
        <ListItem
          first
          left={<CoinBadge coin={feeCoin} size={28} />}
          title="Fee coin"
          subtitle={`${feeCoin.config.name} · ${symbol}`}
          right={w.coins.length > 1 ? <Button title="Change" kind="ghost" small onClick={() => setPicking(true)} /> : null}
        />
        <div className="stack" style={{ padding: 16, borderTop: '1px solid var(--border)' }}>
          <Field
            label={`Usual fee (${symbol})`}
            value={text}
            onChangeText={(v) => {
              setText(v);
              if (feeAmount(v, feeCoin) !== null) setSettings({ fee: v.trim() });
            }}
            placeholder="0"
            error={valid ? null : `enter an amount with at most ${feeCoin.config.decimals} decimals`}
            hint="Every operation pays this unless you change it there. 0 for none."
          />
        </div>
      </Panel>
      <Dialog open={picking} onClose={() => setPicking(false)} title="Fee coin">
        <div className="card flush">
          {w.coins.map((c, i) => {
            const on = c.hex === feeCoin.hex;
            return (
              <ListItem
                key={c.hex}
                first={i === 0}
                left={<CoinBadge coin={c} size={28} />}
                title={c.config.name}
                subtitle={c.config.symbol}
                selected={on}
                right={on ? <Pill label="pays fees" tone="primary" small /> : null}
                onClick={() => {
                  setSettings({ feeCoin: c.hex });
                  setPicking(false);
                }}
              />
            );
          })}
        </div>
      </Dialog>
    </>
  );
}
