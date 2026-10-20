<script setup lang="ts">
import { reactive, useTemplateRef, watch } from 'vue'
import { VButton, VModal, VSpace } from '@halo-dev/components'
import type { PageSummary } from '../api/generated'
import type { PagePropertiesPatch } from '../composables/usePageWrites'
import { slugOptionalValidation, slugValidation } from '../utils/slug'
const props = defineProps<{
  page: PageSummary
  disabled: boolean
  published: boolean
}>()
const emit = defineEmits<{
  close: []
  apply: [value: PagePropertiesPatch]
  unpublish: []
}>()
const buffer = reactive({
  title: props.page.title,
  description: props.page.description || '',
  slug: props.page.slug ?? '',
  themeLayout: props.page.themeLayout,
})
const form = useTemplateRef<{ node: { submit: () => void } }>('form')
const modal = useTemplateRef<InstanceType<typeof VModal>>('modal')
// Keep edited inputs on conflict while untouched fields follow the refreshed page.
watch(
  () => props.page,
  (page, previous) => {
    if (buffer.title === previous.title) buffer.title = page.title
    if (buffer.description === (previous.description || ''))
      buffer.description = page.description || ''
    if (buffer.slug === (previous.slug ?? '')) buffer.slug = page.slug ?? ''
    if (buffer.themeLayout === previous.themeLayout) buffer.themeLayout = page.themeLayout
  },
)
function submit() {
  if (props.disabled) return
  const patch: PagePropertiesPatch = {}
  if (buffer.title !== props.page.title) patch.title = buffer.title
  if (buffer.description !== (props.page.description || '')) patch.description = buffer.description
  if (buffer.slug !== (props.page.slug ?? '')) patch.slug = buffer.slug
  if (buffer.themeLayout !== props.page.themeLayout) patch.themeLayout = buffer.themeLayout
  emit('apply', patch)
}
</script>
<template>
  <VModal title="页面设置" :width="560" @close="emit('close')" ref="modal">
    <!-- Wrap conditional content in a stable container for VModal's default slot. -->
    <div>
      <p class="mb-4 text-sm text-gray-500">
        属性保存后立即生效，历史版本只记录页面内容，不包含这些属性。
      </p>
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
        <!-- Pages without a slug may stay empty; an existing slug cannot be cleared. -->
        <FormKit
          v-model="buffer.slug"
          type="text"
          name="slug"
          label="页面路径"
          placeholder="company/about"
          help="使用小写字母、数字和连字符，支持 / 分隔的多级路径，不含首尾斜线。保存后立即生效。"
          :validation="page.slug ? slugValidation : slugOptionalValidation"
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
            :loading="disabled"
            :disabled="disabled"
            @click="form?.node.submit()"
            >保存</VButton
          >
          <VButton @click="modal?.close()">取消</VButton>
        </VSpace>
        <VButton v-if="published" ghost :disabled="disabled" @click="emit('unpublish')"
          >取消发布</VButton
        >
      </div>
    </template>
  </VModal>
</template>
