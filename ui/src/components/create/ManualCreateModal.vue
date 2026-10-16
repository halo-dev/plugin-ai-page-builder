<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useMutation } from '@tanstack/vue-query'
import { Toast, VButton, VModal, VSpace } from '@halo-dev/components'
import { aiPageBuilderApi } from '../../api/clients'
import { errorMessage } from '../../utils/error'
import { pageKeys, queryClient } from '../../lib/query-client'

const emit = defineEmits<{ close: [] }>()
const router = useRouter()
const form = ref({ title: '', themeLayout: true })
const formNode = ref<{ submit: () => void }>()
const createMutation = useMutation({
  mutationFn: async (value: { title: string; themeLayout: boolean }) =>
    (await aiPageBuilderApi.createPage({ createPageRequest: value })).data,
  onSuccess: (detail) => {
    void queryClient.invalidateQueries({ queryKey: pageKeys.all })
    void router.push({ name: 'AiPageBuilderEditor', params: { name: detail.page.name } })
  },
  onError: (e) => Toast.error(errorMessage(e)),
  queryClient,
})
function create() {
  if (!createMutation.isPending.value) createMutation.mutate(form.value)
}
</script>

<template>
  <VModal
    title="手动创建页面"
    :width="500"
    @close="!createMutation.isPending.value && emit('close')"
  >
    <FormKit
      v-model="form"
      type="form"
      :actions="false"
      :disabled="createMutation.isPending.value"
      @node="formNode = $event"
      @submit="create"
    >
      <FormKit
        type="text"
        name="title"
        label="页面标题"
        placeholder="例如：关于我们"
        validation="required|length:1,200"
      />
      <FormKit
        type="checkbox"
        name="themeLayout"
        label="复用主题布局"
        help="关闭后页面独立渲染，不套用主题的页头页尾与基础样式。"
      />
    </FormKit>
    <template #footer>
      <VSpace>
        <VButton
          :loading="createMutation.isPending.value"
          :disabled="createMutation.isPending.value"
          type="secondary"
          @click="formNode?.submit()"
        >
          创建页面
        </VButton>
        <VButton :disabled="createMutation.isPending.value" @click="emit('close')"> 取消 </VButton>
      </VSpace>
    </template>
  </VModal>
</template>
