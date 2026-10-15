<script setup lang="ts">
import { computed, ref, useId, watch } from 'vue'
import { Toast, VButton, VDropdown, type VDropdownPlacement } from '@halo-dev/components'
import IconRefresh from '~icons/mingcute/refresh-2-line'
import IconEarth from '~icons/mingcute/earth-line'
import { aiPageBuilderApi } from '../api/clients'
import type { PageDetail } from '../api/generated'
import { errorMessage } from '../utils/error'
import { randomSlug } from '../utils/slug'

const props = withDefaults(
  defineProps<{
    name: string
    slug?: string | null
    version: number
    placement?: VDropdownPlacement
  }>(),
  { placement: 'bottom-end' },
)
const emit = defineEmits<{ published: [detail: PageDetail]; pending: [pending: boolean] }>()

const SLUG_PATTERN = /^[a-z0-9]+(?:-[a-z0-9]+)*(?:\/[a-z0-9]+(?:-[a-z0-9]+)*)*$/

const slugId = useId()
const shown = ref(false)
const slug = ref(props.slug || randomSlug())
// Slug/version already saved on the server by this popper, so a retry after a
// failed publishPage call doesn't resend a stale version to updatePageSlug.
const synced = ref<{ slug: string | null; version: number } | null>(null)
const pending = ref(false)
const error = ref('')
const slugValid = computed(() => SLUG_PATTERN.test(slug.value) && slug.value.length <= 200)
const previewUrl = computed(() => `${window.location.origin}/${slug.value}`)

watch(shown, (open) => {
  if (!open) return
  // The slug may have changed through Page Settings while the popper was
  // closed; re-sync from the latest props on every open.
  slug.value = props.slug || randomSlug()
  synced.value = null
  error.value = ''
})

async function publish() {
  if (pending.value || !slugValid.value) return
  pending.value = true
  emit('pending', true)
  error.value = ''
  try {
    const base = synced.value ?? { slug: props.slug ?? null, version: props.version }
    let version = base.version
    if (slug.value !== base.slug) {
      const updated = (
        await aiPageBuilderApi.updatePageSlug({
          name: props.name,
          updatePageSlugRequest: { version, slug: slug.value },
        })
      ).data
      version = updated.page.version
      synced.value = { slug: slug.value, version }
    }
    const published = (
      await aiPageBuilderApi.publishPage({ name: props.name, mutationRequest: { version } })
    ).data
    Toast.success('页面已发布')
    shown.value = false
    emit('published', published)
  } catch (e) {
    // Slug conflicts land here; keep the popper open so the user can edit and retry.
    error.value = errorMessage(e)
  } finally {
    pending.value = false
    emit('pending', false)
  }
}
function keydown(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.isComposing && event.keyCode !== 229) {
    event.preventDefault()
    void publish()
  }
}
</script>

<template>
  <VDropdown
    v-model:shown="shown"
    :placement="placement"
    :triggers="['click']"
    :auto-hide="true"
    :distance="6"
  >
    <slot />
    <template #popper>
      <div class="w-80 p-3.5">
        <p class="mb-2 text-[13px] font-semibold text-gray-900">发布页面</p>
        <label :for="slugId" class="mb-1 block text-xs text-gray-500">访问路径</label>
        <input
          :id="slugId"
          v-model.trim="slug"
          class="apb-input"
          :class="!slugValid && slug ? '!border-red-300' : ''"
          placeholder="company/about"
          maxlength="200"
          :disabled="pending"
          @keydown="keydown"
        />
        <p class="mt-1.5 flex min-w-0 items-center gap-1 text-xs text-gray-400">
          <IconEarth class="shrink-0" />
          <span class="truncate" :title="previewUrl">{{ previewUrl }}</span>
        </p>
        <p v-if="error" class="mt-1.5 text-xs text-red-600">{{ error }}</p>
        <div class="mt-3 flex items-center justify-between gap-2">
          <button
            v-if="!props.slug"
            type="button"
            class="inline-flex items-center gap-1 text-xs text-gray-400 hover:text-gray-600 disabled:opacity-40"
            :disabled="pending"
            @click="slug = randomSlug()"
          >
            <IconRefresh />随机换一个
          </button>
          <span v-else />
          <VButton
            size="sm"
            type="primary"
            :loading="pending"
            :disabled="!slugValid"
            @click="publish"
          >
            发布
          </VButton>
        </div>
      </div>
    </template>
  </VDropdown>
</template>
