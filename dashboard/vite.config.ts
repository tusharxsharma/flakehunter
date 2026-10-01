/// <reference types="vitest/config" />
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

const API_URL = process.env.FLAKEHUNTER_API_URL ?? 'http://localhost:8080'
const TRIAGE_URL = process.env.FLAKEHUNTER_TRIAGE_URL ?? 'http://localhost:8000'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    // Same routing as nginx in production: one origin, two backends, no CORS needed.
    proxy: {
      '/api': API_URL,
      '/triage': { target: TRIAGE_URL, rewrite: (path) => path.replace(/^\/triage/, '') },
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
    coverage: {
      provider: 'v8',
      include: ['src/**/*.{ts,tsx}'],
      exclude: ['src/main.tsx', 'src/test/**', 'src/**/*.test.{ts,tsx}'],
      thresholds: { lines: 80, branches: 70 },
    },
  },
})
