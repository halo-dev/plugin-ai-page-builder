<script setup lang="ts">
import { computed, shallowRef } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { VAlert, VButton, VEmpty, VLoading, VModal, VSpace } from '@halo-dev/components'
import { aiPageBuilderApi } from '../api/clients'
import { pageKeys, queryClient } from '../lib/query-client'
import type { PageDocument, RevisionSummary } from '../api/generated'
import SourceComparison from './SourceComparison.vue'
const props = defineProps<{ name: string; current: PageDocument; busy: boolean }>()
const emit = defineEmits<{ close: []; restore: [revision: string] }>()
const selected = shallowRef<RevisionSummary>()
const revisionsQuery = useQuery({
  queryKey: computed(() => pageKeys.revisions(props.name)),
  queryFn: async () => (await aiPageBuilderApi.listPageRevisions({ name: props.name })).data,
  queryClient,
})
const detailQuery = useQuery({
  queryKey: computed(() => pageKeys.revision(props.name, selected.value?.name || '')),
  queryFn: async () =>
    (await aiPageBuilderApi.getPageRevision({ name: props.name, revision: selected.value!.name }))
      .data,
  enabled: computed(() => !!selected.value),
  queryClient,
})
const document = computed(() => detailQuery.data.value?.document)
</script>

<template>
  <VModal title="历史版本" :width="1100" @close="emit('close')">
    <!-- Wrap conditional content in a stable container for VModal's default slot. -->
    <div>
      <VLoading v-if="revisionsQuery.isLoading.value" />
      <VAlert v-if="revisionsQuery.isError.value" type="error" title="历史版本加载失败"
        ><template #actions
          ><VButton @click="revisionsQuery.refetch()">重新加载</VButton></template
        ></VAlert
      >
      <VEmpty
        v-if="
          !revisionsQuery.isLoading.value &&
          !revisionsQuery.isError.value &&
          !revisionsQuery.data.value?.length
        "
        title="暂无历史版本"
      />
      <div
        v-if="revisionsQuery.data.value?.length"
        class="grid grid-cols-[minmax(0,1fr)] gap-5 md:grid-cols-[220px_minmax(0,1fr)]"
      >
        <nav class="max-h-40 overflow-auto md:max-h-[540px]" aria-label="历史版本列表">
          <button
            v-for="revision in revisionsQuery.data.value"
            :key="revision.name"
            type="button"
            class="grid w-full gap-1 border-b border-gray-200 p-3 text-left"
            :class="{ 'bg-gray-100': selected?.name === revision.name }"
            :aria-pressed="selected?.name === revision.name"
            @click="selected = revision"
          >
            <strong class="text-[13px]">{{ revision.title }}</strong
            ><span class="text-xs text-gray-500">{{
              new Date(revision.createdAt).toLocaleString()
            }}</span
            ><span class="text-xs text-gray-500"
              >{{ revision.source }} · {{ revision.createdBy }}</span
            >
          </button>
        </nav>
        <div>
          <VLoading v-if="detailQuery.isFetching.value" /><template v-else-if="document"
            ><p class="mb-3 text-xs text-gray-500">当前编辑内容 → 选中版本</p>
            <p class="mb-3 text-xs text-gray-500">
              标题：{{ current.title }} → {{ document.title }}<br />描述：{{
                current.description || '无'
              }}
              → {{ document.description || '无' }}
            </p>
            <SourceComparison :before="current" :after="document" /></template
          ><VEmpty v-else title="选择一个版本查看差异" />
        </div>
      </div>
    </div>
    <template #footer
      ><VSpace
        ><VButton
          type="secondary"
          :disabled="!document || busy || detailQuery.isFetching.value"
          @click="selected && emit('restore', selected.name)"
          >恢复为新草稿</VButton
        ><VButton @click="emit('close')">关闭</VButton></VSpace
      ></template
    >
  </VModal>
</template>
