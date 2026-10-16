// Renders the window and installer icons from public/logo.svg (synced from
// the mobile app's assets/logo/icon-rounded.svg): the SVG is drawn at
// 1024 × 1024 with the Edge or Chrome already installed, then `tauri icon`
// writes every size into src-tauri/icons; its Android and iOS sets are not
// the desktop app's and are left out.
//
//   npm run icons
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const root = dirname(dirname(fileURLToPath(import.meta.url)));
const svg = readFileSync(join(root, 'public', 'logo.svg'));
const work = mkdtempSync(join(tmpdir(), 'ting-id-icon-'));
const png = join(work, 'icon.png');

let browser;
for (const channel of ['msedge', 'chrome', undefined]) {
  try {
    browser = await chromium.launch({ channel, headless: true });
    break;
  } catch {}
}
const page = await browser.newPage({ viewport: { width: 1024, height: 1024 }, deviceScaleFactor: 1 });
await page.setContent(
  `<html><body style="margin:0;background:transparent"><img src="data:image/svg+xml;base64,${svg.toString('base64')}" width="1024" height="1024" style="display:block"></body></html>`,
);
await page.waitForFunction(() => document.images[0]?.complete);
await page.screenshot({ path: png, omitBackground: true });
await browser.close();

const run = spawnSync('npx', ['tauri', 'icon', png], { cwd: root, stdio: 'inherit', shell: true });
rmSync(work, { recursive: true, force: true });
for (const mobile of ['android', 'ios']) rmSync(join(root, 'src-tauri', 'icons', mobile), { recursive: true, force: true });
process.exit(run.status ?? 1);
