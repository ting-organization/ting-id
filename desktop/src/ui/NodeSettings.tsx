// Settings → Nodes: the nodes the app uses as one, each one's state, and
// adding or removing them.
import { useState } from 'react';
import { hex } from '@/core/chain/bytes';
import { nodeAddress, remoteHttp } from '@/core/chain/pool';
import { NODES_INFO, describeNode } from '@/core/describe';
import { useNodes } from '@/core/state/chain';
import { DEFAULT_NODE_URL, useStore } from '@/core/state/store';
import type { useWorkspace } from '@/core/state/workspace';
import { Banner, Button, Caption, Field, IdChip, Panel, Row } from './components';
import { Icon } from './icons';

const TONES = { success: 'var(--success)', muted: 'var(--faint)', warning: 'var(--warning)', danger: 'var(--danger)' };

export function NodeSettings({ w, missing }: { w: ReturnType<typeof useWorkspace>; missing: string[] }) {
  const { nodes } = useNodes();
  const bestHeight = nodes.find((n) => n.best)?.status?.height ?? null;
  const nodeUrls = useStore((s) => s.settings.nodeUrls);
  const setSettings = useStore((s) => s.setSettings);
  const [input, setInput] = useState('');
  const [problem, setProblem] = useState<string | null>(null);

  const add = () => {
    const url = nodeAddress(input);
    if (remoteHttp(input)) return setProblem('Plain http is only for a node on this computer or your home network. Use the node’s https address.');
    if (!url) return setProblem(`Enter an address such as ${DEFAULT_NODE_URL}.`);
    if (nodeUrls.includes(url)) return setProblem('That node is in the list already.');
    setSettings({ nodeUrls: [...nodeUrls, url] });
    setInput('');
    setProblem(null);
  };

  return (
    <Panel title="Nodes" info={NODES_INFO} action="Refresh" onAction={w.refresh}>
      {nodes.map((n, i) => {
        const d = describeNode(n, bestHeight);
        return (
          <div key={n.url} className="node-row" style={i === 0 ? { borderTop: 0 } : undefined}>
            <span className="node-dot" style={{ background: TONES[d.tone] }} />
            <span className="stack grow" style={{ gap: 2, minWidth: 0 }}>
              <span className="node-url">{n.url}</span>
              <Caption style={d.tone === 'warning' || d.tone === 'danger' ? { color: TONES[d.tone] } : undefined}>{d.text}</Caption>
            </span>
            {nodes.length > 1 ? (
              <button className="icon-button" title={`Remove ${n.url}`} aria-label={`Remove ${n.url}`} onClick={() => setSettings({ nodeUrls: nodeUrls.filter((u) => u !== n.url) })}>
                <Icon name="close" size={16} />
              </button>
            ) : null}
          </div>
        );
      })}
      <div className="stack" style={{ padding: 16, borderTop: '1px solid var(--border)' }}>
        <Row style={{ alignItems: 'flex-end' }}>
          <div className="grow">
            <Field
              label="Add a node"
              value={input}
              onChangeText={(v) => {
                setInput(v);
                setProblem(null);
              }}
              placeholder={DEFAULT_NODE_URL}
              error={problem}
              mono
            />
          </div>
          <Button title="Add" small icon="add" onClick={add} disabled={!input.trim()} />
        </Row>
        {nodes.length === 1 ? <Caption>Add a second node so Ting ID can check this one’s answers.</Caption> : null}
        {w.info ? (
          <div className="stack" style={{ gap: 4 }}>
            <Row>
              <Caption>Chain</Caption>
              <IdChip value={hex(w.info.status.chainId)} />
            </Row>
            <Caption>
              Height {w.info.status.height}
              {w.info.status.mempoolCount ? ` · ${w.info.status.mempoolCount} pending` : ''}
            </Caption>
          </div>
        ) : null}
        {missing.length > 0 ? <Banner tone="warning">This chain lacks {missing.join(', ')}; identities and accounts need them.</Banner> : null}
      </div>
    </Panel>
  );
}
