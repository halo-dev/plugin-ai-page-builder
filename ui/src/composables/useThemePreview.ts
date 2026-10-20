import { computed, shallowRef } from 'vue'
import IconContrast from '~icons/mingcute/brightness-line'
import IconMoon from '~icons/mingcute/moon-line'
import IconSun from '~icons/mingcute/sun-line'
import { aiPageBuilderChatApi } from '../api/clients'
import type { ThemeStyleContext } from '../api/generated'
import { pageKeys, queryClient } from '../lib/query-client'

const themeModes = [
  { value: 'default', icon: IconContrast, title: '主题默认模式' },
  { value: 'light', icon: IconSun, title: '模拟亮色模式' },
  { value: 'dark', icon: IconMoon, title: '模拟暗色模式' },
] as const

export function useThemePreview() {
  const themeContext = shallowRef<ThemeStyleContext>()
  const previewMode = shallowRef<'default' | 'light' | 'dark'>('default')
  const rootStates = computed(() =>
    themeContext.value?.colorMode?.strategy === 'explicit'
      ? themeContext.value.colorMode.rootStates
      : [],
  )
  const rootState = computed(() =>
    rootStates.value.find((state) => state.mode === previewMode.value),
  )
  function cyclePreviewMode() {
    const order = ['default', 'light', 'dark'] as const
    const next = order[(order.indexOf(previewMode.value) + 1) % order.length]!
    previewMode.value = next
  }
  const previewModeIcon = computed(
    () => themeModes.find((mode) => mode.value === previewMode.value) || themeModes[0],
  )
  queryClient
    .fetchQuery({
      queryKey: pageKeys.themeContext,
      queryFn: async () => (await aiPageBuilderChatApi.getThemeContext()).data,
      staleTime: 60_000,
      retry: false,
    })
    .then((context) => {
      themeContext.value = context
    })
    .catch(() => {
      // Theme context is optional; manual editing and validation stay independent.
    })
  return { themeContext, previewMode, rootStates, rootState, previewModeIcon, cyclePreviewMode }
}
