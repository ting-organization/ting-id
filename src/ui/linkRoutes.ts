// The screen a Ting link opens (LINKS.md). Scanned or pasted in the app,
// every link opens its screen. Opened from outside, only those whose screen
// asks before anything happens and moves only the person's own coins: a pay,
// create or sign link. A device or recovery link opened from a message could
// lead someone into adding a stranger's key, so those open from inside only.
import { LinkError, parseLink } from '@/chain/links';

export type LinkRoute = {
  pathname: '/send' | '/sign' | '/sponsor' | '/identity/propose' | '/identity/recovery';
  params: Record<string, string>;
};

/** Where `text` leads, or why it leads nowhere. */
export function routeOf(text: string, from: 'inside' | 'outside'): LinkRoute | LinkError {
  let link;
  try {
    link = parseLink(text);
  } catch (e) {
    return e instanceof LinkError ? e : new LinkError((e as Error).message);
  }
  const code = text.trim();
  if (from === 'outside' && (link.request === 'device' || link.request === 'recover'))
    return new LinkError('open this link from the app: scan it or paste it there');
  switch (link.request) {
    case 'pay':
      return { pathname: '/send', params: { code } };
    case 'sign':
      return { pathname: '/sign', params: { code } };
    case 'create':
      return { pathname: '/sponsor', params: { code } };
    case 'device':
      return { pathname: '/identity/propose', params: { kind: 'addKey', code } };
    case 'recover':
      return { pathname: '/identity/recovery', params: { mode: 'help', code } };
  }
}

/**
 * The route with when it was opened, so the same link opened again is taken
 * again, as a fresh request, rather than read as the one already there.
 */
export function opened(route: LinkRoute, at = Date.now()): LinkRoute {
  return { ...route, params: { ...route.params, at: String(at) } };
}

/** The path a link opened from outside goes to: its screen, or the wallet. */
export function systemPath(text: string, at = Date.now()): string {
  const route = routeOf(text, 'outside');
  if (route instanceof LinkError) return '/';
  const query = Object.entries(opened(route, at).params)
    .map(([k, v]) => `${k}=${encodeURIComponent(v)}`)
    .join('&');
  return `${route.pathname}?${query}`;
}
