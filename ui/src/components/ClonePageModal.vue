<script setup lang="ts">
import { ref, shallowRef, useTemplateRef } from 'vue'
import { Toast, VButton, VModal, VSpace } from '@halo-dev/components'
import { useMutation } from '@tanstack/vue-query'
import { aiPageBuilderApi } from '../api/clients'
import { errorMessage } from '../utils/error'
import { slugValidation } from '../utils/slug'
import { pageKeys, queryClient } from '../lib/query-client'
import type { PageSummary } from '../api/generated'
const props = defineProps<{ page: PageSummary }>()
const emit = defineEmits<{ close: []; created: [name: string] }>()
const form = ref({ title: `${props.page.title} 副本`.slice(0, 200), slug: '' })
const formNode = shallowRef<{ submit: () => void }>()
const modal = useTemplateRef<InstanceType<typeof VModal>>('modal')
const mutation = useMutation({
  mutationFn: async (value: { title: string; slug: string }) =>
    (
      await aiPageBuilderApi.clonePage({
        name: props.page.name,
        clonePageRequest: { version: props.page.version, ...value },
      })
    ).data,
  onSuccess: (result) => {
    void queryClient.invalidateQueries({ queryKey: pageKeys.all })
    Toast.success('已克隆为新的未发布草稿')
    emit('created', result.page.name)
  },
  onError: (e) => Toast.error(errorMessage(e)),
  queryClient,
})
function clone() {
  if (!mutation.isPending.value) mutation.mutate(form.value)
}
</script>

<template>
  <VModal
    title="克隆页面"
    :width="500"
    @close="!mutation.isPending.value && emit('close')"
    ref="modal"
  >
    <p class="mb-4 text-[13px] leading-6 text-gray-500">
      复制「{{ page.title }}」已保存的草稿，新页面不会自动发布。
    </p>
    <FormKit
      v-model="form"
      type="form"
      :actions="false"
      :disabled="mutation.isPending.value"
      @node="formNode = $event"
      @submit="clone"
    >
      <FormKit type="text" name="title" label="页面标题" validation="required|length:1,200" />
      <FormKit
        type="text"
        name="slug"
        label="新访问路径"
        placeholder="company/about-copy"
        help="使用小写字母、数字和连字符，支持 / 分隔的多级路径，不含首尾斜线。"
        :validation="slugValidation"
        :maxlength="200"
      />
    </FormKit>
    <template #footer
      ><VSpace
        ><VButton
          type="secondary"
          :loading="mutation.isPending.value"
          :disabled="mutation.isPending.value"
          @click="formNode?.submit()"
          >克隆页面</VButton
        ><VButton :disabled="mutation.isPending.value" @click="modal?.close()"
          >取消</VButton
        ></VSpace
      ></template
    >
  </VModal>
</template>
