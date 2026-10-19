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
}>()
type SettingsPatch = Partial<Pick<PageDocument, 'title' | 'description'>> & {
  themeLayout?: boolean
}
const emit = defineEmits<{
  close: []
  apply: [value: SettingsPatch]
  unpublish: []
}>()
const buffer = reactive({
  title: props.document.title,
  description: props.document.description || '',
  slug: props.slug ?? '',
  themeLayout: props.themeLayout,
})
const form = useTemplateRef<{ node: { submit: () => void } }>('form')
const modal = useTemplateRef<InstanceType<typeof VModal>>('modal')
const saving = ref(false)
// The modal's writes (slug save and the parent's apply) share the page's
// optimistic-lock version, so no two of them may be in flight at once. Closing
// stays allowed even while busy: VModal hides itself before emitting close, so
// the event cannot be vetoed — parents must tolerate late results instead.
const busy = computed(() => saving.value || props.disabled)
// A parent refresh (e.g. after a version conflict) swaps the document under
// the form: fields the user never touched follow the fresh values, while
// edited fields (buffer diverged from the old props) keep the user's input.
watch(
  () => [props.document, props.slug, props.themeLayout] as const,
  ([doc, slug, themeLayout], [prevDoc, prevSlug, prevThemeLayout]) => {
    if (buffer.title === prevDoc.title) buffer.title = doc.title
    if (buffer.description === (prevDoc.description || ''))
      buffer.description = doc.description || ''
    if (buffer.slug === (prevSlug ?? '')) buffer.slug = slug ?? ''
    if (buffer.themeLayout === prevThemeLayout) buffer.themeLayout = themeLayout
  },
)
async function submit() {
  if (busy.value) return
  const patch: SettingsPatch = {}
  if (buffer.title !== props.document.title) patch.title = buffer.title
  if (buffer.description !== (props.document.description || ''))
    patch.description = buffer.description
  if (buffer.themeLayout !== props.themeLayout) patch.themeLayout = buffer.themeLayout
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
  <VModal title="页面设置" :width="560" @close="emit('close')" ref="modal">
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
        <FormKit
          v-model="buffer.themeLayout"
          type="checkbox"
          name="themeLayout"
          label="复用主题布局"
          help="关闭后页面独立渲染，不套用主题的页头页尾与基础样式。"
        />
      </FormKit>
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
          <VButton @click="modal?.close()">取消</VButton>
        </VSpace>
        <VButton v-if="published" ghost :disabled="busy" @click="emit('unpublish')"
          >取消发布</VButton
        >
      </div>
    </template>
  </VModal>
</template>
