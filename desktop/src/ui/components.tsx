// Building blocks shared by every page: the mobile app's components, as DOM.
import React, { CSSProperties, ReactNode, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { shortId } from '@/core/chain/format';
import { nodeNotice } from '@/core/describe';
import { useChainInfo } from '@/core/state/chain';
import type { Tone } from '@/platform/ui-types';
import { copyText } from '@/platform/clipboard';
import { Icon } from './icons';

export function Page({ title, right, children, narrow }: { title?: string; right?: ReactNode; children: ReactNode; narrow?: boolean }) {
  return (
    <div className={`page${narrow ? ' narrow' : ''}`}>
      <NodeNotice />
      {title ? (
        <div className="page-header">
          <h1 className="page-title">{title}</h1>
          {right}
        </div>
      ) : null}
      {children}
    </div>
  );
}

/** The one message on every page about the nodes: none answers, or their answers did not check out. */
function NodeNotice() {
  const { info, error, notice } = useChainInfo();
  const message = nodeNotice(info?.status.height ?? null, error, notice);
  return message ? (
    <Banner tone={notice?.kind === 'distrusted' && !error ? 'danger' : 'warning'} icon={error ? 'cloud-offline-outline' : 'shield-outline'}>
      {message}
    </Banner>
  ) : null;
}

export function Card({ children, tone, flush, className, style }: { children: ReactNode; tone?: 'alt'; flush?: boolean; className?: string; style?: CSSProperties }) {
  return (
    <div className={`card${tone === 'alt' ? ' alt' : ''}${flush ? ' flush' : ''}${className ? ` ${className}` : ''}`} style={style}>
      {children}
    </div>
  );
}

export function H({ children, style }: { children: ReactNode; style?: CSSProperties }) {
  return (
    <h2 className="heading" style={style}>
      {children}
    </h2>
  );
}

export function Sub({ children, style }: { children: ReactNode; style?: CSSProperties }) {
  return (
    <h4 className="sub" style={style}>
      {children}
    </h4>
  );
}

export function Body({ children, muted, style }: { children: ReactNode; muted?: boolean; style?: CSSProperties }) {
  return (
    <p className={`body${muted ? ' muted' : ''}`} style={style}>
      {children}
    </p>
  );
}

export function Caption({ children, style, className }: { children: ReactNode; style?: CSSProperties; className?: string }) {
  return (
    <p className={`caption${className ? ` ${className}` : ''}`} style={style}>
      {children}
    </p>
  );
}

/** Room kept between a tooltip and the window's edge or its icon. */
const TIP_GAP = 8;

/**
 * An (i) that explains on hover or focus. The explanation floats above the
 * page, outside any card or dialog that would cut it off, below the icon or
 * above it when there is no room, and always inside the window.
 */
export function Help({ text, light }: { text: string; light?: boolean }) {
  const icon = useRef<HTMLSpanElement>(null);
  const tip = useRef<HTMLSpanElement>(null);
  const [open, setOpen] = useState(false);
  const [place, setPlace] = useState<{ left: number; top: number } | null>(null);
  useLayoutEffect(() => {
    if (!open || !icon.current || !tip.current) return;
    const at = icon.current.getBoundingClientRect();
    const { width, height } = tip.current.getBoundingClientRect();
    const left = Math.min(Math.max(at.left + at.width / 2 - width / 2, TIP_GAP), window.innerWidth - width - TIP_GAP);
    const below = at.bottom + TIP_GAP;
    const top = below + height <= window.innerHeight - TIP_GAP ? below : Math.max(at.top - TIP_GAP - height, TIP_GAP);
    setPlace({ left, top });
  }, [open]);
  useEffect(() => {
    if (!open) return;
    // The page moving under it would leave it behind.
    const close = () => setOpen(false);
    window.addEventListener('scroll', close, true);
    window.addEventListener('resize', close);
    return () => {
      window.removeEventListener('scroll', close, true);
      window.removeEventListener('resize', close);
    };
  }, [open]);
  const show = () => {
    setPlace(null);
    setOpen(true);
  };
  return (
    <span
      ref={icon}
      className={`help${light ? ' light' : ''}`}
      tabIndex={0}
      aria-label={text}
      onMouseEnter={show}
      onMouseLeave={() => setOpen(false)}
      onFocus={show}
      onBlur={() => setOpen(false)}>
      <Icon name="information-circle-outline" size={16} />
      {open
        ? createPortal(
            <span ref={tip} className="tip" role="tooltip" style={place ? { left: place.left, top: place.top } : { left: 0, top: 0, visibility: 'hidden' }}>
              {text}
            </span>,
            document.body,
          )
        : null}
    </span>
  );
}

export function SectionHeader({ title, action, onAction, icon, info }: { title: string; action?: string; onAction?: () => void; icon?: string; info?: string }) {
  return (
    <div className="section-header">
      <h3>
        {icon ? <Icon name={icon} size={18} /> : null}
        {title}
        {info ? <Help text={info} /> : null}
      </h3>
      {action && onAction ? (
        <button className="link-button" onClick={onAction}>
          {action}
        </button>
      ) : null}
    </div>
  );
}

export function Button({
  title,
  onClick,
  kind = 'primary',
  small,
  icon,
  loading,
  disabled,
  block,
  plain,
  type = 'button',
}: {
  title: string;
  onClick?: () => void;
  /** `destructive`: solid red, for what cannot be undone. */
  kind?: 'primary' | 'secondary' | 'ghost' | 'danger' | 'destructive';
  small?: boolean;
  icon?: string;
  loading?: boolean;
  disabled?: boolean;
  block?: boolean;
  /** No box until hovered: an action inside a table row. */
  plain?: boolean;
  type?: 'button' | 'submit';
}) {
  return (
    <button type={type} className={`btn ${kind}${small ? ' small' : ''}${block ? ' block' : ''}${plain ? ' plain' : ''}`} onClick={onClick} disabled={disabled || loading}>
      {loading ? <span className={`spinner${kind === 'primary' ? ' light' : ''}`} /> : icon ? <Icon name={icon} size={small ? 15 : 18} /> : null}
      {title}
    </button>
  );
}

/** An icon on a tinted tile, the leading mark of list rows. */
export function IconCircle({ name, tone = 'primary', size = 34, color, bg }: { name: string; tone?: Tone; size?: number; color?: string; bg?: string }) {
  return (
    <span className={`icon-circle${color ? '' : ` tone-${tone}`}`} style={{ width: size, height: size, background: bg, color }}>
      <Icon name={name} size={Math.round(size * 0.5)} />
    </span>
  );
}

/** A count with its icon and a short line under it, for a page's summary card. */
export function StatTile({ label, value, icon, tone = 'primary', hint }: { label: string; value: string; icon: string; tone?: Tone; hint?: string }) {
  return (
    <div className="stat-tile">
      <IconCircle name={icon} tone={tone} size={40} />
      <span className="text">
        <span className="value">{value}</span>
        <span className="label">{label}</span>
        {hint ? <span className="hint">{hint}</span> : null}
      </span>
    </div>
  );
}

export function Field({
  label,
  hint,
  error,
  multiline,
  value,
  onChangeText,
  placeholder,
  type = 'text',
  autoFocus,
  mono,
}: {
  label?: string;
  hint?: string;
  error?: string | null;
  multiline?: boolean;
  value: string;
  onChangeText: (v: string) => void;
  placeholder?: string;
  type?: string;
  autoFocus?: boolean;
  mono?: boolean;
}) {
  const style = mono ? { fontFamily: 'var(--mono)', fontSize: 13 } : undefined;
  return (
    <div className={`field${error ? ' error' : ''}`}>
      {label ? <label>{label}</label> : null}
      {multiline ? (
        <textarea value={value} onChange={(e) => onChangeText(e.target.value)} placeholder={placeholder} autoFocus={autoFocus} spellCheck={false} />
      ) : (
        <input type={type} value={value} onChange={(e) => onChangeText(e.target.value)} placeholder={placeholder} autoFocus={autoFocus} spellCheck={false} style={style} />
      )}
      {error ? <span className="error-text">{error}</span> : hint ? <Caption>{hint}</Caption> : null}
    </div>
  );
}

export function Pill({ label, tone = 'neutral', small, light }: { label: string; tone?: Tone; small?: boolean; light?: boolean }) {
  return <span className={`pill${small ? ' small' : ''}${light ? ' light' : ` tone-${tone}`}`}>{label}</span>;
}

export function initialsOf(name: string): string {
  return (
    name
      .split(/[\s·]+/)
      .map((p) => p.replace(/[^\p{L}\p{N}]/gu, '')[0])
      .filter(Boolean)
      .slice(0, 2)
      .join('')
      .toUpperCase() || '?'
  );
}

export function Avatar({ name, color, size = 40 }: { name: string; color: string; size?: number }) {
  return (
    <span className="avatar" style={{ width: size, height: size, fontSize: Math.round(size * 0.4), background: color }}>
      {initialsOf(name)}
    </span>
  );
}

/** A 32-byte identifier, shortened, copied on click. */
export function IdChip({ value, label, full, light }: { value: string; label?: string; full?: boolean; light?: boolean }) {
  const [copied, setCopied] = useState(false);
  return (
    <button
      className={`id-chip${light ? ' light' : ''}${full ? ' full' : ''}`}
      title="Copy"
      onClick={async () => {
        await copyText(value);
        setCopied(true);
        setTimeout(() => setCopied(false), 1500);
      }}>
      {label ? <span className="label">{label}</span> : null}
      <span className="value">{full ? value : shortId(value, 8, 6)}</span>
      <Icon name={copied ? 'checkmark' : 'copy-outline'} size={13} color={copied ? (light ? '#fff' : 'var(--success)') : light ? 'rgba(255,255,255,0.8)' : 'var(--muted)'} />
    </button>
  );
}

export function Row({ children, between, wrap, gap, style, className }: { children: ReactNode; between?: boolean; wrap?: boolean; gap?: number; style?: CSSProperties; className?: string }) {
  return (
    <div className={`row${between ? ' between' : ''}${wrap ? ' wrap' : ''}${className ? ` ${className}` : ''}`} style={{ gap, ...style }}>
      {children}
    </div>
  );
}

export function ListItem({
  title,
  subtitle,
  left,
  right,
  onClick,
  selected,
  chevron,
  first,
}: {
  title: ReactNode;
  subtitle?: ReactNode;
  left?: ReactNode;
  right?: ReactNode;
  onClick?: () => void;
  selected?: boolean;
  chevron?: boolean;
  first?: boolean;
}) {
  const className = `list-item${onClick ? ' clickable' : ''}${selected ? ' selected' : ''}${first ? ' first' : ''}`;
  const content = (
    <>
      {left}
      <span className="text">
        {typeof title === 'string' ? <span className="title">{title}</span> : title}
        {typeof subtitle === 'string' ? <Caption>{subtitle}</Caption> : subtitle}
      </span>
      {right}
      {chevron ? <Icon name="chevron-forward" size={18} color="var(--faint)" /> : null}
    </>
  );
  return onClick ? (
    <button className={className} onClick={onClick}>
      {content}
    </button>
  ) : (
    <div className={className}>{content}</div>
  );
}

export function Segmented<T extends string>({ options, value, onChange }: { options: { value: T; label: string }[]; value: T; onChange: (v: T) => void }) {
  return (
    <div className="segmented">
      {options.map((o) => (
        <button key={o.value} className={o.value === value ? 'active' : ''} onClick={() => onChange(o.value)}>
          {o.label}
        </button>
      ))}
    </div>
  );
}

export function Banner({ children, tone = 'info', icon }: { children: ReactNode; tone?: 'info' | 'warning' | 'danger' | 'success'; icon?: string }) {
  const map = {
    info: { cls: 'tone-primary', icon: 'information-circle-outline' },
    warning: { cls: 'tone-warning', icon: 'alert-circle-outline' },
    danger: { cls: 'tone-danger', icon: 'close-circle-outline' },
    success: { cls: 'tone-success', icon: 'checkmark-circle-outline' },
  }[tone];
  return (
    <div className={`banner ${map.cls}`}>
      <Icon name={icon ?? map.icon} size={18} />
      <span className="grow">{children}</span>
    </div>
  );
}

export function Empty({ title, body, icon = 'leaf-outline' }: { title: string; body?: string; icon?: string }) {
  return (
    <div className="empty">
      <IconCircle name={icon} tone="neutral" size={40} />
      <span className="sub">{title}</span>
      {body ? <Caption>{body}</Caption> : null}
    </div>
  );
}

export function Loading({ label }: { label?: string }) {
  return (
    <div className="loading">
      <span className="spinner" />
      {label ? <Caption>{label}</Caption> : null}
    </div>
  );
}

export function Switch({ checked, onChange }: { checked: boolean; onChange: (v: boolean) => void }) {
  return <button role="switch" aria-checked={checked} className={`switch${checked ? ' on' : ''}`} onClick={() => onChange(!checked)} />;
}

export function Divider() {
  return <div className="divider" />;
}

/** A centered dialog; Escape and the backdrop close it. */
/** `title`: a heading, or a header of its own (a site asking to sign). */
/** A dialog over the page. While `busy` (submitting), Escape, a click beside it and the close button do nothing. */
export function Dialog({ open, onClose, title, children, size, busy }: { open: boolean; onClose: () => void; title?: ReactNode; children: ReactNode; size?: 'sm' | 'md' | 'lg'; busy?: boolean }) {
  const overlay = useRef<HTMLDivElement>(null);
  const close = () => {
    if (!busy) onClose();
  };
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      // Only the topmost dialog closes, as a confirmation over another dialog.
      const overlays = document.querySelectorAll('.overlay');
      if (e.key === 'Escape' && overlays[overlays.length - 1] === overlay.current && !busy) onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, onClose, busy]);
  if (!open) return null;
  return (
    <div ref={overlay} className="overlay" onMouseDown={(e) => e.target === e.currentTarget && close()}>
      <div className={`dialog${size ? ` ${size}` : ''}`} role="dialog" aria-modal>
        <div className="dialog-header">
          {typeof title === 'string' ? <H>{title}</H> : (title ?? <span />)}
          <button className="close-button" onClick={close} disabled={busy} aria-label="Close">
            <Icon name="close" size={17} />
          </button>
        </div>
        <div className="dialog-body">{children}</div>
      </div>
    </div>
  );
}

/**
 * A white panel with its title in a header bar: a count beside the title, an
 * (i) for anything worth explaining, actions at the end, and an optional
 * footer. `attention` edges it amber, for what the user should look at.
 */
export function Panel({
  title,
  count,
  info,
  action,
  onAction,
  actions,
  footer,
  attention,
  padded,
  children,
}: {
  title: ReactNode;
  count?: number;
  info?: string;
  /** A text action at the end of the header. */
  action?: string;
  onAction?: () => void;
  actions?: ReactNode;
  footer?: ReactNode;
  attention?: boolean;
  /** Content that is not rows or a table gets the panel's inner spacing. */
  padded?: boolean;
  children: ReactNode;
}) {
  return (
    <section className={`panel${attention ? ' attention' : ''}`}>
      <div className="panel-head">
        <h2>{title}</h2>
        {count !== undefined ? <span className="count">{count}</span> : null}
        {info ? <Help text={info} /> : null}
        {actions || action ? (
          <span className="end">
            {actions}
            {action ? (
              <button className="link-button" onClick={onAction}>
                {action}
              </button>
            ) : null}
          </span>
        ) : null}
      </div>
      {padded ? <div className="panel-body">{children}</div> : children}
      {footer ? <div className="panel-foot">{footer}</div> : null}
    </section>
  );
}

/** A row in a details list: label left, value right. */
export function Detail({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="list-item">
      <Caption className="grow">{label}</Caption>
      {typeof value === 'string' ? <span>{value}</span> : value}
    </div>
  );
}
