<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, shallowRef, useTemplateRef, watch } from 'vue'
import IconClose from '~icons/mingcute/close-line'
import IconZoomIn from '~icons/mingcute/zoom-in-line'
import IconZoomOut from '~icons/mingcute/zoom-out-line'
import ImageReplaceDropdown from './ImageReplaceDropdown.vue'
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
import {
  PREVIEW_DEVICES,
  type PreviewDevice,
  MIN_PREVIEW_SCALE,
  MAX_PREVIEW_SCALE,
  adjustPreviewScale,
  calculateFitScale,
} from '../editor/preview-canvas'
import type { PreviewRootState } from '../editor/preview'
import type { AnnotationTarget } from '../editor/annotations'

const props = withDefaults(
  defineProps<{
    name: string
    document: PageDocument
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
const annotationTarget = shallowRef<AnnotationTarget>()
const annotationComment = shallowRef('')
const annotationInput = useTemplateRef<HTMLTextAreaElement>('annotationInput')
const annotationCurrent = computed(
  () =>
    annotationTarget.value?.content.html === props.document.html &&
    annotationTarget.value?.content.css === props.document.css &&
    (annotationTarget.value?.content.js ?? '') === (props.document.js ?? ''),
)
function openAnnotation() {
  if (
    !editable.value ||
    !selected.value ||
    !selectedPath.value ||
    (props.annotationCount || 0) >= 5 ||
    selectedPath.value.length > 64
  )
    return
  annotationTarget.value = {
    path: [...selectedPath.value],
    tag: selected.value.localName.slice(0, 32),
    summary: (
      selected.value.textContent ||
      selected.value.getAttribute('alt') ||
      selected.value.localName
    )
      .trim()
      .slice(0, 300),
    html: selected.value.outerHTML.slice(0, 4000),
    content: { html: props.document.html, css: props.document.css, js: props.document.js ?? '' },
  }
  annotationComment.value = ''
  void nextTick(() => annotationInput.value?.focus())
}
function submitAnnotation() {
  if (
    !annotationTarget.value ||
    !annotationCurrent.value ||
    !editable.value ||
    !annotationComment.value.trim()
  )
    return
  emit('annotate', annotationTarget.value, annotationComment.value.trim())
  select()
}
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
const outline = shallowRef<Record<string, string>>()
const box = shallowRef<{ left: number; top: number; width: number; height: number }>()
let originalRootState: { classes: Set<string>; attributes: Map<string, string | null> } | undefined
let removeListeners = () => {}
let contentObserver: ResizeObserver | undefined
let stageObserver: ResizeObserver | undefined

const stageSize = shallowRef({ width: 0, height: 0 })
const manualScale = shallowRef<number>()
const viewport = computed(() => PREVIEW_DEVICES[props.device])
watch(
  () => props.device,
  () => {
    manualScale.value = undefined
  },
)
const scale = computed(
  () => manualScale.value ?? calculateFitScale(stageSize.value, viewport.value),
)
const scalePercent = computed(() => Math.round(scale.value * 100))
function zoom(direction: -1 | 1) {
  manualScale.value = adjustPreviewScale(scale.value, direction)
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
const textEditable = computed(() => selected.value && canEditText(selected.value))
const duplicateAllowed = computed(() => selected.value && canDuplicate(selected.value))

watch(
  () => [props.name, JSON.stringify(props.document), props.validated, refresh.value] as const,
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
      previous?.[3] === refresh.value
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
      annotationTarget.value = undefined
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
  annotationTarget.value = undefined
  updateOutline()
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
function valueOf(event: Event): string {
  return (event.target as HTMLInputElement).value
}
function replaceImage(url: string) {
  if (!editable.value) return
  apply({ type: 'attribute', name: 'src', value: url })
}
watch(
  stage,
  (element) => {
    stageObserver?.disconnect()
    if (!element || typeof ResizeObserver === 'undefined') return
    stageObserver = new ResizeObserver(() => {
      stageSize.value = { width: element.clientWidth, height: element.clientHeight }
    })
    stageObserver.observe(element)
    stageSize.value = { width: element.clientWidth, height: element.clientHeight }
  },
  { immediate: true },
)
onBeforeUnmount(() => {
  requestId++
  removeListeners()
  contentObserver?.disconnect()
  stageObserver?.disconnect()
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
        <div
          v-if="box && selected"
          :key="selectedPath?.join('.')"
          class="absolute z-[3] w-72 rounded-xl border border-gray-200 bg-white p-3 shadow-lg"
          :style="popperStyle"
          role="dialog"
          aria-label="元素操作"
        >
          <div class="mb-2 flex items-center gap-1 text-xs">
            <strong class="font-mono text-gray-700">{{ selected.localName }}</strong>
            <button
              type="button"
              class="apb-btn-ghost h-6 px-1.5 text-xs"
              :disabled="!editable || selectedIsRootChild"
              @click="selectParent"
            >
              父区块
            </button>
            <button
              type="button"
              class="apb-icon-btn ml-auto h-6 w-6"
              aria-label="取消选择"
              @click="select()"
            >
              <IconClose class="text-xs" />
            </button>
          </div>
          <template v-if="annotationTarget">
            <p class="mb-1.5 line-clamp-2 text-xs leading-5 text-gray-500">
              {{ annotationTarget.tag }} · {{ annotationTarget.summary }}
            </p>
            <textarea
              ref="annotationInput"
              v-model="annotationComment"
              class="apb-input resize-y"
              rows="3"
              maxlength="2000"
              placeholder="描述要让 AI 做的修改"
              aria-label="修改说明"
            />
            <p v-if="!annotationCurrent" class="mt-1 text-xs leading-5 text-amber-600">
              内容已变化，请重新选择元素。
            </p>
            <div class="mt-2 flex gap-1.5">
              <button
                type="button"
                class="apb-btn-primary h-7 flex-1 text-xs"
                :disabled="!editable || !annotationCurrent || !annotationComment.trim()"
                @click="submitAnnotation"
              >
                添加到对话
              </button>
              <button
                type="button"
                class="apb-btn-outline h-7 px-2 text-xs"
                @click="annotationTarget = undefined"
              >
                取消
              </button>
            </div>
          </template>
          <template v-else>
            <button
              type="button"
              class="apb-btn-primary mb-2 h-7 w-full text-xs"
              :disabled="!editable || (annotationCount || 0) >= 5"
              @click="openAnnotation"
            >
              让 AI 修改
            </button>
            <fieldset class="m-0 min-w-0 border-0 p-0" :disabled="!editable">
              <label v-if="textEditable" class="mb-2 flex flex-col gap-1 text-xs text-gray-600">
                文字
                <textarea
                  class="apb-input resize-y"
                  :value="selected.textContent || ''"
                  rows="2"
                  @change="apply({ type: 'text', value: valueOf($event) })"
                />
              </label>
              <p v-else-if="selected.children.length" class="mb-2 text-xs leading-5 text-gray-400">
                包含嵌套内容，请选中文字子元素，或在源码中编辑。
              </p>
              <template v-if="selected.localName === 'img'">
                <label class="mb-2 flex flex-col gap-1 text-xs text-gray-600"
                  >图片说明<input
                    class="apb-input"
                    :value="selected.getAttribute('alt') || ''"
                    @change="apply({ type: 'attribute', name: 'alt', value: valueOf($event) })"
                /></label>
                <ImageReplaceDropdown class="mb-2 block w-full" @select="replaceImage">
                  <button
                    type="button"
                    class="apb-btn-outline h-7 w-full text-xs"
                    :disabled="!editable"
                  >
                    替换图片
                  </button>
                </ImageReplaceDropdown>
              </template>
              <template v-if="selected.localName === 'a'">
                <label class="mb-2 flex flex-col gap-1 text-xs text-gray-600"
                  >链接地址<input
                    class="apb-input"
                    :value="selected.getAttribute('href') || ''"
                    @change="apply({ type: 'attribute', name: 'href', value: valueOf($event) })"
                /></label>
                <label class="mb-2 flex flex-col gap-1 text-xs text-gray-600"
                  >打开方式<select
                    class="apb-input"
                    :value="selected.getAttribute('target') || ''"
                    @change="apply({ type: 'attribute', name: 'target', value: valueOf($event) })"
                  >
                    <option value="">当前窗口</option>
                    <option value="_self">当前窗口（显式）</option>
                    <option value="_blank">新窗口</option>
                  </select></label
                >
              </template>
            </fieldset>
            <div class="flex gap-1">
              <button
                type="button"
                class="apb-btn-outline h-7 flex-1 px-1 text-xs"
                :disabled="!editable || !selected.previousElementSibling"
                @click="apply({ type: 'up' })"
              >
                上移
              </button>
              <button
                type="button"
                class="apb-btn-outline h-7 flex-1 px-1 text-xs"
                :disabled="!editable || !selected.nextElementSibling"
                @click="apply({ type: 'down' })"
              >
                下移
              </button>
              <button
                type="button"
                class="apb-btn-outline h-7 flex-1 px-1 text-xs"
                :disabled="!editable || !duplicateAllowed"
                @click="apply({ type: 'duplicate' })"
              >
                复制
              </button>
              <button
                type="button"
                class="apb-btn-danger h-7 flex-1 px-1 text-xs"
                :disabled="!editable"
                @click="apply({ type: 'delete' })"
              >
                删除
              </button>
            </div>
            <p v-if="!duplicateAllowed" class="mt-1.5 text-xs leading-5 text-gray-400">
              此区块包含 ID，复制可能破坏样式或引用。
            </p>
          </template>
        </div>
      </div>
      <div v-if="!editable" class="absolute inset-3 z-[2]" aria-hidden="true" />
      <div
        class="absolute bottom-6 right-6 z-[5] flex items-center rounded-lg border border-gray-200 bg-white text-xs shadow-sm"
        role="group"
        aria-label="画布缩放"
      >
        <button
          type="button"
          class="apb-icon-btn h-7 w-7"
          aria-label="缩小"
          :disabled="scale <= MIN_PREVIEW_SCALE"
          @click="zoom(-1)"
        >
          <IconZoomOut class="text-sm" />
        </button>
        <button
          type="button"
          class="h-7 min-w-12 px-1 text-center text-gray-600 hover:bg-gray-50"
          title="适应画布"
          aria-label="适应画布"
          @click="manualScale = undefined"
        >
          {{ scalePercent }}%
        </button>
        <button
          type="button"
          class="apb-icon-btn h-7 w-7"
          aria-label="放大"
          :disabled="scale >= MAX_PREVIEW_SCALE"
          @click="zoom(1)"
        >
          <IconZoomIn class="text-sm" />
        </button>
      </div>
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
