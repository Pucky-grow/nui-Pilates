import { defineConfig, loadEnv } from 'vite';
import vue from '@vitejs/plugin-vue';
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const target = process.env.VITE_API_TARGET || env.VITE_API_TARGET || 'http://127.0.0.1:8090';
  const cloudTest = target === 'https://uni-test-339305-11-1505023965.sh.run.tcloudbase.com';
  const localOrigins = new Set(['http://127.0.0.1:5173', 'http://localhost:5173']);
  const localCloudProxy = {
    name: 'local-cloud-test-origin-check',
    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        // Match the same prefix as Vite's proxy, including encoded paths.
        if (!req.url?.startsWith('/api')) return next();
        if (req.headers.origin && !localOrigins.has(req.headers.origin)) {
          res.writeHead(403, { 'Content-Type': 'text/plain; charset=utf-8' });
          res.end('Local test origin rejected');
          return;
        }
        next();
      });
    }
  };
  return {
    plugins: [vue(), ...(cloudTest ? [localCloudProxy] : [])],
    server: {
      port: 5173,
      ...(cloudTest ? { host: '127.0.0.1', strictPort: true } : {}),
      proxy: { '/api': {
        target, changeOrigin: true,
        ...(cloudTest ? { configure(proxy) {
          // Browser origin is checked at the local edge above. The upstream hop
          // is a server request; bearer authorization remains unchanged.
          proxy.on('proxyReq', (outgoing) => outgoing.removeHeader('origin'));
        } } : {})
      } }
    }
  };
});
