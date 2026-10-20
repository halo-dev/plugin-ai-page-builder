<script setup lang="ts">
import { computed, onBeforeUnmount, shallowRef, useTemplateRef, watch } from 'vue'
import CanvasZoomControls from './editor/CanvasZoomControls.vue'
import ElementInspector from './editor/ElementInspector.vue'
import type { PageDocument } from '../api/generated'
import { pagePreviewUrl, renderPagePreview } from '../api/clients'
import { errorMessage } from '../utils/error'
import {
  canDuplicate,
  canEditText,
  editCanvasDocument,
  elementAtPath,
  elementPath,
  sameElementStructure,
  type CanvasEdit,
  type ElementPath,
} from '../editor/document'
import type { PreviewDevice } from '../editor/preview-canvas'
import { usePreviewScale } from '../composables/usePreviewScale'
import type { PreviewRootState } from '../editor/preview'
import type { AnnotationTarget } from '../editor/annotations'

const props = withDefaults(
  defineProps<{
    name: string
    document: PageDocument
    themeLayout: boolean
    validated: boolean
    disabled?: boolean
    device?: PreviewDevice
    rootState?: PreviewRootState
    rootStates?: PreviewRootState[]
    annotationCount?: number
  }>(),
  { disabled: false, device: 'desktop' },
)
const emit = defineEmits<{
  'update:document': [document: PageDocument]
  annotate: [target: AnnotationTarget, comment: string]
}>()
// Use the server's complete theme render for each validated snapshot. Scripts
// stay disabled: editable DOM must retain the source document's node paths.
const rootId = computed(() => `ai-page-builder-${props.name}`)
const frame = shallowRef<HTMLIFrameElement>()
const frameWrap = useTemplateRef<HTMLElement>('frameWrap')
const stage = useTemplateRef<HTMLElement>('stage')
const rendered = shallowRef<PageDocument>()
const preview = shallowRef<{ source: string; snapshot: PageDocument; name: string; id: number }>()
const previewError = shallowRef('')
const refresh = shallowRef(0)
const sameDocument = (a: PageDocument | undefined, b: PageDocument) =>
  !!a && JSON.stringify(a) === JSON.stringify(b)
let requestId = 0
const ready = shallowRef(false)
const pending = shallowRef(false)
const unsupported = shallowRef(false)
const selectedPath = shallowRef<ElementPath>()
const selected = shallowRef<HTMLElement>()
// Remount the inspector only when the selection changes; document updates and
// layout refreshes must keep an in-progress annotation draft alive.
const selectionEpoch = shallowRef(0)
const outline = shallowRef<Record<string, string>>()
const box = shallowRef<{ left: number; top: number; width: number; height: number }>()
let originalRootState: { classes: Set<string>; attributes: Map<string, string | null> } | undefined
let removeListeners = () => {}
let contentObserver: ResizeObserver | undefined

const { stageSize, viewport, scale, wrapStyle, frameStyle, zoom, fit } = usePreviewScale(
  () => props.device,
  stage,
)

function containerEl(): HTMLElement | undefined {
  return (
    (frame.value?.contentDocument?.getElementById(rootId.value) as HTMLElement | null) ?? undefined
  )
}
const selectedIsRootChild = computed(() => selected.value?.parentElement === containerEl())

const editable = computed(
  () =>
    props.validated &&
    !props.disabled &&
    ready.value &&
    !pending.value &&
    !unsupported.value &&
    !previewError.value &&
    sameDocument(rendered.value, props.document),
)
const textEditable = computed(() => !!selected.value && canEditText(selected.value))
const duplicateAllowed = computed(() => !!selected.value && canDuplicate(selected.value))

watch(
  () =>
    [
      props.name,
      JSON.stringify(props.document),
      props.validated,
      refresh.value,
      props.themeLayout,
    ] as const,
  async (_, previous, onCleanup) => {
    const id = ++requestId
    const controller = new AbortController()
    onCleanup(() => controller.abort())
    if (previous && previous[0] !== props.name) {
      preview.value = undefined
      rendered.value = undefined
      ready.value = false
      select()
    }
    if (!props.validated) return
    if (
      ready.value &&
      !unsupported.value &&
      sameDocument(rendered.value, props.document) &&
      sameDocument(preview.value?.snapshot, props.document) &&
      previous?.[3] === refresh.value &&
      previous?.[4] === props.themeLayout
    ) {
      previewError.value = ''
      pending.value = false
      return
    }
    const snapshot = JSON.parse(JSON.stringify(props.document)) as PageDocument
    const name = props.name
    if (!unsupported.value) previewError.value = ''
    try {
      const source = await renderPagePreview(name, snapshot, controller.signal)
      if (
        id !== requestId ||
        controller.signal.aborted ||
        !props.validated ||
        name !== props.name ||
        !sameDocument(snapshot, props.document)
      )
        return
      ready.value = false
      originalRootState = undefined
      pending.value = false
      selected.value = undefined
      outline.value = undefined
      box.value = undefined
      selectionEpoch.value++
      const page = new DOMParser().parseFromString(source, 'text/html')
      // Preserve the original markup: serializing a parsed <pre> can strip a
      // second leading newline when srcdoc parses it again.
      const base = page.createElement('base')
      base.href = new URL(pagePreviewUrl(name), window.location.href).href
      const markup = page.querySelector('base[href]')
        ? source
        : source.replace(/<head(?:\s[^>]*)?>/i, (head) => head + base.outerHTML)
      preview.value = {
        source: markup,
        snapshot,
        name,
        id,
      }
    } catch (e) {
      if (id === requestId && !controller.signal.aborted) previewError.value = errorMessage(e)
    }
  },
  { immediate: true },
)

function applyRootState() {
  const root = frame.value?.contentDocument?.documentElement
  if (!root || !originalRootState) return
  const states = [...(props.rootStates || []), ...(props.rootState ? [props.rootState] : [])]
  const classes = new Set(states.flatMap((state) => state.classes))
  const attributes = new Set(states.flatMap((state) => Object.keys(state.attributes)))
  for (const name of attributes) {
    if (!originalRootState.attributes.has(name))
      originalRootState.attributes.set(name, root.getAttribute(name))
  }
  for (const name of classes)
    root.classList.toggle(
      name,
      props.rootState
        ? props.rootState.classes.includes(name)
        : originalRootState.classes.has(name),
    )
  for (const name of attributes) {
    const value = props.rootState
      ? props.rootState.attributes[name]
      : originalRootState.attributes.get(name)
    if (value == null) root.removeAttribute(name)
    else root.setAttribute(name, value)
  }
}
watch(
  () => [props.rootState, props.rootStates],
  () => {
    applyRootState()
    updateOutline()
  },
  { flush: 'post' },
)

const stageScroll = shallowRef({ left: 0, top: 0 })
function onStageScroll() {
  stageScroll.value = { left: stage.value?.scrollLeft || 0, top: stage.value?.scrollTop || 0 }
}
const POPPER_WIDTH = 288 // w-72
const POPPER_HEIGHT_EST = 340
const popperStyle = computed(() => {
  if (!box.value) return undefined
  const stageWidth = stage.value?.clientWidth || 800
  const stageHeight = stage.value?.clientHeight || 600
  const { left: scrollLeft, top: scrollTop } = stageScroll.value
  const left = Math.min(
    Math.max(scrollLeft + 8, box.value.left),
    Math.max(scrollLeft + 8, scrollLeft + stageWidth - POPPER_WIDTH - 8),
  )
  const belowTop = box.value.top + box.value.height + 8
  if (belowTop >= scrollTop + 8 && belowTop + POPPER_HEIGHT_EST <= scrollTop + stageHeight)
    return { left: `${left}px`, top: `${belowTop}px` }
  const aboveTop = box.value.top - 8
  if (aboveTop - POPPER_HEIGHT_EST >= scrollTop + 8 && aboveTop <= scrollTop + stageHeight - 8)
    return { left: `${left}px`, top: `${aboveTop}px`, transform: 'translateY(-100%)' }
  // Element fills the stage: neither side fits, overlay its top edge instead of clipping.
  return {
    left: `${left}px`,
    top: `${Math.min(Math.max(scrollTop + 8, box.value.top + 8), Math.max(scrollTop + 8, scrollTop + stageHeight - POPPER_HEIGHT_EST - 8))}px`,
  }
})
function updateOutline() {
  const element = selected.value
  if (!element?.isConnected) {
    outline.value = undefined
    box.value = undefined
    return
  }
  // Frame geometry is in the iframe's layout viewport; scale it back into
  // stage coordinates and add the wrapper's offset within the stage.
  const rect = element.getBoundingClientRect()
  const offsetLeft = frameWrap.value?.offsetLeft ?? 0
  const offsetTop = frameWrap.value?.offsetTop ?? 0
  const left = rect.left * scale.value + offsetLeft
  const top = rect.top * scale.value + offsetTop
  const width = rect.width * scale.value
  const height = rect.height * scale.value
  outline.value = {
    left: `${rect.left * scale.value}px`,
    top: `${rect.top * scale.value}px`,
    width: `${width}px`,
    height: `${height}px`,
  }
  box.value = { left, top, width, height }
}
watch([scale, viewport, stageSize], updateOutline, { flush: 'post' })
function select(element?: HTMLElement) {
  const root = containerEl()
  // Paths only resolve inside the content container; clicks on theme chrome
  // (header/footer) or the container itself clear the selection.
  selectedPath.value = element && root ? elementPath(root, element) : undefined
  selected.value = selectedPath.value ? element : undefined
  if (selected.value) contentObserver?.observe(selected.value)
  selectionEpoch.value++
  updateOutline()
}
function annotate(target: AnnotationTarget, comment: string) {
  emit('annotate', target, comment)
}
function selectParent() {
  select(selected.value?.parentElement || undefined)
}
function intercept(event: Event) {
  event.preventDefault()
  if (!editable.value || event.type !== 'click') return
  const target = event.target as HTMLElement | null
  if (target?.nodeType === 1) select(target)
}
function onLoad(event: Event) {
  if (event.target !== frame.value || !preview.value || preview.value.name !== props.name) return
  removeListeners()
  contentObserver?.disconnect()
  const doc = frame.value?.contentDocument
  const root = containerEl()
  if (!doc || !root) {
    // The theme does not honour the ai-page-builder template contract.
    unsupported.value = true
    return
  }
  unsupported.value = false
  if (sameDocument(preview.value.snapshot, props.document)) previewError.value = ''
  ready.value = true
  originalRootState = { classes: new Set(doc.documentElement.classList), attributes: new Map() }
  applyRootState()
  const events = ['click', 'auxclick', 'submit', 'dragstart', 'drop', 'beforeinput']
  events.forEach((type) => doc.addEventListener(type, intercept, true))
  const layoutEvents = ['scroll', 'transitionend', 'transitioncancel']
  layoutEvents.forEach((type) => doc.addEventListener(type, updateOutline, true))
  frame.value?.contentWindow?.addEventListener('resize', updateOutline)
  removeListeners = () => {
    events.forEach((type) => doc.removeEventListener(type, intercept, true))
    layoutEvents.forEach((type) => doc.removeEventListener(type, updateOutline, true))
    doc.defaultView?.removeEventListener('resize', updateOutline)
  }
  rendered.value = preview.value!.snapshot
  const expected = doc.implementation.createHTMLDocument('').body
  expected.innerHTML = rendered.value.html
  // Source paths only depend on the element tree; attribute-only rewrites by the
  // render pipeline (Halo adds srcset/sizes to /upload images) are expected here.
  // Any theme-inserted node (including scripts) would still shift source paths.
  if (!sameElementStructure(root, expected)) {
    ready.value = false
    unsupported.value = true
    previewError.value = '页面正文与预览结构不一致，请检查 HTML 标签闭合或主题模板后重试。'
    return
  }
  selected.value = selectedPath.value ? elementAtPath(root, selectedPath.value) : undefined
  if (typeof ResizeObserver !== 'undefined') {
    contentObserver = new ResizeObserver(updateOutline)
    contentObserver.observe(root)
    if (selected.value) contentObserver.observe(selected.value)
  }
  updateOutline()
}
function apply(edit: CanvasEdit, path = selectedPath.value) {
  if (!editable.value) return
  const next = editCanvasDocument(props.document, path, edit)
  if (next === props.document) return
  // Freeze synchronously: another event cannot edit the old DOM before props update.
  pending.value = true
  emit('update:document', next)
}
onBeforeUnmount(() => {
  requestId++
  removeListeners()
  contentObserver?.disconnect()
})
</script>

<template>
  <div class="flex h-full min-w-0 flex-col">
    <div class="relative min-h-0 flex-1 bg-gray-100 p-3">
      <div ref="stage" class="relative h-full overflow-auto" @scroll="onStageScroll">
        <div ref="frameWrap" class="relative mx-auto bg-white" :style="wrapStyle">
          <iframe
            v-if="preview"
            :key="preview.id"
            ref="frame"
            class="absolute left-0 top-0 block border-0"
            :style="frameStyle"
            :srcdoc="preview.source"
            sandbox="allow-same-origin"
            title="页面编辑画布"
            @load="onLoad"
          />
          <div class="pointer-events-none absolute inset-0 overflow-hidden" aria-hidden="true">
            <div
              v-if="outline && editable"
              class="absolute z-[1] border-2 border-indigo-500"
              :style="outline"
            />
          </div>
        </div>
        <ElementInspector
          v-if="box && selected && selectedPath"
          :key="selectionEpoch"
          :selected="selected"
          :selected-path="selectedPath"
          :editable="editable"
          :text-editable="textEditable"
          :duplicate-allowed="duplicateAllowed"
          :is-root-child="selectedIsRootChild"
          :annotation-count="annotationCount"
          :document="document"
          :popper-style="popperStyle"
          @edit="apply"
          @annotate="annotate"
          @select-parent="selectParent"
          @close="select()"
        />
      </div>
      <div v-if="!editable" class="absolute inset-3 z-[2]" aria-hidden="true" />
      <CanvasZoomControls :scale="scale" @zoom="zoom" @fit="fit" />
      <div
        v-if="unsupported || previewError"
        class="absolute inset-3 z-[4] flex flex-col items-center justify-center gap-3 bg-white text-sm text-gray-500"
      >
        <p>{{ previewError || '当前主题不支持画布内预览，编辑请使用源码模式。' }}</p>
        <p v-if="!validated">请先修正源码并等待校验通过。</p>
        <button type="button" class="apb-btn-outline" :disabled="!validated" @click="refresh++">
          重新加载画布
        </button>
        <a
          class="apb-btn-outline h-7 px-3 text-xs"
          :href="pagePreviewUrl(name)"
          target="_blank"
          rel="noopener"
          >新窗口打开预览</a
        >
      </div>
    </div>
  </div>
</template>
