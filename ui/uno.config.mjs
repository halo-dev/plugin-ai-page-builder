import { presetWind3 } from 'unocss'
import { defineConfig } from 'unocss'

export default defineConfig({
  presets: [presetWind3()],
  shortcuts: {
    'apb-btn':
      'inline-flex items-center justify-center gap-1.5 h-8 px-3 rounded-lg text-[13px] font-medium transition-colors select-none whitespace-nowrap disabled:opacity-50 disabled:pointer-events-none',
    'apb-btn-primary': 'apb-btn bg-gray-900 text-white hover:bg-gray-700',
    'apb-btn-outline': 'apb-btn border border-gray-200 bg-white text-gray-700 hover:bg-gray-50',
    'apb-btn-ghost': 'apb-btn text-gray-600 hover:bg-gray-100',
    'apb-btn-danger': 'apb-btn text-red-600 hover:bg-red-50',
    'apb-icon-btn':
      'inline-flex items-center justify-center h-8 w-8 rounded-lg text-gray-500 transition-colors hover:bg-gray-100 hover:text-gray-900 disabled:opacity-40 disabled:pointer-events-none',
    'apb-chip': 'inline-flex shrink-0 items-center gap-1 whitespace-nowrap rounded-full px-2 py-0.5 text-xs leading-5',
    // border-solid is required: Halo console resets border-style:none on form elements globally.
    'apb-input':
      'w-full min-w-0 rounded-lg border border-solid border-gray-200 bg-white px-2.5 py-1.5 text-[13px] text-gray-900 outline-none transition focus:border-gray-400 disabled:bg-gray-50',
    'apb-panel': 'bg-white border border-gray-200 rounded-xl',
  },
  theme: {
    colors: {
      // Halo console accent, kept for focus rings and active states.
      accent: '#4ccba0',
    },
  },
})
