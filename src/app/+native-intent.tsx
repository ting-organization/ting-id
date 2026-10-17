// Links the system opens the app with (LINKS.md): a pay, create or sign link
// opens its screen, which asks before anything happens. Nothing else from
// outside opens a screen: a device or recovery link from a message could
// otherwise lead someone into adding a stranger's key (ui/linkRoutes).
import { systemPath } from '@/ui/linkRoutes';

export function redirectSystemPath({ path }: { path: string; initial: boolean }): string {
  try {
    return systemPath(path);
  } catch {
    return '/';
  }
}
