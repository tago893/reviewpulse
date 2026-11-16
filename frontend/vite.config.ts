import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

// The dev server proxies /api to the backend. For /api/admin/** it adds the X-Admin-Token header
// server-side from ../.env, so the token never reaches the browser bundle.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '..', '');
  const adminToken = env.REVIEWPULSE_ADMIN_TOKEN ?? '';
  const backend = env.REVIEWPULSE_BACKEND_URL ?? 'http://127.0.0.1:8080';
  return {
    plugins: [react()],
    server: {
      host: '127.0.0.1',
      port: 5173,
      strictPort: true,
      // More specific prefix first: only admin calls carry the token.
      proxy: {
        '/api/admin': { target: backend, headers: adminToken ? { 'X-Admin-Token': adminToken } : {} },
        '/api': { target: backend },
      },
    },
  };
});
