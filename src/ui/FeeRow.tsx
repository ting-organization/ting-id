// The fee of an operation as one line: what it pays and from which account,
// with a sheet to change the amount or the paying account.
import React, { ReactNode, useState } from 'react';
import { Text, View } from 'react-native';
import { formatAmount } from '@/chain/format';
import { FEE_INFO, FeeChoice, PayerOption, RUN_INFO } from '@/state/fees';
import type { CoinInfo } from '@/state/coins';
import { Button, Caption, Card, Field, Info, ListItem, Pill, Row, Sheet } from './components';
import { font, spacing, tabular, useTheme } from './theme';

/** The fee, with a scheduled action's run added: one sum in the same coin, else both. */
function feeText(fee: bigint, c: CoinInfo, run?: { amount: bigint; coin: CoinInfo }): string {
  const one = (v: bigint, x: CoinInfo) => `${formatAmount(v, x.config.decimals)} ${x.config.symbol}`;
  if (!run || run.amount === 0n) return one(fee, c);
  return run.coin.hex === c.hex ? one(fee + run.amount, c) : `${one(fee, c)} + ${one(run.amount, run.coin)}`;
}

function balance(o: PayerOption): string {
  return o.available === null ? '…' : `${formatAmount(o.available, o.coin.config.decimals)} ${o.coin.config.symbol}`;
}

/**
 * `boxed`: the line sits in a card of its own. `label`: what the line is
 * called, "Fee" unless said. `run`: what a scheduled action pays again when it
 * runs, shown added to the fee.
 */
export function FeeRow({ fee, boxed, label = 'Fee', run }: { fee: FeeChoice; boxed?: boolean; label?: string; run?: { amount: bigint; coin: CoinInfo } }) {
  const t = useTheme();
  const [open, setOpen] = useState(false);
  const box = (children: ReactNode) => (boxed ? <Card>{children}</Card> : <View style={{ gap: spacing.xs }}>{children}</View>);
  const payer = fee.payer;
  if (!fee.canPay || !payer) return box(<Caption>No account here can pay a fee yet.</Caption>);
  const symbol = payer.coin.config.symbol;
  return box(
    <>
      <Row>
        <Text style={{ color: t.muted, fontSize: 13.5, ...font.medium }}>{label}</Text>
        <Text style={[{ color: t.text, flex: 1, fontSize: 13.5, ...font.bold }, tabular]} numberOfLines={1}>
          {fee.fee > 0n ? `${feeText(fee.fee, payer.coin, run)} · ${payer.account.label}` : 'none'}
        </Text>
        <Info title="Fee" text={run && run.amount > 0n ? `${FEE_INFO} ${RUN_INFO}` : FEE_INFO} />
        <Button title="Change" kind="ghost" small onPress={() => setOpen(true)} />
      </Row>
      {fee.notice ? <Caption style={{ color: t.warning }}>{fee.notice}</Caption> : null}
      {fee.problem ? <Caption style={{ color: t.danger }}>{fee.problem}</Caption> : null}
      <Sheet visible={open} onClose={() => setOpen(false)} title="Fee">
        <Field
          label={`Fee (${symbol})`}
          value={fee.amount}
          onChangeText={fee.setAmount}
          placeholder="0"
          keyboardType="decimal-pad"
          error={fee.problem}
          hint={fee.automatic ? 'Automatic: what recent transactions paid for the same room in a block.' : undefined}
        />
        {!fee.automatic ? <Button title="Use the automatic fee" kind="ghost" small icon="refresh-outline" onPress={fee.useAutomatic} /> : null}
        {fee.bundled ? (
          <Caption>Paid in {symbol} beside this operation. It takes a second signature, so the transaction needs about 60% more room in a block, and pays for it.</Caption>
        ) : null}
        {fee.options.length > 1 ? (
          <>
            <Caption>Paid from</Caption>
            {fee.options.map((o) => {
              const on = o === payer;
              return (
                <ListItem
                  key={`${o.account.slot}:${o.coin.hex}`}
                  title={fee.multiCoin ? `${o.account.label} · ${o.coin.config.symbol}` : o.account.label}
                  subtitle={`${balance(o)} available`}
                  selected={on}
                  right={on ? <Pill label="pays" tone="primary" small /> : null}
                  onPress={() => fee.choose(o)}
                />
              );
            })}
          </>
        ) : null}
        <Button title="Done" onPress={() => setOpen(false)} />
      </Sheet>
    </>,
  );
}
