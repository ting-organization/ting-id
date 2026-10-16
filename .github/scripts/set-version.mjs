// Gives the app the version of the release tag (v1.2.3): its version and its
// Android version code. Without a version tag, as in a build run by hand, the
// app keeps the version it has.
import { readFileSync, writeFileSync } from 'node:fs';

const tag = /^refs\/tags\/v(\d+)\.(\d+)\.(\d+)$/.exec(process.env.GITHUB_REF ?? '');
if (!tag) {
  console.log('No version tag: the app keeps its version.');
  process.exit(0);
}
const [major, minor, patch] = tag.slice(1).map(Number);
// The Android version code must grow with every release: 1.2.3 is 10203.
if (minor > 99 || patch > 99) throw new Error('minor and patch must be below 100, for the Android version code');
const version = `${major}.${minor}.${patch}`;

function update(path, change) {
  const value = JSON.parse(readFileSync(path, 'utf8'));
  change(value);
  writeFileSync(path, `${JSON.stringify(value, null, 2)}\n`);
}

update('app.json', (app) => {
  app.expo.version = version;
  app.expo.android.versionCode = major * 10_000 + minor * 100 + patch;
});
console.log(`Version ${version}`);
