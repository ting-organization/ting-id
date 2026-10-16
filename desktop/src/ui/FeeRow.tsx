// The fee of an operation as one line: what it pays and from which account,
// with a dialog to change the amount or the paying account.
import { ReactNode, useState } from 'react';
import { formatAmount } from '@/core/chain/format';
import type { CoinInfo } from '@/core/state/coins';
import { FEE_INFO, FeeChoice, PayerOption, RUN_INFO } from '@/core/state/fees';
import { Button, Caption, Card, Dialog, Field, Help, ListItem, Pill, Row } from './components';

/** The fee, with a scheduled action's run added: one sum in the same coin, else both. */
function feeText(fee: bigint, c: CoinInfo, run?: { amount: bigint; coin: CoinInfo }): string {
  const one = (v: bigint, x: CoinInfo) => `${formatAmount(v, x.config.decimals)} ${x.config.symbol}`;
  if (!run || run.amount === 0n) return one(fee, c);
  return run.coin.hex === c.hex ? one(fee + run.amount, c) : `${one(fee, c)} + ${one(run.amount, run.coin)}`;
}

function balance(o: PayerOption): string {
  return o.available === null ? '…' : `${formatAmount(o.available, o.coin.config.decimals)} ${o.coin.config.symbol}`;
}

/** `boxed`: the line sits in a card of its own. `run`: what a scheduled action pays again when it runs, shown added to the fee. */
export function FeeRow({ fee, boxed, run }: { fee: FeeChoice; boxed?: boolean; run?: { amount: bigint; coin: CoinInfo } }) {
  const [open, setOpen] = useState(false);
  const box = (children: ReactNode) => (boxed ? <Card>{children}</Card> : <div className="stack" style={{ gap: 4 }}>{children}</div>);
  const payer = fee.payer;
  if (!fee.canPay || !payer) return box(<Caption>No account here can pay a fee yet.</Caption>);
  const symbol = payer.coin.config.symbol;
  return box(
    <>
      <Row between>
        <Row gap={8}>
          <span className="caption" style={{ margin: 0 }}>
            Fee
          </span>
          <span style={{ fontWeight: 600 }}>{fee.fee > 0n ? feeText(fee.fee, payer.coin, run) : 'none'}</span>
          {fee.fee > 0n ? <Caption>from {payer.account.label}</Caption> : null}
          <Help text={run && run.amount > 0n ? `${FEE_INFO} ${RUN_INFO}` : FEE_INFO} />
        </Row>
        <Button title="Change" kind="ghost" small onClick={() => setOpen(true)} />
      </Row>
      {fee.notice ? <Caption style={{ color: 'var(--warning)' }}>{fee.notice}</Caption> : null}
      {fee.problem ? <Caption style={{ color: 'var(--danger)' }}>{fee.problem}</Caption> : null}
      <Dialog open={open} onClose={() => setOpen(false)} title="Fee">
        <Field label={`Fee (${symbol})`} value={fee.amount} onChangeText={fee.setAmount} placeholder="0" error={fee.problem} autoFocus />
        {fee.bundled ? (
          <Caption>Paid in {symbol} beside this operation. It takes a second signature, so the transaction needs about 60% more room in a block.</Caption>
        ) : null}
        {fee.options.length > 1 ? (
          <>
            <span className="field-label">Paid from</span>
            <div className="card flush">
              {fee.options.map((o) => {
                const on = o === payer;
                return (
                  <ListItem
                    key={`${o.account.slot}:${o.coin.hex}`}
                    title={fee.multiCoin ? `${o.account.label} · ${o.coin.config.symbol}` : o.account.label}
                    subtitle={`${balance(o)} available`}
                    selected={on}
                    right={on ? <Pill label="pays" tone="primary" small /> : null}
                    onClick={() => fee.choose(o)}
                  />
                );
              })}
            </div>
          </>
        ) : null}
        <div>
          <Button title="Done" onClick={() => setOpen(false)} />
        </div>
      </Dialog>
    </>,
  );
}
