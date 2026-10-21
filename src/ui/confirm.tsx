// Confirmation as a modal sheet: `await confirm({…})` opens it over the screen
// and resolves true when the person confirms, false when they step back.
// Serious actions ask first; none asks inline. `ConfirmSheet` is the same
// sheet for a screen that shows more in it, such as the fee it pays, kept up
// to date while it is open.
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
      <ConfirmSheet
        visible={options !== null}
        options={options ?? { title: '', confirm: '' }}
        onAnswer={answer}
        ready={!options?.typeToConfirm || typed.trim().toLowerCase() === options.typeToConfirm.toLowerCase()}>
        {options?.typeToConfirm ? (
          <>
            <Row style={{ alignItems: 'flex-start' }}>
              <Ionicons name="warning-outline" size={22} color={t.danger} />
              {options.body ? <Body muted style={{ flex: 1 }}>{options.body}</Body> : null}
            </Row>
            <Field label={`Type “${options.typeToConfirm}” to confirm`} value={typed} onChangeText={setTyped} autoCapitalize="none" autoCorrect={false} />
          </>
        ) : null}
      </ConfirmSheet>
    </ConfirmContext.Provider>
  );
}

/**
 * The confirmation sheet itself: the title, the body unless `children` say
 * more, `children`, then the two buttons. `ready`: whether the confirming
 * button works yet.
 */
export function ConfirmSheet({
  visible,
  options,
  onAnswer,
  ready = true,
  busy,
  children,
}: {
  visible: boolean;
  options: ConfirmOptions;
  onAnswer: (yes: boolean) => void;
  ready?: boolean;
  busy?: boolean;
  children?: ReactNode;
}) {
  return (
    <Sheet visible={visible} onClose={() => onAnswer(false)} title={options.title}>
      {options.body && !options.typeToConfirm ? <Body muted>{options.body}</Body> : null}
      {children}
      <Row>
        <Button title={options.back ?? 'Back'} kind="secondary" onPress={() => onAnswer(false)} style={{ flex: 1 }} />
        <Button
          title={options.confirm}
          kind={options.typeToConfirm ? 'destructive' : options.danger ? 'danger' : 'primary'}
          disabled={!ready}
          loading={busy}
          onPress={() => onAnswer(true)}
          style={{ flex: 1 }}
        />
      </Row>
    </Sheet>
  );
}
