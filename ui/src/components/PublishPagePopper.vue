<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Toast, VButton, VDropdown, type VDropdownPlacement } from '@halo-dev/components'
import IconEarth from '~icons/mingcute/earth-line'
import { aiPageBuilderApi } from '../api/clients'
import type { PageDetail } from '../api/generated'
import { errorMessage } from '../utils/error'
import { slugValidation } from '../utils/slug'

const props = withDefaults(
  defineProps<{
    name: string
    title: string
    slug?: string | null
    version: number
    disabled?: boolean
    placement?: VDropdownPlacement
  }>(),
  { placement: 'bottom-end' },
)
const emit = defineEmits<{
  published: [detail: PageDetail]
  updated: [detail: PageDetail]
  pending: [pending: boolean]
}>()

const SLUG_PATTERN = /^[a-z0-9]+(?:-[a-z0-9]+)*(?:\/[a-z0-9]+(?:-[a-z0-9]+)*)*$/

const shown = ref(false)
const form = ref({ title: props.title, slug: props.slug ?? '' })
const formNode = ref<{ submit: () => void }>()
// Keep successful intermediate saves so retries use the latest version.
const synced = ref<PageDetail['page'] | null>(null)
const pending = ref(false)
const error = ref('')
const previewUrl = computed(() =>
  form.value.slug.trim() ? `${window.location.origin}/${form.value.slug.trim()}` : '',
)

// Authoritative slug/version: whichever of props or this popper's own synced
// state is newer, so display and publish always agree on the same base.
function currentBase() {
  return synced.value && synced.value.version > props.version
    ? synced.value
    : { title: props.title, slug: props.slug ?? null, version: props.version }
}

watch(shown, (open) => {
  // Never reset mid-request: reopening while a publish is in flight must not
  // clobber the slug the request snapshot below is based on.
  if (!open || pending.value) return
  // The slug may have changed through Page Settings while the popper was
  // closed; re-sync from the freshest known state on every open.
  const base = currentBase()
  form.value = { title: base.title, slug: base.slug ?? '' }
  error.value = ''
})

function sync(detail: PageDetail) {
  synced.value = detail.page
  emit('updated', detail)
}

async function publish(value: { title: string; slug: string }) {
  const title = value.title.trim()
  const requested = value.slug.trim()
  if (
    pending.value ||
    props.disabled ||
    !title ||
    title.length > 200 ||
    !SLUG_PATTERN.test(requested) ||
    requested.length > 200
  )
    return
  pending.value = true
  emit('pending', true)
  error.value = ''
  try {
    const base = currentBase()
    let version = base.version
    if (title !== base.title) {
      const current = (await aiPageBuilderApi.getPage({ name: props.name })).data
      const updated = (
        await aiPageBuilderApi.savePage({
          name: props.name,
          savePageRequest: {
            version,
            document: { ...current.document, title },
            source: 'manual',
          },
        })
      ).data
      version = updated.page.version
      sync(updated)
    }
    if (requested !== base.slug) {
      const updated = (
        await aiPageBuilderApi.updatePageSlug({
          name: props.name,
          updatePageSlugRequest: { version, slug: requested },
        })
      ).data
      version = updated.page.version
      sync(updated)
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
    formNode.value?.submit()
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
        <FormKit
          v-slot="{ state }"
          v-model="form"
          type="form"
          :actions="false"
          :disabled="pending || disabled"
          @node="formNode = $event"
          @submit="publish"
          @keydown="keydown"
        >
          <FormKit
            type="text"
            name="title"
            label="页面标题"
            validation="required|length:1,200"
            maxlength="200"
          />
          <FormKit
            type="text"
            name="slug"
            label="访问路径"
            placeholder="company/about"
            :validation="slugValidation"
            maxlength="200"
          />
          <p v-if="previewUrl" class="mt-1.5 flex min-w-0 items-center gap-1 text-xs text-gray-400">
            <IconEarth class="shrink-0" />
            <span class="truncate" :title="previewUrl">{{ previewUrl }}</span>
          </p>
          <p v-if="error" class="mt-1.5 text-xs text-red-600">{{ error }}</p>
          <div class="mt-3 flex justify-end">
            <VButton
              size="sm"
              type="primary"
              :loading="pending"
              :disabled="pending || disabled || !state.valid"
              @click="formNode?.submit()"
            >
              发布
            </VButton>
          </div>
        </FormKit>
      </div>
    </template>
  </VDropdown>
</template>
