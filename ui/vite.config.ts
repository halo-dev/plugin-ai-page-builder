import { fileURLToPath, URL } from 'node:url'

import { viteConfig } from '@halo-dev/ui-plugin-bundler-kit/vite'
import Icons from 'unplugin-icons/vite'
import UnoCSS from 'unocss/vite'

// For more info,
// please see https://github.com/halo-dev/halo/tree/main/ui/packages/ui-plugin-bundler-kit
export default viteConfig({
  vite: {
    plugins: [
      Icons({ compiler: 'vue3' }),
      UnoCSS({
        configFile: './uno.config.mjs',
        mode: 'vue-scoped',
      }),
    ],
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
          fileURLToPath(new URL('.', import.meta.url)),
          fileURLToPath(new URL('../src/main/resources/static', import.meta.url)),
        ],
      },
    },
  },
})
