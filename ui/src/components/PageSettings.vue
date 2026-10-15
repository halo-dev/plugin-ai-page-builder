<script setup lang="ts">
import { reactive, ref, useTemplateRef, watch } from 'vue'
import { Toast, VAlert, VButton, VModal, VSpace } from '@halo-dev/components'
import type { PageDetail, PageDocument } from '../api/generated'
import { errorMessage } from '../utils/error'
import { slugValidation } from '../utils/slug'
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
})
const form = useTemplateRef<{ node: { submit: () => void } }>('form')
const slugForm = useTemplateRef<{ node: { submit: () => void } }>('slugForm')
const editingSlug = ref(false)
const slugBuffer = ref(props.slug ?? '')
const savingSlug = ref(false)
const slugError = ref('')
const layoutOn = ref(props.themeLayout)
const layoutSaving = ref(false)
watch(
  () => props.themeLayout,
  (value) => (layoutOn.value = value),
)
// FormKit emits change before the v-model update lands, so take the target
// value from update:model-value instead of reading the bound ref.
async function toggleLayout(value: unknown) {
  const target = value === true
  if (props.disabled || layoutSaving.value || target === props.themeLayout) return
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
function editSlug() {
  slugBuffer.value = props.slug ?? ''
  slugError.value = ''
  editingSlug.value = true
}
async function submitSlug() {
  if (props.disabled || savingSlug.value) return
  savingSlug.value = true
  slugError.value = ''
  try {
    if (await props.saveSlug(slugBuffer.value)) {
      editingSlug.value = false
      Toast.success('访问路径已保存')
    }
  } catch (e) {
    slugError.value = errorMessage(e)
  } finally {
    savingSlug.value = false
  }
}
function submit() {
  if (props.disabled) return
  const patch: Partial<Pick<PageDocument, 'title' | 'description'>> = {}
  if (buffer.title !== props.document.title) patch.title = buffer.title
  if (buffer.description !== (props.document.description || ''))
    patch.description = buffer.description
  emit('apply', patch)
}
</script>
<template>
  <VModal
    :title="editingSlug ? '修改访问路径' : '页面设置'"
    :width="560"
    @close="!savingSlug && emit('close')"
  >
    <!-- Wrap conditional content in a stable container for VModal's default slot. -->
    <div>
      <div v-show="!editingSlug">
        <FormKit ref="form" type="form" :actions="false" :disabled="disabled" @submit="submit">
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
        </FormKit>
        <div class="my-5 flex items-center gap-3">
          <p class="min-w-0 flex-1 break-all text-xs text-gray-500">
            页面路径
            <a
              v-if="published && slug"
              :href="`/${slug}`"
              target="_blank"
              rel="noopener"
              class="hover:underline"
              >/{{ slug }}</a
            >
            <span v-else>{{ slug ? '/' + slug : '未设置' }}</span>
          </p>
          <VButton size="sm" :disabled="disabled" @click="editSlug">修改</VButton>
        </div>
        <div class="my-5 flex items-center gap-3">
          <p class="min-w-0 flex-1 text-xs text-gray-500">
            复用主题布局
            <span class="block">关闭后页面独立渲染，不套用主题的页头页尾与基础样式。</span>
          </p>
          <FormKit
            :model-value="layoutOn"
            type="checkbox"
            name="themeLayout"
            :disabled="disabled || layoutSaving"
            @update:model-value="toggleLayout"
          />
        </div>
        <VButton v-if="published" :disabled="disabled" @click="emit('unpublish')">取消发布</VButton>
      </div>
      <div v-if="editingSlug">
        <p class="mb-4 text-sm text-gray-500">保存后访问路径立即生效。</p>
        <VAlert v-if="slugError" type="error" :title="slugError" class="mb-4" />
        <FormKit
          ref="slugForm"
          type="form"
          :actions="false"
          :disabled="disabled || savingSlug"
          @submit="submitSlug"
        >
          <FormKit
            v-model="slugBuffer"
            type="text"
            name="slug"
            label="访问路径"
            placeholder="company/about"
            help="使用小写字母、数字和连字符，支持 / 分隔的多级路径，不含首尾斜线。"
            :validation="slugValidation"
            :maxlength="200"
          />
        </FormKit>
      </div>
    </div>
    <template #footer>
      <VSpace v-if="editingSlug">
        <VButton
          type="secondary"
          :loading="savingSlug"
          :disabled="disabled || savingSlug"
          @click="slugForm?.node.submit()"
          >保存路径</VButton
        >
        <VButton :disabled="savingSlug" @click="editingSlug = false">取消</VButton>
      </VSpace>
      <VSpace v-else>
        <VButton type="secondary" :disabled="disabled" @click="form?.node.submit()">应用</VButton>
        <VButton @click="emit('close')">取消</VButton>
      </VSpace>
    </template>
  </VModal>
</template>
