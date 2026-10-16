// What the window shows when the saved state could not be read. Nothing is
// overwritten until the person chooses to start fresh.
import { useState } from 'react';
import { startFresh } from '@/core/state/store';
import { setUnreadableStateAside } from '@/platform/async-storage';
import { Banner, Button, Caption, Row } from './components';
import { useConfirm } from './confirm';
import { sentence } from '@/core/describe';


export function LoadError({ message }: { message: string }) {
  const confirm = useConfirm();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const start = async () => {
    const yes = await confirm({
      title: 'Start fresh?',
      body: 'Ting ID starts without users, contacts or settings. The keys it can still find in the saved data stay listed, and the file stays in the app’s data folder. Identities and funds stay on chain.',
      confirm: 'Start fresh',
      back: 'Keep the saved data',
      danger: true,
      typeToConfirm: 'start fresh',
    });
    if (!yes) return;
    setBusy(true);
    setError(null);
    try {
      await startFresh(setUnreadableStateAside);
    } catch (e) {
      setError(sentence((e as Error).message));
      setBusy(false);
    }
  };

  return (
    <div className="page narrow" style={{ paddingTop: 72 }}>
      <h1 className="page-title">Ting ID could not read its saved data</h1>
      <Banner tone="danger">{sentence(message)}</Banner>
      <Caption>Nothing has been changed: your users and settings stay as they were saved, and your keys stay in the system keychain. Closing Ting ID and opening it again tries once more.</Caption>
      <Row>
        <Button title="Start fresh" kind="danger" onClick={start} loading={busy} />
      </Row>
      {error ? <Banner tone="danger">{sentence(error)}</Banner> : null}
    </div>
  );
}
