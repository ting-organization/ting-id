// Links the system opens the app with: a site's signing request
// (`ting:sign?url=…`) opens for review, a payment code (`ting:<account>`) opens
// Send. Nothing else from outside opens a screen: a link could otherwise open a
// form with someone's own values filled in, such as a guardian to link.
import { isSignCode } from '@/chain/offchain';
import { isPaymentCode } from '@/chain/request';

export function redirectSystemPath({ path }: { path: string; initial: boolean }): string {
  try {
    if (isSignCode(path)) return `/sign?code=${encodeURIComponent(path)}`;
    if (/^ting:/i.test(path) && isPaymentCode(path)) return `/send?code=${encodeURIComponent(path)}`;
    return '/';
  } catch {
    return '/';
  }
}
