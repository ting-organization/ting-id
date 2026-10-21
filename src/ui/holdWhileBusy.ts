// A screen that is sending something stays until it is done: going back
// mid-way would leave the person not knowing whether it went, and the screen
// that would tell them gone. Only going back is held; the screen's own move
// when the sending ends goes ahead.
import { useEffect, useRef } from 'react';
import { useNavigation } from 'expo-router';

const BACK = new Set(['GO_BACK', 'POP', 'POP_TO_TOP']);

export function useHoldWhileBusy(busy: boolean) {
  const navigation = useNavigation();
  const held = useRef(busy);
  useEffect(() => {
    held.current = busy;
  }, [busy]);
  useEffect(
    () =>
      navigation.addListener('beforeRemove', (e) => {
        if (held.current && BACK.has(e.data.action.type)) e.preventDefault();
      }),
    [navigation],
  );
}
