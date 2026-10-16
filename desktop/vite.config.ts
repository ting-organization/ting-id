import { fileURLToPath, URL } from 'node:url';
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

const local = (p: string) => fileURLToPath(new URL(p, import.meta.url));

// The shared logic under src/core comes from the mobile app and imports a few
// Expo modules; those names resolve to the desktop implementations here.
export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': local('./src'),
      'react-native': local('./src/platform/react-native.ts'),
      'expo-crypto': local('./src/platform/expo-crypto.ts'),
      'expo-secure-store': local('./src/platform/expo-secure-store.ts'),
      '@react-native-async-storage/async-storage': local('./src/platform/async-storage.ts'),
    },
  },
  clearScreen: false,
  server: {
    port: 1420,
    strictPort: true,
    host: '127.0.0.1',
    watch: { ignored: ['**/src-tauri/**'] },
  },
  envPrefix: ['VITE_', 'TAURI_ENV_*'],
  // The nodes a release build starts new installations with, as the mobile build reads them.
  define: { 'process.env.EXPO_PUBLIC_TING_NODES': JSON.stringify(process.env.EXPO_PUBLIC_TING_NODES ?? '') },
  build: { target: 'es2022', sourcemap: false, chunkSizeWarningLimit: 2000 },
  test: { include: ['tests/**/*.test.ts'] },
});
