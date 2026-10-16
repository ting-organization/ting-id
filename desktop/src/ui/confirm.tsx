// Confirmation as a modal: `await confirm({…})` opens a dialog over the page
// and resolves true when the person confirms, false when they step back
// (the back button, Escape or the backdrop). Serious actions ask first; none
// asks inline.
import { createContext, ReactNode, useCallback, useContext, useRef, useState } from 'react';
import { Body, Button, Dialog, Field, Row } from './components';
import { Icon } from './icons';

export interface ConfirmOptions {
  title: string;
  /** One short line on what happens; most confirmations need none. */
  body?: ReactNode;
  /** The confirming button, named for the action ("Cancel action", "Delete key"). */
  confirm: string;
  /** The button that steps back; never "Cancel", which may be the action itself. */
  back?: string;
  danger?: boolean;
  /**
   * For what cannot be undone: the person types this phrase before the
   * confirming button, solid red, works.
   */
  typeToConfirm?: string;
}

type Ask = (options: ConfirmOptions) => Promise<boolean>;

const ConfirmContext = createContext<Ask>(async () => false);

export function useConfirm(): Ask {
  return useContext(ConfirmContext);
}

export function ConfirmProvider({ children }: { children: ReactNode }) {
  const [options, setOptions] = useState<ConfirmOptions | null>(null);
  const [typed, setTyped] = useState('');
  const resolver = useRef<((yes: boolean) => void) | null>(null);
  const ask = useCallback<Ask>(
    (o) =>
      new Promise<boolean>((resolve) => {
        resolver.current?.(false);
        resolver.current = resolve;
        setTyped('');
        setOptions(o);
      }),
    [],
  );
  const answer = (yes: boolean) => {
    resolver.current?.(yes);
    resolver.current = null;
    setOptions(null);
  };
  return (
    <ConfirmContext.Provider value={ask}>
      {children}
      <Dialog open={options !== null} onClose={() => answer(false)} title={options?.title} size="sm">
        {options?.typeToConfirm ? (
          <>
            <Row gap={10} style={{ alignItems: 'flex-start' }}>
              <Icon name="warning-outline" size={20} color="var(--danger)" />
              {options.body ? <Body muted>{options.body}</Body> : null}
            </Row>
            <Field label={`Type “${options.typeToConfirm}” to confirm`} value={typed} onChangeText={setTyped} autoFocus />
          </>
        ) : options?.body ? (
          <Body muted>{options.body}</Body>
        ) : null}
        <Row style={{ justifyContent: 'flex-end' }}>
          <Button title={options?.back ?? 'Back'} kind="ghost" onClick={() => answer(false)} />
          <Button
            title={options?.confirm ?? 'Confirm'}
            kind={options?.typeToConfirm ? 'destructive' : options?.danger ? 'danger' : 'primary'}
            disabled={!!options?.typeToConfirm && typed.trim().toLowerCase() !== options.typeToConfirm.toLowerCase()}
            onClick={() => answer(true)}
          />
        </Row>
      </Dialog>
    </ConfirmContext.Provider>
  );
}
