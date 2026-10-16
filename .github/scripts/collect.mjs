// Copies one platform's built files to out/ under names that stay the same
// from release to release, so a link to the latest release's file keeps
// working: node collect.mjs windows | linux | mac | android
import { copyFileSync, existsSync, mkdirSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

const bundle = 'desktop/src-tauri/target/release/bundle';
const files = {
  windows: [
    [`${bundle}/nsis`, '-setup.exe', 'Ting-ID-x64-setup.exe'],
    [`${bundle}/msi`, '.msi', 'Ting-ID-x64.msi'],
  ],
  linux: [
    [`${bundle}/appimage`, '.AppImage', 'Ting-ID-x86_64.AppImage'],
    [`${bundle}/deb`, '.deb', 'Ting-ID-amd64.deb'],
  ],
  // One app for Apple silicon and Intel Macs.
  mac: [['desktop/src-tauri/target/universal-apple-darwin/release/bundle/dmg', '.dmg', 'Ting-ID-universal.dmg']],
  // Signed with the build's own debug key; a release signs it again with the release key.
  android: [['mobile/android/app/build/outputs/apk/release', '.apk', 'Ting-ID-debug-key.apk']],
}[process.argv[2]];
if (!files) throw new Error('name a platform: windows, linux, mac or android');

mkdirSync('out', { recursive: true });
for (const [dir, ending, name] of files) {
  const found = existsSync(dir) ? readdirSync(dir).filter((f) => f.endsWith(ending)) : [];
  if (found.length !== 1) throw new Error(`expected one ${ending} file in ${dir}, found ${found.length}`);
  copyFileSync(join(dir, found[0]), join('out', name));
  console.log(`${found[0]} -> ${name}`);
}
