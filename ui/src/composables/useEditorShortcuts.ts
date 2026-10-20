import { onBeforeUnmount } from 'vue'

export function useEditorShortcuts(options: {
  save: () => void
  move: (direction: 'undo' | 'redo') => void
  undoDisabled: () => boolean
}) {
  function keydown(event: KeyboardEvent) {
    if (!(event.ctrlKey || event.metaKey)) return
    if (event.key.toLowerCase() === 's') {
      event.preventDefault()
      options.save()
    }
    const target = event.target instanceof HTMLElement ? event.target : undefined
    const nativeInput = target?.closest('input, textarea, [contenteditable="true"]')
    if (
      event.key.toLowerCase() === 'z' &&
      !options.undoDisabled() &&
      (!nativeInput || target?.closest('.apb-code'))
    ) {
      event.preventDefault()
      event.stopPropagation()
      options.move(event.shiftKey ? 'redo' : 'undo')
    }
  }
  // Capture so the editor wins over per-widget handlers, like the previous inline listener.
  window.addEventListener('keydown', keydown, true)
  onBeforeUnmount(() => window.removeEventListener('keydown', keydown, true))
}
