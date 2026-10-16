// What the shared code uses from react-native: the platform name. In the
// Tauri window it is the machine ("Windows PC"), which the shared key naming
// shows as the device; in a plain browser it is "web", which makes the shared
// keystore fall back to local storage for development.
import { hostLabel, isTauri } from './tauri';

export const Platform: { OS: string } = { OS: isTauri ? hostLabel() : 'web' };
