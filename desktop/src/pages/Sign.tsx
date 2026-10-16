// A site's signing request (SIGNING.md): fetched from the address in its code,
// shown with the facts the app vouches for apart from what the site wrote, and
// answered with the user's signature or a refusal.
import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { hex } from '@/core/chain/bytes';
import { nowSeconds, relativeTime } from '@/core/chain/format';
import { Attachment, SignRequest, Span, bodyBlocks, matchCode, parseSignCode } from '@/core/chain/offchain';
import { declineRequest, fetchAttachment, fetchSignRequest, possibleSigners, signAndAnswer, signerProblem } from '@/core/state/signing';
import { useStore } from '@/core/state/store';
import { useWorkspace } from '@/core/state/workspace';
import { describeSignRequest, sentence, siteName } from '@/core/describe';
import { Avatar, Banner, Body, Button, Caption, Card, Dialog, Field, IconCircle, Loading, Row, Sub } from '@/ui/components';
import { Icon } from '@/ui/icons';
import { useModal } from '@/ui/modal';

type Step =
  | { kind: 'enter' }
  | { kind: 'loading'; url: string }
  | { kind: 'failed'; message: string }
  | { kind: 'review'; request: SignRequest }
  | { kind: 'sending'; request: SignRequest }
  | { kind: 'done'; request: SignRequest; outcome: 'signed' | 'declined'; message: string };

/** Each code opens its own review: a new link while one is open starts afresh. */
export function SignDialog() {
  const [params] = useSearchParams();
  return <SignFlow key={params.get('code') ?? ''} code={params.get('code') ?? ''} />;
}

function SignFlow({ code: linked }: { code: string }) {
  const modal = useModal();
  const w = useWorkspace();
  const users = useStore((s) => s.users);
  const setActiveUser = useStore((s) => s.setActiveUser);
  const addSignature = useStore((s) => s.addSignature);
  const [code, setCode] = useState('');
  const [step, setStep] = useState<Step>(() => {
    const url = parseSignCode(linked);
    return url ? { kind: 'loading', url } : { kind: 'enter' };
  });
  const [error, setError] = useState<string | null>(null);
  const chainHex = w.info ? hex(w.info.status.chainId) : null;

  useEffect(() => {
    if (step.kind !== 'loading') return;
    let alive = true;
    fetchSignRequest(step.url)
      .then((request) => alive && setStep({ kind: 'review', request }))
      .catch((e) => alive && setStep({ kind: 'failed', message: (e as Error).message }));
    return () => {
      alive = false;
    };
  }, [step]);

  const open = () => {
    const url = parseSignCode(code);
    if (!url) {
      setError('That is not a signing code from a secure site.');
      return;
    }
    setError(null);
    setStep({ kind: 'loading', url });
  };

  const note = (request: SignRequest, outcome: 'signed' | 'declined' | 'failed', message: string | null) =>
    w.user &&
    addSignature({ at: nowSeconds(), userId: w.user.id, origin: request.origin, kind: request.request.request.kind, title: request.request.title, outcome, message });

  const sign = async (request: SignRequest) => {
    if (!w.user || !w.info) return;
    setStep({ kind: 'sending', request });
    setError(null);
    try {
      const message = await signAndAnswer(request, w.user, w.info.canonical, w.keys, w.identity);
      note(request, 'signed', message);
      setStep({ kind: 'done', request, outcome: 'signed', message });
    } catch (e) {
      const message = (e as Error).message;
      note(request, 'failed', message);
      setError(message);
      setStep({ kind: 'review', request });
    }
  };

  const decline = async (request: SignRequest) => {
    setStep({ kind: 'sending', request });
    await declineRequest(request);
    note(request, 'declined', null);
    setStep({ kind: 'done', request, outcome: 'declined', message: 'The site was told you declined.' });
  };

  return (
    <Dialog open onClose={modal.close} title={step.kind === 'review' || step.kind === 'sending' ? <SiteTitle origin={step.request.origin} /> : 'Signing request'}>
      {step.kind === 'enter' ? (
        <Card>
          <Body>Paste the code a site shows for Ting ID, or scan it from the wallet.</Body>
          <Field label="Code" value={code} onChangeText={setCode} placeholder="ting:sign?url=…" mono autoFocus />
          {error ? <Banner tone="danger">{sentence(error)}</Banner> : null}
          <div>
            <Button title="Open" icon="arrow-up" onClick={open} disabled={!code.trim()} />
          </div>
        </Card>
      ) : null}
      {step.kind === 'loading' ? (
        <Card>
          <Loading />
          <Caption>Asking {siteName(new URL(step.url).origin)} for the request…</Caption>
        </Card>
      ) : null}
      {step.kind === 'failed' ? (
        <div className="stack" style={{ alignItems: 'center', gap: 8, padding: '12px 0 4px', textAlign: 'center' }}>
          <IconCircle name="alert-circle-outline" tone="danger" size={44} />
          <Sub>This request can’t be signed</Sub>
          <Caption>{sentence(step.message)}</Caption>
          <div style={{ marginTop: 8 }}>
            <Button title="Close" kind="ghost" onClick={modal.close} />
          </div>
        </div>
      ) : null}
      {step.kind === 'review' || step.kind === 'sending' ? (
        <Review
          key={hex(step.request.nonce)}
          request={step.request}
          busy={step.kind === 'sending'}
          error={error}
          problem={signerProblem(step.request, w.user, chainHex)}
          signers={possibleSigners(step.request, users, chainHex)}
          onSigner={setActiveUser}
          onSign={() => sign(step.request)}
          onDecline={() => decline(step.request)}
        />
      ) : null}
      {step.kind === 'done' ? (
        <Card>
          <Row>
            <IconCircle name={step.outcome === 'signed' ? 'checkmark-circle-outline' : 'close-circle-outline'} tone={step.outcome === 'signed' ? 'success' : 'neutral'} size={44} />
            <span className="stack grow" style={{ gap: 2 }}>
              <Sub>{step.outcome === 'signed' ? `Signed for ${siteName(step.request.origin)}` : 'Declined'}</Sub>
              <Caption>
                {step.outcome === 'signed' ? `${siteName(step.request.origin)} says: ` : ''}
                {step.message}
              </Caption>
            </span>
          </Row>
          <div>
            <Button title="Done" onClick={modal.close} />
          </div>
        </Card>
      ) : null}
    </Dialog>
  );
}

/** Who asks, as the dialog's title: the site as the app knows it. */
function SiteTitle({ origin }: { origin: string }) {
  const secure = origin.startsWith('https://');
  return (
    <div className="sign-site">
      <span className="sign-site-tile">
        <Icon name="globe-outline" size={18} />
      </span>
      <span className="stack grow" style={{ gap: 0, minWidth: 0 }}>
        <span className="sign-site-name">{siteName(origin)}</span>
        {secure ? (
          <span className="caption" style={{ margin: 0, display: 'inline-flex', alignItems: 'center', gap: 4 }}>
            <Icon name="lock-closed" size={12} color="var(--success)" />
            Secure connection
          </span>
        ) : null}
      </span>
      {secure ? null : <span className="sign-dev">In development</span>}
    </div>
  );
}

function Spans({ spans }: { spans: Span[] }) {
  return (
    <>
      {spans.map((s, k) => (s.bold ? <strong key={k}>{s.text}</strong> : s.italic ? <em key={k}>{s.text}</em> : <span key={k}>{s.text}</span>))}
    </>
  );
}

function Review({
  request,
  busy,
  error,
  problem,
  signers,
  onSigner,
  onSign,
  onDecline,
}: {
  request: SignRequest;
  busy: boolean;
  error: string | null;
  problem: string | null;
  signers: { id: string; name: string; color: string }[];
  onSigner: (id: string) => void;
  onSign: () => void;
  onDecline: () => void;
}) {
  const w = useWorkspace();
  const doc = request.request;
  const d = describeSignRequest(doc.request, w.user?.name ?? 'you');
  const [, tick] = useState(0);
  useEffect(() => {
    const t = setInterval(() => tick((n) => n + 1), 15_000);
    return () => clearInterval(t);
  }, []);
  const expired = Number(request.expiresAt) <= nowSeconds();
  // A statement's file is fetched, checked against its hash, and opened before it can be signed.
  const attachment = doc.request.kind === 'statement' ? doc.request.attachment : null;
  const [file, setFile] = useState<{ state: 'idle' | 'opening' | 'opened' | 'failed'; message?: string; bytes?: Uint8Array }>({ state: 'idle' });
  // A request that just appeared, perhaps under the pointer, cannot be signed by the same click.
  const [armed, setArmed] = useState(false);
  useEffect(() => {
    const t = setTimeout(() => setArmed(true), 1000);
    return () => clearTimeout(t);
  }, []);
  const [viewing, setViewing] = useState(false);
  const open = async () => {
    if (!attachment) return;
    if (file.bytes) {
      setViewing(true);
      return;
    }
    setFile({ state: 'opening' });
    try {
      const bytes = await fetchAttachment(request);
      setFile({ state: 'opened', bytes });
      setViewing(true);
    } catch (e) {
      setFile({ state: 'failed', message: (e as Error).message });
    }
  };
  const unopened = !!attachment && file.state !== 'opened';
  return (
    <>
      {/* What signing does, in the app's words, and who signs. */}
      <div className="stack" style={{ gap: 4 }}>
        <span className="sign-headline">{d.headline}</span>
        {d.detail ? <Caption>{d.detail}</Caption> : null}
      </div>
      {doc.request.kind === 'grant' && doc.request.scopes.length > 0 ? (
        <div className="sign-scopes">
          {doc.request.scopes.map((s) => (
            <span key={s} className="sign-scope">
              {s}
            </span>
          ))}
        </div>
      ) : null}
      {signers.length > 1 ? (
        <div className="stack" style={{ gap: 6 }}>
          <Caption>Sign as</Caption>
          <Row wrap>
            {signers.map((u) => (
              <button key={u.id} className={`signer-chip${u.id === w.user?.id ? ' selected' : ''}`} onClick={() => onSigner(u.id)} disabled={busy} aria-pressed={u.id === w.user?.id}>
                <Avatar name={u.name} color={u.color} size={22} />
                {u.name}
              </button>
            ))}
          </Row>
        </div>
      ) : null}
      {d.warning ? <Banner tone="warning">{d.warning}</Banner> : null}

      {/* The file the statement covers: only the exact file it names opens. */}
      {attachment ? (
        <div className={`sign-file${file.state === 'opened' ? ' opened' : ''}`}>
          <Icon name="document-text-outline" size={20} />
          <span className="stack grow" style={{ gap: 0, minWidth: 0 }}>
            <span className="sign-file-name">{attachment.name}</span>
            <Caption>{file.state === 'opened' ? 'Checked and opened' : attachment.mediaType}</Caption>
          </span>
          <Button title="Open" kind="ghost" small icon={file.state === 'opened' ? 'checkmark' : 'open-outline'} loading={file.state === 'opening'} onClick={open} />
        </div>
      ) : null}
      {file.state === 'failed' ? <Banner tone="danger">{sentence(file.message ?? '')}</Banner> : null}
      {attachment && file.bytes ? <FileViewer open={viewing} onClose={() => setViewing(false)} attachment={attachment} bytes={file.bytes} /> : null}

      {/* The number the site shows beside its code: the same one means the same request. */}
      {doc.request.kind === 'signIn' ? (
        <div className="sign-match">
          <span className="sign-match-number">{matchCode(request.nonce)}</span>
          <span>The site shows the same number. If it shows another, decline.</span>
        </div>
      ) : null}

      {/* What the site wrote, shown as written and nothing more. */}
      <div className="sign-message">
        <span className="sign-message-label">Message from the site</span>
        <span className="sign-message-title">{doc.title}</span>
        {bodyBlocks(doc.body).map((b, i) =>
          b.type === 'paragraph' ? (
            <p key={i} className="body" style={{ margin: 0 }}>
              {b.lines.map((spans, j) => (
                <span key={j}>
                  {j > 0 ? <br /> : null}
                  <Spans spans={spans} />
                </span>
              ))}
            </p>
          ) : (
            <div key={i} className="sign-list">
              {b.items.map((item, j) => (
                <div key={j} className="sign-list-item">
                  <span className="marker">{item.marker}</span>
                  <span>
                    <Spans spans={item.spans} />
                  </span>
                </div>
              ))}
            </div>
          ),
        )}
        {doc.fields.length > 0 ? (
          <div className="sign-fields">
            {doc.fields.map((f, i) => (
              <div key={i} className="sign-field">
                <span className="soft">{f.label}</span>
                <span>{f.value}</span>
              </div>
            ))}
          </div>
        ) : null}
      </div>

      {problem ? <Banner tone="warning">This device cannot sign it: {problem}.</Banner> : null}
      {error ? <Banner tone="danger">{sentence(error)}</Banner> : null}
      <div className="sign-footer">
        <span className={`sign-expiry${expired ? ' over' : ''}`}>
          <Icon name="time-outline" size={15} />
          {expired ? 'Expired. Start again on the site.' : unopened ? 'Open the file to sign' : `Expires ${relativeTime(request.expiresAt)}`}
        </span>
        <Button title="Decline" kind="ghost" onClick={onDecline} disabled={busy} />
        <Button title={d.action} icon="finger-print-outline" onClick={onSign} loading={busy} disabled={!!problem || expired || unopened || !armed || !w.info} />
      </div>
    </>
  );
}

/**
 * The checked file, shown in the app: a PDF, an image or text as it is; any
 * other kind can be saved, to open with the program that reads it.
 */
function FileViewer({ open, onClose, attachment, bytes }: { open: boolean; onClose: () => void; attachment: Attachment; bytes: Uint8Array }) {
  const [url, setUrl] = useState<string | null>(null);
  useEffect(() => {
    const u = URL.createObjectURL(new Blob([new Uint8Array(bytes)], { type: attachment.mediaType }));
    setUrl(u);
    return () => URL.revokeObjectURL(u);
  }, [bytes, attachment.mediaType]);
  const type = attachment.mediaType;
  const save = () => {
    if (!url) return;
    const a = document.createElement('a');
    a.href = url;
    a.download = attachment.name;
    a.click();
  };
  return (
    <Dialog open={open} onClose={onClose} title={attachment.name} size="lg">
      {!url ? null : type === 'application/pdf' ? (
        <iframe className="sign-viewer" src={`${url}#view=FitH&navpanes=0`} title={attachment.name} />
      ) : type.startsWith('image/') ? (
        <img className="sign-viewer-image" src={url} alt={attachment.name} />
      ) : type.startsWith('text/') ? (
        <pre className="sign-viewer-text">{new TextDecoder().decode(bytes)}</pre>
      ) : (
        <Caption>Ting ID cannot show a file of this kind ({type}). Save a copy and open it with the program that reads it.</Caption>
      )}
      <div className="sign-footer">
        <span className="sign-expiry">
          <Icon name="checkmark-circle-outline" size={15} color="var(--success)" />
          This is the file the statement covers.
        </span>
        <Button title="Save a copy" kind="ghost" icon="download-outline" onClick={save} />
        <Button title="Done" onClick={onClose} />
      </div>
    </Dialog>
  );
}

