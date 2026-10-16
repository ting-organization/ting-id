import React from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import { setNodeFetch, setSiteFetch } from './core/chain/node';
import { isTauri } from './platform/tauri';
import '@fontsource/ibm-plex-sans/400.css';
import '@fontsource/ibm-plex-sans/500.css';
import '@fontsource/ibm-plex-sans/600.css';
import '@fontsource/ibm-plex-mono/400.css';
import '@fontsource/ibm-plex-mono/500.css';
import './styles.css';

// In the Tauri window the node is reached through the Rust HTTP client, so
// the node needs no CORS setup. Only the node client gets it: Tauri's own IPC
// goes through the page's fetch, so the global must stay untouched.
if (isTauri) {
  const { fetch } = await import('@tauri-apps/plugin-http');
  setNodeFetch(fetch as typeof globalThis.fetch);
  // Signing requests never follow a redirect: a 3xx comes back as it is and counts as a refusal.
  setSiteFetch(((input, init) => fetch(input, { ...init, maxRedirections: 0 })) as typeof globalThis.fetch);
}

createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
