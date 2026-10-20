<script setup lang="ts">
import { computed, nextTick, shallowRef, useTemplateRef, type CSSProperties } from 'vue'
import IconClose from '~icons/mingcute/close-line'
import ImageReplaceDropdown from '../ImageReplaceDropdown.vue'
import type { PageDocument } from '../../api/generated'
import type { CanvasEdit, ElementPath } from '../../editor/document'
import type { AnnotationTarget } from '../../editor/annotations'

const props = defineProps<{
  selected: HTMLElement
  selectedPath: ElementPath
  editable: boolean
  textEditable: boolean
  duplicateAllowed: boolean
  isRootChild: boolean
  annotationCount?: number
  document: PageDocument
  popperStyle?: CSSProperties
}>()
const emit = defineEmits<{
  edit: [edit: CanvasEdit]
  annotate: [target: AnnotationTarget, comment: string]
  'select-parent': []
  close: []
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
  if (!props.editable || (props.annotationCount || 0) >= 5 || props.selectedPath.length > 64) return
  annotationTarget.value = {
    path: [...props.selectedPath],
    tag: props.selected.localName.slice(0, 32),
    summary: (
      props.selected.textContent ||
      props.selected.getAttribute('alt') ||
      props.selected.localName
    )
      .trim()
      .slice(0, 300),
    html: props.selected.outerHTML.slice(0, 4000),
    content: { html: props.document.html, css: props.document.css, js: props.document.js ?? '' },
  }
  annotationComment.value = ''
  void nextTick(() => annotationInput.value?.focus())
}

function submitAnnotation() {
  if (
    !annotationTarget.value ||
    !annotationCurrent.value ||
    !props.editable ||
    !annotationComment.value.trim()
  )
    return
  emit('annotate', annotationTarget.value, annotationComment.value.trim())
  // The parent clears the selection, which closes this panel and its outline.
  emit('close')
}

function apply(edit: CanvasEdit) {
  emit('edit', edit)
}

function valueOf(event: Event): string {
  return (event.target as HTMLInputElement).value
}

function replaceImage(url: string) {
  apply({ type: 'attribute', name: 'src', value: url })
}
</script>

<template>
  <div
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
        :disabled="!editable || isRootChild"
        @click="emit('select-parent')"
      >
        父区块
      </button>
      <button
        type="button"
        class="apb-icon-btn ml-auto h-6 w-6"
        aria-label="取消选择"
        @click="emit('close')"
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
            <button type="button" class="apb-btn-outline h-7 w-full text-xs" :disabled="!editable">
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
</template>
