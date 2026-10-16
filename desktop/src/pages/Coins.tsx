// Every coin on the chain: the listed ones first, then the ones you follow,
// then the newest. A coin opens its details; Create coin deploys a new one.
import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { hex } from '@/core/chain/bytes';
import { knownCoinsFor } from '@/core/config/known-coins';
import { useCoinIndex } from '@/core/state/coinIndex';
import { CoinInfo, useCoinInfos } from '@/core/state/coins';
import { useStore } from '@/core/state/store';
import { useWorkspace } from '@/core/state/workspace';
import { CoinBadge } from '@/ui/CoinPicker';
import { capped, circulating, minterAccount, originalOf, supplyModel } from '@/core/coinFacts';
import { formatAmount } from '@/core/chain/format';
import { Banner, Button, Caption, Empty, Field, Loading, Page, Pill, Row, Segmented } from '@/ui/components';
import { useModal } from '@/ui/modal';
import { sentence } from '@/core/describe';

type Filter = 'all' | 'following' | 'yours';

export function CoinsPage() {
  const navigate = useNavigate();
  const modal = useModal();
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
    .filter((c) => filter === 'all' || (filter === 'following' ? followed.includes(c.hex) : minterAccount(c, w.accounts) !== null))
    .filter((c) => !q || c.config.name.toLowerCase().includes(q) || c.config.symbol.toLowerCase().includes(q) || c.hex.startsWith(q))
    .sort((a, b) => rank(a) - rank(b) || Number(b.config.deployHeight - a.config.deployHeight));

  return (
    <Page title="Coins" right={<Button title="Create coin" icon="add" onClick={() => modal.open('/coins/new')} disabled={!w.user || !w.info} />}>
      <Row between wrap gap={12}>
        <div style={{ flex: 1, minWidth: 220 }}>
          <Field value={query} onChangeText={setQuery} placeholder="Search by name, symbol or contract ID" />
        </div>
        <Segmented
          options={[
            { value: 'all', label: 'All' },
            { value: 'following', label: 'Following' },
            { value: 'yours', label: 'You mint' },
          ]}
          value={filter}
          onChange={setFilter}
        />
      </Row>
      {index.error ? <Banner tone="danger">{sentence(index.error)}</Banner> : null}
      {rows.length > 0 ? (
        <section className="panel">
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th>Coin</th>
                  <th>Issued by</th>
                  <th className="r">Circulating</th>
                  <th className="r">Cap</th>
                  <th aria-label="Following"></th>
                </tr>
              </thead>
              <tbody>
                {rows.map((c) => {
                  const copy = originalOf(c, all, listedIds);
                  const mine = minterAccount(c, w.accounts);
                  const open = () => navigate(`/coins/${c.hex}`);
                  return (
                    <tr key={c.hex} className="link" onClick={open}>
                      <td>
                        <span className="row" style={{ gap: 10 }}>
                          <CoinBadge coin={c} size={22} />
                          <button
                            className="link-button"
                            style={{ padding: 0, color: 'var(--text)', fontSize: 13.5, fontWeight: 600 }}
                            onClick={(e) => {
                              e.stopPropagation();
                              open();
                            }}>
                            {c.config.name}
                          </button>
                          <span className="quiet">{c.config.symbol}</span>
                          {listedIds.includes(c.hex) ? <span className="chip">listed</span> : null}
                          {copy ? <Pill label="not the original" tone="danger" small /> : null}
                          {mine ? <span className="chip primary">you mint</span> : null}
                        </span>
                      </td>
                      <td className="soft">{supplyModel(c)}</td>
                      <td className="r num">{formatAmount(circulating(c), c.config.decimals)}</td>
                      <td className="r num">{capped(c) ? formatAmount(c.config.maxSupply, c.config.decimals) : <span className="quiet">none</span>}</td>
                      <td className="r soft">{followed.includes(c.hex) ? 'following' : ''}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </section>
      ) : loading || !index.loaded ? (
        <Loading />
      ) : (
        <Empty title={q || filter !== 'all' ? 'No coins match' : 'No coins yet'} icon="layers-outline" />
      )}
      <Caption>
        {index.reading ? `Reading the chain for coins… block ${index.readTo} of ${height ?? '…'}` : `Every coin deployed up to block ${index.readTo}.`}
      </Caption>
    </Page>
  );
}
