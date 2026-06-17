import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// During local dev we proxy /api and /actuator to the Spring Boot backend so the
// browser sees a single origin (no CORS, simpler cookies later). The target
// defaults to :8080 but can be overridden with VITE_PROXY_TARGET (e.g. when the
// backend runs on another port). In production the frontend talks to
// VITE_API_BASE_URL directly (see api/client.js).
const proxyTarget = process.env.VITE_PROXY_TARGET || 'http://localhost:8080';

export default defineConfig({
  plugins: [react()],
  build: {
    rollupOptions: {
      output: {
        // Split the heavy, rarely-changing libraries into their own chunks so
        // the browser caches them across app deploys (Recharts alone is ~400KB).
        manualChunks: {
          react: ['react', 'react-dom', 'react-router-dom'],
          charts: ['recharts'],
          query: ['@tanstack/react-query', 'axios'],
        },
      },
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': { target: proxyTarget, changeOrigin: true },
      '/actuator': { target: proxyTarget, changeOrigin: true },
    },
  },
});
