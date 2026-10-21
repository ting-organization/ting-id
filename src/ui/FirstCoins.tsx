// For an identity made on this phone that holds no coins yet: a create link
// to hand to someone who has some (LINKS.md). They send coins to start with,
// and create the identity too if no block has yet.
import React, { useState } from 'react';
import { View } from 'react-native';
import { unhex } from '@/chain/bytes';
import { encodeLink } from '@/chain/links';
import type { CoinInfo } from '@/state/coins';
import type { User } from '@/state/types';
import { Button, Caption, Card, IconCircle, Row, Sub } from './components';
import { LinkShare } from './Qr';
import { useTheme } from './theme';

export function FirstCoins({ user, net, coin }: { user: User; net: string; coin: CoinInfo | null }) {
  const t = useTheme();
  const [open, setOpen] = useState(false);
  if (!user.creation) return null;
  const link = encodeLink({ request: 'create', net, input: unhex(user.creation), name: user.name, coin: coin?.hex });
  const symbol = coin?.config.symbol ?? 'coins';
  return (
    <Card>
      <Row gap={12} style={{ alignItems: 'flex-start' }}>
        <IconCircle name="gift-outline" color={t.accent} bg={t.accentSoft} />
        <View style={{ flex: 1, gap: 2 }}>
          <Sub>Get your first coins</Sub>
          <Caption>
            Ask someone who has {symbol} to scan this or open the link in Ting ID. They can send you some to start with, and set up your identity if no block has yet.
          </Caption>
        </View>
      </Row>
      {open ? <LinkShare link={link} /> : <Button title="Show the link" kind="secondary" small icon="qr-code-outline" onPress={() => setOpen(true)} />}
    </Card>
  );
}
