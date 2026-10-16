<script setup lang="ts">
import { useRouter } from 'vue-router'
import { VButton, VPageHeader } from '@halo-dev/components'
import IconAddCircle from '~icons/mingcute/add-circle-line'
import IconArrowLeft from '~icons/mingcute/arrow-left-line'
import CreatePagePanel from '../components/create/CreatePagePanel.vue'

const router = useRouter()
</script>

<template>
  <VPageHeader title="新建页面">
    <template #icon><IconAddCircle /></template>
    <template #actions>
      <VButton type="secondary" @click="router.push({ name: 'AiPageBuilderPages' })">
        <template #icon><IconArrowLeft /></template>
        返回列表
      </VButton>
    </template>
  </VPageHeader>
  <div class="m-2 md:m-4">
    <CreatePagePanel class="py-10" />
  </div>
</template>
