import { fileURLToPath, URL } from 'node:url'

import vue from '@vitejs/plugin-vue'
import Icons from 'unplugin-icons/vite'
import { configDefaults, defineConfig } from 'vitest/config'

export default defineConfig({
  plugins: [vue(), Icons({ compiler: 'vue3' })],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    fs: {
      // The preview imports the plugin's vendored normalize as ?raw so both
      // renderers share one baseline file.
      allow: [
        fileURLToPath(new URL('./', import.meta.url)),
        fileURLToPath(new URL('../src/main/resources/static', import.meta.url)),
      ],
    },
  },
  test: {
    environment: 'jsdom',
    server: { deps: { inline: ['@halo-dev/ai-foundation-sdk'] } },
    exclude: [...configDefaults.exclude, 'e2e/**'],
    root: fileURLToPath(new URL('./', import.meta.url)),
  },
})
