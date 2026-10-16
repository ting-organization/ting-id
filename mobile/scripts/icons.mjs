// Renders the app's icons from the logo sources in assets/logo with Playwright
// (the Edge or Chrome already installed):
//
//   npm run icons
//
// icon.svg is the full-bleed app icon (iOS masks the corners itself),
// icon-rounded.svg the icon with its own corners (favicon, the desktop app),
// mark.svg the white mark alone (Android's adaptive and monochrome layers).
import { Buffer } from 'node:buffer';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const root = dirname(dirname(fileURLToPath(import.meta.url)));
const logo = (name) => readFileSync(join(root, 'assets', 'logo', name), 'utf8');
const inner = (svg) => svg.replace(/^[\s\S]*?<svg[^>]*>/, '').replace(/<\/svg>\s*$/, '');

/** An SVG of the mark scaled about the centre, in a colour, on a transparent canvas. */
const scaledMark = (scale, color) =>
  `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024"><g transform="translate(512 512) scale(${scale}) translate(-512 -512)">${inner(logo('mark.svg')).replaceAll('#FFFFFF', color)}</g></svg>`;
const gradientOnly = inner(logo('icon.svg')).replace(/<g[\s\S]*<\/g>/, '');

// Android keeps the central 66 of 108 units of an adaptive icon; the ring
// (two thirds of the grid) is scaled into that circle.
const targets = [
  { file: 'icon.png', size: 1024, svg: logo('icon.svg'), opaque: true },
  { file: 'android-icon-background.png', size: 512, svg: `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024">${gradientOnly}</svg>`, opaque: true },
  { file: 'android-icon-foreground.png', size: 512, svg: scaledMark(0.84, '#FFFFFF') },
  { file: 'android-icon-monochrome.png', size: 432, svg: scaledMark(0.84, '#FFFFFF') },
  { file: 'favicon.png', size: 48, svg: logo('icon-rounded.svg') },
  { file: 'splash-icon.png', size: 1024, svg: scaledMark(0.9, '#4F46E5') },
];

async function launch() {
  for (const channel of ['msedge', 'chrome']) {
    try {
      return await chromium.launch({ channel, headless: true });
    } catch {}
  }
  return chromium.launch({ headless: true });
}

const browser = await launch();
for (const t of targets) {
  const page = await browser.newPage({ viewport: { width: t.size, height: t.size }, deviceScaleFactor: 1 });
  const data = `data:image/svg+xml;base64,${Buffer.from(t.svg).toString('base64')}`;
  await page.setContent(`<html><body style="margin:0;background:transparent"><img src="${data}" width="${t.size}" height="${t.size}" style="display:block"></body></html>`);
  await page.waitForFunction(() => document.images[0]?.complete);
  const path = join(root, 'assets', t.file);
  await page.screenshot({ path, omitBackground: !t.opaque });
  await page.close();
  console.log('wrote', path);
}
await browser.close();
