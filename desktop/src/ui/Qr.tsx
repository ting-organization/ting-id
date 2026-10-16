// QR codes: showing a payment code to be paid, and reading one to pay it,
// from the camera, an image file, a dropped image or the clipboard.
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import jsQR from 'jsqr';
import { hex } from '@/core/chain/bytes';
import { encodeRequest } from '@/core/chain/request';
import type { UserAccount } from '@/core/state/chain';
import type { CoinInfo } from '@/core/state/coins';
import { relativeDuration } from '@/core/describe';
import { qrShape } from '@/core/qrShape';
import { Banner, Button, Caption, Dialog, IdChip, Segmented } from './components';
import { Icon } from './icons';

/** A QR code, dark on white in both themes so every camera reads it. */
export function QrCode({ value, size = 200 }: { value: string; size?: number }) {
  const shape = useMemo(() => qrShape(value), [value]);
  const quiet = 2;
  const box = shape.size + 2 * quiet;
  return (
    <div className="qr">
      <svg width={size} height={size} viewBox={`${-quiet} ${-quiet} ${box} ${box}`} role="img" aria-label="QR code" shapeRendering="crispEdges">
        <path d={shape.path} fill="#111827" />
      </svg>
    </div>
  );
}

/** The Receive dialog: pick an account, show its payment code and the account to copy. */
export function ReceiveDialog({
  accounts,
  slot,
  onSlot,
  onClose,
  coin,
}: {
  accounts: UserAccount[];
  slot: number | null;
  onSlot: (slot: number) => void;
  onClose: () => void;
  coin?: CoinInfo;
}) {
  const account = slot === null ? null : (accounts.find((a) => a.slot === slot) ?? null);
  const accountHex = account ? hex(account.account) : null;
  return (
    <Dialog open={account !== null} onClose={onClose} title={coin ? `Receive ${coin.config.symbol}` : 'Receive'} size="md">
      {accounts.length > 1 ? (
        <Segmented options={accounts.map((a) => ({ value: String(a.slot), label: a.label }))} value={String(slot ?? 0)} onChange={(v) => onSlot(Number(v))} />
      ) : null}
      {account && accountHex ? (
        <div className="receive">
          <QrCode value={encodeRequest({ account: accountHex, coin: coin?.hex })} />
          <div className="stack grow">
            <span className="field-label">{account.label} account</span>
            <div className="account-box">
              <IdChip value={accountHex} full />
            </div>
            <Caption>Scan the code, or click the account to copy it.</Caption>
            {account.delay ? <Caption>Deposits are instant; withdrawals from {account.label} wait {relativeDuration(account.delay)}.</Caption> : null}
          </div>
        </div>
      ) : null}
    </Dialog>
  );
}

/** Finds a QR code in an image, drawn at most `max` pixels on its long side. */
function decode(source: CanvasImageSource, width: number, height: number, canvas: HTMLCanvasElement, max: number, thorough: boolean): string | null {
  const scale = Math.min(1, max / Math.max(width, height));
  const w = Math.max(1, Math.round(width * scale));
  const h = Math.max(1, Math.round(height * scale));
  canvas.width = w;
  canvas.height = h;
  const ctx = canvas.getContext('2d', { willReadFrequently: true });
  if (!ctx) return null;
  ctx.drawImage(source, 0, 0, w, h);
  const pixels = ctx.getImageData(0, 0, w, h);
  return jsQR(pixels.data, w, h, { inversionAttempts: thorough ? 'attemptBoth' : 'dontInvert' })?.data ?? null;
}

type CameraState = { kind: 'starting' } | { kind: 'live' } | { kind: 'off'; reason: string };

/**
 * Reads a payment code. `onScan` gets each new code and returns null when it
 * took it (the caller then closes the dialog) or a message to show.
 */
export function ScanDialog({
  open,
  onClose,
  onScan,
  title = 'Scan a code',
}: {
  open: boolean;
  onClose: () => void;
  onScan: (text: string) => string | null;
  title?: string;
}) {
  return (
    <Dialog open={open} onClose={onClose} title={title} size="md">
      {open ? <Scanner onScan={onScan} /> : null}
    </Dialog>
  );
}

function Scanner({ onScan }: { onScan: (text: string) => string | null }) {
  const video = useRef<HTMLVideoElement>(null);
  const file = useRef<HTMLInputElement>(null);
  const canvas = useMemo(() => document.createElement('canvas'), []);
  const handler = useRef(onScan);
  const last = useRef<string | null>(null);
  const [camera, setCamera] = useState<CameraState>({ kind: 'starting' });
  const [message, setMessage] = useState<string | null>(null);
  const [dragging, setDragging] = useState(false);

  useEffect(() => {
    handler.current = onScan;
  }, [onScan]);

  /** A code from the camera repeats every frame; one from a file is always news. */
  const take = useCallback((text: string, repeat: boolean) => {
    if (!repeat && text === last.current) return;
    last.current = text;
    setMessage(handler.current(text));
  }, []);

  const readImage = useCallback(
    async (blob: Blob | null | undefined) => {
      if (!blob || !blob.type.startsWith('image/')) {
        setMessage('Choose an image file.');
        return;
      }
      try {
        const bitmap = await createImageBitmap(blob);
        const text = decode(bitmap, bitmap.width, bitmap.height, canvas, 2000, true);
        bitmap.close();
        if (text === null) setMessage('No QR code found in that image.');
        else take(text, true);
      } catch {
        setMessage('That image could not be read.');
      }
    },
    [canvas, take],
  );

  // The camera: frames are read a few times a second until the dialog closes.
  useEffect(() => {
    let stopped = false;
    let stream: MediaStream | null = null;
    let timer = 0;
    (async () => {
      try {
        if (!navigator.mediaDevices?.getUserMedia) throw new DOMException('no camera interface', 'NotFoundError');
        stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment', width: { ideal: 1280 } }, audio: false });
        const v = video.current;
        if (stopped || !v) return;
        v.srcObject = stream;
        await v.play();
        if (stopped) return;
        setCamera({ kind: 'live' });
        const tick = () => {
          if (stopped) return;
          if (v.readyState >= 2 && v.videoWidth > 0) {
            const text = decode(v, v.videoWidth, v.videoHeight, canvas, 720, false);
            if (text !== null) take(text, false);
          }
          timer = window.setTimeout(tick, 150);
        };
        tick();
      } catch (e) {
        if (stopped) return;
        const name = (e as DOMException).name;
        setCamera({
          kind: 'off',
          reason: name === 'NotAllowedError' || name === 'SecurityError' ? 'Camera access was declined.' : name === 'NotFoundError' || name === 'OverconstrainedError' ? 'No camera found.' : 'The camera could not be started.',
        });
      }
    })();
    return () => {
      stopped = true;
      window.clearTimeout(timer);
      stream?.getTracks().forEach((t) => t.stop());
    };
  }, [canvas, take]);

  // Ctrl+V: an image of a code, or the code's text itself.
  useEffect(() => {
    const onPaste = (e: ClipboardEvent) => {
      const image = Array.from(e.clipboardData?.files ?? []).find((f) => f.type.startsWith('image/'));
      if (image) {
        e.preventDefault();
        readImage(image);
        return;
      }
      const text = e.clipboardData?.getData('text')?.trim();
      if (text) {
        e.preventDefault();
        take(text, true);
      }
    };
    window.addEventListener('paste', onPaste);
    return () => window.removeEventListener('paste', onPaste);
  }, [readImage, take]);

  return (
    <div
      className={`scan${dragging ? ' dragging' : ''}`}
      onDragOver={(e) => {
        e.preventDefault();
        setDragging(true);
      }}
      onDragLeave={() => setDragging(false)}
      onDrop={(e) => {
        e.preventDefault();
        setDragging(false);
        readImage(e.dataTransfer.files[0]);
      }}>
      <div className="scan-view">
        <video ref={video} muted playsInline className={camera.kind === 'live' ? 'live' : ''} />
        {camera.kind === 'live' ? <div className="scan-frame" /> : null}
        {camera.kind === 'starting' ? (
          <div className="scan-state">
            <span className="spinner light" />
            Starting the camera…
          </div>
        ) : null}
        {camera.kind === 'off' ? (
          <div className="scan-state">
            <Icon name="camera-outline" size={28} />
            <span>{camera.reason}</span>
            <span className="scan-hint">Open an image of the code, drop one here, or paste one with Ctrl+V.</span>
          </div>
        ) : null}
      </div>
      {message ? <Banner tone="warning">{message}</Banner> : null}
      <div className="row between">
        <Caption>Hold the code up to the camera, or use an image of it: open, drop or paste one. Ctrl+V also pastes a copied code.</Caption>
        <Button title="Open image…" kind="ghost" small icon="image-outline" onClick={() => file.current?.click()} />
      </div>
      <input
        ref={file}
        type="file"
        accept="image/*"
        hidden
        onChange={(e) => {
          readImage(e.target.files?.[0]);
          e.target.value = '';
        }}
      />
    </div>
  );
}
