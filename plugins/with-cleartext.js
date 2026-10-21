// Lets the release build reach a node over plain http. Android refuses plain
// http in release builds unless the app allows it; Ting ID allows it here and
// limits it itself to a node on this phone or the home network
// (src/chain/pool.ts), where an https certificate is rarely set up. Anything
// else, sites included, must use https.
const { withAndroidManifest } = require('expo/config-plugins');

module.exports = function withCleartext(config) {
  return withAndroidManifest(config, (c) => {
    c.modResults.manifest.application[0].$['android:usesCleartextTraffic'] = 'true';
    return c;
  });
};
