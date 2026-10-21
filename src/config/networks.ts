// What the app knows of each network beyond what its nodes say, by chain ID
// (hex): the most a block's proof-of-work target may be (wiki:
// consensus/genesis), so that a header without that much work is refused.

export interface Network {
  name: string;
  /** The proof-of-work limit, 32 bytes big-endian in hex. */
  powLimit: string;
}

export const NETWORKS: Record<string, Network> = {
  // The production network: 2^228 - 1.
  '15d6549cfacd816f835c632b4c67308d1c63e642f4cbf4049368430670705107': { name: 'Ting', powLimit: '0000000' + 'f'.repeat(57) },
};

/** The proof-of-work limit of chain `chainHex`; none for a chain this app does not know, such as a development chain. */
export function powLimitOf(chainHex: string | null): string | undefined {
  return chainHex ? NETWORKS[chainHex]?.powLimit : undefined;
}
