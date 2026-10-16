// Dialog routes (new user, proposals, recovery, transactions) open over the
// page they came from: the opener records that page as the background
// location, the router keeps rendering it, and the dialog route renders on top.
import { useCallback } from 'react';
import { Location, useLocation, useNavigate } from 'react-router-dom';

interface ModalState {
  backgroundLocation?: Location;
}

export function useModal() {
  const navigate = useNavigate();
  const location = useLocation();
  const state = (location.state ?? null) as ModalState | null;
  const background = state?.backgroundLocation ?? location;
  const open = useCallback(
    (to: string, replace = false) => navigate(to, { state: { backgroundLocation: background }, replace }),
    [navigate, background],
  );
  const close = useCallback(() => {
    if (state?.backgroundLocation) navigate(-1);
    else navigate('/');
  }, [navigate, state]);
  return { open, close, background: state?.backgroundLocation ?? null };
}
