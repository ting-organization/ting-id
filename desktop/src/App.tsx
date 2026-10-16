import { useEffect, useRef } from 'react';
import { HashRouter, Location, Outlet, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import { getCurrent, onOpenUrl } from '@tauri-apps/plugin-deep-link';
import { isSignCode } from '@/core/chain/offchain';
import { isPaymentCode } from '@/core/chain/request';
import { useStore } from '@/core/state/store';
import { useCoinBootstrap } from '@/core/state/workspace';
import { Layout } from '@/ui/Layout';
import { LoadError } from '@/ui/LoadError';
import { Loading } from '@/ui/components';
import { ConfirmProvider } from '@/ui/confirm';
import { CoinPage } from '@/pages/Coin';
import { CoinDetailsPage } from '@/pages/CoinDetails';
import { CoinsPage } from '@/pages/Coins';
import { CreateCoinDialog } from '@/pages/CreateCoin';
import { IdentityPage } from '@/pages/Identity';
import { MintDialog } from '@/pages/Mint';
import { MinterDialog } from '@/pages/Minter';
import { NewUserDialog } from '@/pages/NewUser';
import { PeoplePage } from '@/pages/People';
import { ProposeDialog } from '@/pages/Propose';
import { RecoveryDialog } from '@/pages/Recovery';
import { SendPage } from '@/pages/Send';
import { SettingsPage } from '@/pages/Settings';
import { SignDialog } from '@/pages/Sign';
import { TransactionDialog } from '@/pages/Transaction';
import { WalletPage } from '@/pages/Wallet';
import { isTauri } from '@/platform/tauri';

/**
 * Settings → Appearance: the page's colors, and on Tauri the window's title
 * bar, follow the choice; "system" leaves both to the system.
 */
function useAppearance() {
  const theme = useStore((s) => s.settings.theme);
  useEffect(() => {
    const root = document.documentElement;
    if (theme === 'light' || theme === 'dark') root.dataset.theme = theme;
    else delete root.dataset.theme;
    if (!isTauri) return;
    import('@tauri-apps/api/window')
      .then(({ getCurrentWindow }) => getCurrentWindow().setTheme(theme === 'light' || theme === 'dark' ? theme : null))
      .catch(() => {});
  }, [theme]);
}

/** Runs once the store is hydrated: follows the chain's known coins. */
function Bootstrap() {
  useCoinBootstrap();
  return null;
}

/** Whether the link the app was started with was handled: once per run, not again on every page. */
let startLinkHandled = false;

/**
 * `ting:` links the system hands the app, at start or while it runs: a site's
 * signing request opens for review, a payment code opens Send.
 */
function DeepLinks() {
  const navigate = useNavigate();
  const location = useLocation();
  const here = useRef(location);
  here.current = location;
  // `navigate` changes with every page; the listener stays and uses the latest.
  const go = useRef(navigate);
  go.current = navigate;
  useEffect(() => {
    if (!isTauri) return;
    let stop: (() => void) | undefined;
    let alive = true;
    const open = (urls: string[] | null) => {
      for (const url of urls ?? []) {
        const background = (here.current.state as { backgroundLocation?: Location } | null)?.backgroundLocation ?? here.current;
        if (isSignCode(url)) go.current(`/sign?code=${encodeURIComponent(url)}`, { state: { backgroundLocation: background } });
        else if (isPaymentCode(url)) go.current(`/send?code=${encodeURIComponent(url)}`);
      }
    };
    if (!startLinkHandled) {
      startLinkHandled = true;
      getCurrent().then(open).catch(() => {});
    }
    onOpenUrl(open)
      .then((unlisten) => (alive ? (stop = unlisten) : unlisten()))
      .catch(() => {});
    return () => {
      alive = false;
      stop?.();
    };
  }, []);
  return null;
}

function WalletBehind() {
  return (
    <>
      <WalletPage />
      <Outlet />
    </>
  );
}

/** Dialog routes render over the page they were opened from (see ui/modal.ts). */
function AppRoutes() {
  const location = useLocation();
  const background = (location.state as { backgroundLocation?: Location } | null)?.backgroundLocation;
  const dialogs = (
    <>
      <Route path="user/new" element={<NewUserDialog />} />
      <Route path="identity/propose" element={<ProposeDialog />} />
      <Route path="identity/recovery" element={<RecoveryDialog />} />
      <Route path="tx/:id" element={<TransactionDialog />} />
      <Route path="sign" element={<SignDialog />} />
      <Route path="coins/new" element={<CreateCoinDialog />} />
      <Route path="mint/:id" element={<MintDialog />} />
      <Route path="minter/:id" element={<MinterDialog />} />
    </>
  );
  return (
    <>
      <Routes location={background ?? location}>
        <Route element={<Layout />}>
          <Route index element={<WalletPage />} />
          <Route path="coin/:id" element={<CoinPage />} />
          <Route path="coins" element={<CoinsPage />} />
          <Route path="coins/:id" element={<CoinDetailsPage />} />
          <Route path="send" element={<SendPage />} />
          <Route path="identity" element={<IdentityPage />} />
          <Route path="people" element={<PeoplePage />} />
          <Route path="settings" element={<SettingsPage />} />
          {/* A dialog address opened directly shows the wallet behind it. */}
          <Route element={<WalletBehind />}>{dialogs}</Route>
          <Route path="*" element={<WalletPage />} />
        </Route>
      </Routes>
      {background ? <Routes>{dialogs}</Routes> : null}
    </>
  );
}

export function App() {
  const hydrated = useStore((s) => s.hydrated);
  const loadError = useStore((s) => s.loadError);
  useAppearance();
  if (loadError) {
    return (
      <ConfirmProvider>
        <LoadError message={loadError} />
      </ConfirmProvider>
    );
  }
  if (!hydrated) {
    return (
      <div style={{ height: '100%', display: 'grid', placeItems: 'center' }}>
        <Loading />
      </div>
    );
  }
  return (
    <HashRouter>
      <ConfirmProvider>
        <Bootstrap />
        <DeepLinks />
        <AppRoutes />
      </ConfirmProvider>
    </HashRouter>
  );
}
