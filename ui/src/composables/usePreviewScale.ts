import { computed, onScopeDispose, shallowRef, watch, type Ref } from 'vue'
import {
  PREVIEW_DEVICES,
  adjustPreviewScale,
  calculateFitScale,
  type PreviewDevice,
} from '../editor/preview-canvas'

export function usePreviewScale(
  device: () => PreviewDevice,
  stage: Readonly<Ref<HTMLElement | null | undefined>>,
) {
  const stageSize = shallowRef({ width: 0, height: 0 })
  const manualScale = shallowRef<number>()
  const viewport = computed(() => PREVIEW_DEVICES[device()])
  watch(device, () => {
    manualScale.value = undefined
  })
  const scale = computed(
    () => manualScale.value ?? calculateFitScale(stageSize.value, viewport.value),
  )
  function zoom(direction: -1 | 1) {
    manualScale.value = adjustPreviewScale(scale.value, direction)
  }
  function fit() {
    manualScale.value = undefined
  }
  const wrapStyle = computed(() => ({
    width: `${viewport.value.width * scale.value}px`,
    height: `${stageSize.value.height || viewport.value.height * scale.value}px`,
  }))
  const frameStyle = computed(() => ({
    width: `${viewport.value.width}px`,
    height: `${stageSize.value.height ? stageSize.value.height / scale.value : viewport.value.height}px`,
    transform: `scale(${scale.value})`,
    transformOrigin: 'top left',
  }))

  let observer: ResizeObserver | undefined
  watch(
    stage,
    (element) => {
      observer?.disconnect()
      if (!element || typeof ResizeObserver === 'undefined') return
      observer = new ResizeObserver(() => {
        stageSize.value = { width: element.clientWidth, height: element.clientHeight }
      })
      observer.observe(element)
      stageSize.value = { width: element.clientWidth, height: element.clientHeight }
    },
    { immediate: true },
  )
  onScopeDispose(() => observer?.disconnect())

  return {
    stageSize,
    viewport,
    scale,
    wrapStyle,
    frameStyle,
    zoom,
    fit,
  }
}
