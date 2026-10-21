// What the app calls an identity's accounts: by slot, Spending and Savings,
// then Account 2, 3, ….

export const SLOT_LABELS = ['Spending', 'Savings'];

export function slotLabel(slot: number): string {
  if (slot < SLOT_LABELS.length) return SLOT_LABELS[slot];
  return `Account ${slot}`;
}
