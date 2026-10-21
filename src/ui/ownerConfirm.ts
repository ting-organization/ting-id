// The owner check before an operation that moves coins or changes who controls
// an identity (Settings → Security, "Confirm payments and changes"):
// payments, identity changes, recovery approvals, minting and signing for
// sites. A phone without a screen lock has nothing to ask.
import { checkOwner } from '@/state/ownerCheck';
import { useStore } from '@/state/store';

/** Resolves true to go on, false when the person cancelled; throws when the phone did not confirm. */
export function useOwnerConfirm(): (title: string, detail?: string) => Promise<boolean> {
  const on = useStore((s) => s.settings.confirmPayments !== false);
  return async (title, detail) => {
    if (!on) return true;
    const r = await checkOwner(title, detail);
    if (r === 'failed') throw new Error('the phone did not confirm it is you');
    return r !== 'cancelled';
  };
}
