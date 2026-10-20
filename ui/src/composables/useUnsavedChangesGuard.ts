import { onBeforeUnmount } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'
import { Toast } from '@halo-dev/components'

export function useUnsavedChangesGuard(options: {
  dirty: () => boolean
  locked: () => boolean
  confirm: () => Promise<boolean>
}) {
  async function leave() {
    if (options.locked()) {
      Toast.info('请等待当前操作完成，或先停止助手。')
      return false
    }
    return !options.dirty() || options.confirm()
  }
  onBeforeRouteLeave(leave)
  onBeforeRouteUpdate((to, from) => to.params.name === from.params.name || leave())
  function beforeUnload(event: BeforeUnloadEvent) {
    if (options.dirty() || options.locked()) {
      event.preventDefault()
      event.returnValue = ''
    }
  }
  window.addEventListener('beforeunload', beforeUnload)
  onBeforeUnmount(() => window.removeEventListener('beforeunload', beforeUnload))
}
