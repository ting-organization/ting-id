// When a payment goes: now, or at a chosen local date and time, which the
// node holds the payment until (wiki: node, deferred transactions).

/** How far ahead a payment can be set: the node holds a transaction for at most 366 days. */
export const LATEST = 365 * 86_400;

/** A later payment's first proposal: the next full hour, a day from now. */
export function defaultLater(now: number): number {
  return Math.ceil((now + 86_400) / 3600) * 3600;
}

/** `at` moved to the date of `day`, keeping its time of day. */
export function onDay(at: number, day: Date): number {
  const d = new Date(at * 1000);
  d.setFullYear(day.getFullYear(), day.getMonth(), day.getDate());
  return Math.floor(d.getTime() / 1000);
}

/** `at` moved to the time of day of `time`, on the same date, to the minute. */
export function atTime(at: number, time: Date): number {
  const d = new Date(at * 1000);
  d.setHours(time.getHours(), time.getMinutes(), 0, 0);
  return Math.floor(d.getTime() / 1000);
}

/** What is wrong with `at` as a later payment's time, or null. */
export function laterProblem(at: number, now: number): string | null {
  if (at <= now + 60) return 'choose a time in the future';
  if (at > now + LATEST) return 'choose a time within a year';
  return null;
}

/** A date for the payment's line, like `Fri 17 Oct 2026`. */
export function formatDay(at: number): string {
  return new Date(at * 1000).toLocaleDateString(undefined, { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' });
}
