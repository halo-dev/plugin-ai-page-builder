<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, useTemplateRef, watch } from 'vue'
import { Toast, VButton, VModal, VSpace } from '@halo-dev/components'
import type { PageDetail, PageDocument } from '../api/generated'
import { errorMessage } from '../utils/error'
import { slugOptionalValidation, slugValidation } from '../utils/slug'
const props = defineProps<{
  document: PageDocument
  slug?: string | null
  themeLayout: boolean
  disabled: boolean
  published: boolean
  saveSlug: (slug: string) => Promise<PageDetail | undefined>
  saveLayout: (themeLayout: boolean) => Promise<PageDetail | undefined>
}>()
const emit = defineEmits<{
  close: []
  apply: [value: Partial<Pick<PageDocument, 'title' | 'description'>>]
  unpublish: []
}>()
const buffer = reactive({
  title: props.document.title,
  description: props.document.description || '',
  slug: props.slug ?? '',
})
const form = useTemplateRef<{ node: { submit: () => void } }>('form')
const saving = ref(false)
const layoutOn = ref(props.themeLayout)
const layoutSaving = ref(false)
// Serialize the modal's writes: every operation (slug, layout, apply,
// unpublish via the disabled prop) shares the page's optimistic-lock version,
// so no two of them may be in flight at once. Closing stays allowed even
// while busy: VModal hides itself before emitting close, so the event cannot
// be vetoed — parents must tolerate late results instead.
const busy = computed(() => saving.value || layoutSaving.value || props.disabled)
watch(
  () => props.themeLayout,
  (value) => (layoutOn.value = value),
)
// A parent refresh (e.g. after a version conflict) swaps the document under
// the form: fields the user never touched follow the fresh values, while
// edited fields (buffer diverged from the old props) keep the user's input.
watch(
  () => [props.document, props.slug] as const,
  ([doc, slug], [prevDoc, prevSlug]) => {
    if (buffer.title === prevDoc.title) buffer.title = doc.title
    if (buffer.description === (prevDoc.description || ''))
      buffer.description = doc.description || ''
    if (buffer.slug === (prevSlug ?? '')) buffer.slug = slug ?? ''
  },
)
// FormKit emits change before the v-model update lands, so take the target
// value from update:model-value instead of reading the bound ref.
async function toggleLayout(value: unknown) {
  const target = value === true
  if (busy.value || target === props.themeLayout) return
  layoutOn.value = target
  layoutSaving.value = true
  try {
    if (!(await props.saveLayout(target))) layoutOn.value = !target
  } catch (e) {
    layoutOn.value = !target
    Toast.error(errorMessage(e))
  } finally {
    layoutSaving.value = false
  }
}
async function submit() {
  if (busy.value) return
  const patch: Partial<Pick<PageDocument, 'title' | 'description'>> = {}
  if (buffer.title !== props.document.title) patch.title = buffer.title
  if (buffer.description !== (props.document.description || ''))
    patch.description = buffer.description
  // The slug saves through its own API and takes effect immediately, unlike
  // title/description which the parent applies to the working document.
  if (buffer.slug !== (props.slug ?? '')) {
    saving.value = true
    try {
      if (!(await props.saveSlug(buffer.slug))) return
      Toast.success('访问路径已保存')
    } catch (e) {
      Toast.error(errorMessage(e))
      return
    } finally {
      saving.value = false
    }
  }
  // The modal may have closed while the slug save was in flight; a dead
  // instance must not emit into whatever modal session is open now.
  if (!alive) return
  emit('apply', patch)
}
let alive = true
onBeforeUnmount(() => {
  alive = false
})
</script>
<template>
  <VModal title="页面设置" :width="560" @close="emit('close')">
    <!-- Wrap conditional content in a stable container for VModal's default slot. -->
    <div>
      <FormKit
        ref="form"
        type="form"
        :actions="false"
        :disabled="disabled || saving"
        @submit="submit"
      >
        <FormKit
          v-model="buffer.title"
          type="text"
          name="title"
          label="标题"
          validation="required|length:1,200"
        />
        <FormKit
          v-model="buffer.description"
          type="textarea"
          name="description"
          label="描述"
          validation="length:0,1000"
          :maxlength="1000"
        />
        <!-- Pages without a slug may stay empty; an existing slug cannot be cleared. -->
        <FormKit
          v-model="buffer.slug"
          type="text"
          name="slug"
          label="页面路径"
          placeholder="company/about"
          help="使用小写字母、数字和连字符，支持 / 分隔的多级路径，不含首尾斜线。保存后立即生效。"
          :validation="slug ? slugValidation : slugOptionalValidation"
          :maxlength="200"
        />
      </FormKit>
      <div class="my-5 flex items-center gap-3">
        <p class="min-w-0 flex-1 text-xs text-gray-500">
          复用主题布局
          <span class="block">关闭后页面独立渲染，不套用主题的页头页尾与基础样式。</span>
        </p>
        <FormKit
          :model-value="layoutOn"
          type="checkbox"
          name="themeLayout"
          :disabled="busy"
          @update:model-value="toggleLayout"
        />
      </div>
    </div>
    <template #footer>
      <div class="flex items-center justify-between gap-3">
        <VSpace>
          <VButton
            type="secondary"
            :loading="saving || disabled"
            :disabled="busy"
            @click="form?.node.submit()"
            >应用</VButton
          >
          <VButton @click="emit('close')">取消</VButton>
        </VSpace>
        <VButton v-if="published" ghost :disabled="busy" @click="emit('unpublish')"
          >取消发布</VButton
        >
      </div>
    </template>
  </VModal>
</template>
