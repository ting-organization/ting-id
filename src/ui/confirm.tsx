// Confirmation as a modal sheet: `await confirm({…})` opens it over the screen
// and resolves true when the person confirms, false when they step back.
// Serious actions ask first; none asks inline.
import React, { createContext, ReactNode, useCallback, useContext, useRef, useState } from 'react';
import Ionicons from '@expo/vector-icons/Ionicons';
import { Body, Button, Field, Row, Sheet } from './components';
import { useTheme } from './theme';

export interface ConfirmOptions {
  title: string;
  /** One short line on what happens; most confirmations need none. */
  body?: string;
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
  const t = useTheme();
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
      <Sheet visible={options !== null} onClose={() => answer(false)} title={options?.title}>
        {options?.typeToConfirm ? (
          <>
            <Row style={{ alignItems: 'flex-start' }}>
              <Ionicons name="warning-outline" size={22} color={t.danger} />
              {options.body ? <Body muted style={{ flex: 1 }}>{options.body}</Body> : null}
            </Row>
            <Field label={`Type “${options.typeToConfirm}” to confirm`} value={typed} onChangeText={setTyped} autoCapitalize="none" autoCorrect={false} />
          </>
        ) : options?.body ? (
          <Body muted>{options.body}</Body>
        ) : null}
        <Row>
          <Button title={options?.back ?? 'Back'} kind="secondary" onPress={() => answer(false)} style={{ flex: 1 }} />
          <Button
            title={options?.confirm ?? 'Confirm'}
            kind={options?.typeToConfirm ? 'destructive' : options?.danger ? 'danger' : 'primary'}
            disabled={!!options?.typeToConfirm && typed.trim().toLowerCase() !== options.typeToConfirm.toLowerCase()}
            onPress={() => answer(true)}
            style={{ flex: 1 }}
          />
        </Row>
      </Sheet>
    </ConfirmContext.Provider>
  );
}
