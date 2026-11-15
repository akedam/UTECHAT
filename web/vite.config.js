import { defineConfig, loadEnv } from 'vite'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'UTECHAT_')
  const target = env.UTECHAT_DEV_BACKEND || 'https://utechat-test-backend.onrender.com'
  return {
    esbuild: { jsx: 'automatic' },
    server: { proxy: { '/api': { target, changeOrigin: true }, '/healthz': { target, changeOrigin: true } } },
    build: {
      rollupOptions: {
        output: {
          manualChunks(id) {
            if (id.includes('/node_modules/@firebase/firestore/')) return 'firebase-firestore'
            if (id.includes('/node_modules/@firebase/auth/')) return 'firebase-auth'
          }
        }
      }
    }
  }
})
