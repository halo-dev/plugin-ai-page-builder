<script setup lang="ts">
import { computed, ref, shallowRef, watch } from 'vue'
import { Toast, VButton, VDropdown, type VDropdownPlacement } from '@halo-dev/components'
import { aiPageBuilderApi, getPublicSiteUrl } from '../api/clients'
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
    scripted?: boolean
    placement?: VDropdownPlacement
  }>(),
  // undefined keeps the list entry's "not provided" state so the popper can fetch it.
  { placement: 'bottom-end', scripted: undefined },
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
const siteBaseUrl = shallowRef('')
const scriptedState = shallowRef(props.scripted ?? false)
// Version the detection result belongs to; a newer list entry must be re-checked.
const scriptCheck = shallowRef<{ version: number; scripted: boolean } | null>(null)
// Detection must finish before publishing so a slow request cannot hide the warning.
const scriptPending = shallowRef(false)
watch(
  () => props.scripted,
  (value) => {
    if (value !== undefined) scriptedState.value = value
  },
)
const previewUrl = computed(() => {
  const slug = form.value.slug.trim()
  if (!slug) return ''
  const base = (siteBaseUrl.value || window.location.origin).replace(/\/$/, '')
  return `${base}/${slug}`
})
function loadSiteBaseUrl() {
  if (siteBaseUrl.value) return
  void getPublicSiteUrl()
    .then((url) => {
      siteBaseUrl.value = url
    })
    .catch(() => {
      // Keep the Console origin as a best-effort fallback.
    })
}
async function loadScriptState() {
  if (props.scripted !== undefined) return
  scriptPending.value = true
  try {
    const detail = (await aiPageBuilderApi.getPage({ name: props.name })).data
    // Only the warning flag: the fetched page may be newer than the list entry, and
    // adopting its version here would let this form overwrite concurrent edits.
    const scripted = (detail.document.js ?? '').trim() !== ''
    scriptCheck.value = { version: detail.page.version, scripted }
    scriptedState.value = scripted
  } catch {
    // Publishing re-checks so an unknown state never silently skips the warning.
  } finally {
    scriptPending.value = false
  }
}

/** Returns whether the version being published contains scripts, re-reading if needed. */
async function verifyScriptState(base: { version: number }): Promise<boolean> {
  if (props.scripted !== undefined) return props.scripted
  if (scriptCheck.value?.version === base.version) return scriptCheck.value.scripted
  const detail = (await aiPageBuilderApi.getPage({ name: props.name })).data
  if (detail.page.version !== base.version) {
    throw new Error('页面已在别处更新，请关闭后重新打开发布面板')
  }
  const scripted = (detail.document.js ?? '').trim() !== ''
  scriptCheck.value = { version: base.version, scripted }
  scriptedState.value = scripted
  return scripted
}

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
  loadSiteBaseUrl()
  void loadScriptState()
})

// A list refresh can move this entry to a newer version while the popper stays open.
watch(
  () => props.version,
  () => {
    if (shown.value && !pending.value) void loadScriptState()
  },
)

function sync(detail: PageDetail) {
  synced.value = detail.page
  emit('updated', detail)
}

async function publish(value: { title: string; slug: string }) {
  const title = value.title.trim()
  const requested = value.slug.trim()
  if (
    pending.value ||
    scriptPending.value ||
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
    // The warning must match the version being published; if detection only discovers
    // scripts now, make the user see the warning and click again.
    const warned = scriptedState.value
    const scripted = await verifyScriptState(base)
    if (scripted && !warned) {
      error.value = '此页面包含脚本，请确认上方提示后再次点击发布。'
      return
    }
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
      <div class="w-80 p-2">
        <p class="mb-2.5 text-sm font-semibold text-gray-900">发布页面</p>
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
            :help="previewUrl"
          />
          <div v-if="scriptedState" class="py-4">
            <p class="rounded-md bg-amber-50 px-2.5 py-2 text-xs leading-5 text-amber-700">
              此页面包含脚本，发布后会在访客浏览器中自动执行。
            </p>
          </div>
          <div v-if="error" class="py-4">
            <p class="text-xs text-red-600">{{ error }}</p>
          </div>
          <div class="pt-4 flex justify-end">
            <VButton
              size="sm"
              type="secondary"
              :loading="pending"
              :disabled="pending || disabled || scriptPending || !state.valid"
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
