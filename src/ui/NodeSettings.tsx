// Settings → Nodes: the nodes the app uses as one, each one's state, and
// adding or removing them.
import React, { useState } from 'react';
import { Text, View } from 'react-native';
import { hex } from '@/chain/bytes';
import { nodeAddress, remoteHttp } from '@/chain/pool';
import { useNodes } from '@/state/chain';
import { DEFAULT_NODE_URL, useStore } from '@/state/store';
import type { useWorkspace } from '@/state/workspace';
import { Banner, Button, Caption, Card, Field, IconButton, IdChip, Row, SectionHeader } from './components';
import { NODES_INFO, describeNode } from './describe';
import { spacing, type, useTheme } from './theme';

export function NodeSettings({ w, missing }: { w: ReturnType<typeof useWorkspace>; missing: string[] }) {
  const t = useTheme();
  const { nodes } = useNodes();
  const bestHeight = nodes.find((n) => n.best)?.status?.height ?? null;
  // Nodes that answer on the chain: with fewer than two, no node's answers are checked against another's.
  const answering = nodes.filter((n) => n.state === 'ok').length;
  const nodeUrls = useStore((s) => s.settings.nodeUrls);
  const setSettings = useStore((s) => s.setSettings);
  const [input, setInput] = useState('');
  const [problem, setProblem] = useState<string | null>(null);
  const tones = { success: t.success, muted: t.faint, warning: t.warning, danger: t.danger };

  const add = () => {
    const url = nodeAddress(input);
    if (remoteHttp(input)) return setProblem('Plain http is only for a node on this device or your home network. Use the node’s https address.');
    if (!url) return setProblem(`Enter an address such as ${DEFAULT_NODE_URL}.`);
    if (nodeUrls.includes(url)) return setProblem('That node is in the list already.');
    setSettings({ nodeUrls: [...nodeUrls, url] });
    setInput('');
    setProblem(null);
  };

  return (
    <>
      <SectionHeader title="Nodes" info={NODES_INFO} action="Refresh" onAction={w.refresh} />
      <Card padded={false} style={{ gap: 0 }}>
        {nodes.map((n, i) => {
          const d = describeNode(n, bestHeight);
          return (
            <Row key={n.url} gap={12} style={{ paddingVertical: spacing.sm, paddingLeft: spacing.lg, paddingRight: spacing.sm, borderTopWidth: i === 0 ? 0 : 1, borderTopColor: t.border }}>
              <View style={{ width: 8, height: 8, borderRadius: 4, backgroundColor: tones[d.tone] }} />
              <View style={{ flex: 1, gap: 2, paddingVertical: spacing.xs }}>
                <Text style={[type.mono, { color: t.text }]} numberOfLines={1}>
                  {n.url}
                </Text>
                <Caption style={{ color: d.tone === 'warning' || d.tone === 'danger' ? tones[d.tone] : t.muted }}>{d.text}</Caption>
              </View>
              {nodes.length > 1 ? (
                <IconButton icon="close" label={`Remove ${n.url}`} kind="ghost" size={36} onPress={() => setSettings({ nodeUrls: nodeUrls.filter((u) => u !== n.url) })} />
              ) : null}
            </Row>
          );
        })}
        <View style={{ padding: spacing.lg, gap: spacing.sm, borderTopWidth: 1, borderTopColor: t.border }}>
          <Field
            label="Add a node"
            value={input}
            onChangeText={(v) => {
              setInput(v);
              setProblem(null);
            }}
            autoCapitalize="none"
            autoCorrect={false}
            placeholder={DEFAULT_NODE_URL}
            error={problem}
          />
          <Row>
            <Button title="Add" small icon="add" onPress={add} disabled={!input.trim()} />
          </Row>
          {answering < 2 ? (
            <Banner tone="warning" icon="git-compare-outline">
              {answering === 1
                ? 'Only one node answers, so nothing checks it: a wrong or dishonest node could hide what happened on chain, such as a withdrawal to cancel. Add a second node, run by someone else.'
                : 'No node answers. Add one, or check the ones listed.'}
            </Banner>
          ) : null}
          {w.info ? (
            <View style={{ gap: 4 }}>
              <Row>
                <Caption>Chain</Caption>
                <IdChip value={hex(w.info.status.chainId)} />
              </Row>
              <Caption>
                Height {w.info.status.height}
                {w.info.status.mempoolCount ? ` · ${w.info.status.mempoolCount} pending` : ''}
              </Caption>
            </View>
          ) : null}
          {missing.length > 0 ? <Banner tone="warning">This chain lacks {missing.join(', ')}; identities and accounts need them.</Banner> : null}
        </View>
      </Card>
    </>
  );
}
