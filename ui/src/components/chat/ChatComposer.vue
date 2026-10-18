<script setup lang="ts">
import { computed, nextTick, onMounted, shallowRef, useTemplateRef, watch } from 'vue'
import IconArrowUp from '~icons/mingcute/arrow-up-line'
import IconStop from '~icons/mingcute/stop-fill'

const props = defineProps<{
  hasAnnotations?: boolean
  disabled: boolean
  canSend: boolean
  running: boolean
  canStop: boolean
  canSteer?: boolean
  available: boolean
  placeholder?: string
  autofocus?: boolean
}>()
const prompt = defineModel<string>('prompt', { required: true })
const model = defineModel<string>('model', { required: true })
const emit = defineEmits<{ send: []; stop: []; steer: [] }>()
const input = useTemplateRef<HTMLTextAreaElement>('input')
const composing = shallowRef(false)
// While a run is active the composer switches to steering: the same box queues
// follow-up instructions instead of starting a new run.
const steering = computed(() => props.running && !!props.canSteer)
const sendDisabled = computed(
  () =>
    props.disabled ||
    props.running ||
    !props.available ||
    !props.canSend ||
    (!prompt.value.trim() && !props.hasAnnotations),
)
const steerDisabled = computed(() => props.disabled || !steering.value || !prompt.value.trim())
function resize() {
  if (!input.value) return
  input.value.style.height = 'auto'
  input.value.style.height = `${Math.min(180, Math.max(64, input.value.scrollHeight))}px`
}
function keydown(event: KeyboardEvent) {
  if (
    event.key !== 'Enter' ||
    event.shiftKey ||
    event.isComposing ||
    composing.value ||
    event.keyCode === 229
  )
    return
  event.preventDefault()
  if (event.repeat) return
  if (steering.value) {
    if (!steerDisabled.value) emit('steer')
  } else if (!sendDisabled.value) emit('send')
}
onMounted(() => {
  if (props.autofocus) input.value?.focus()
})
watch(prompt, async () => {
  await nextTick()
  resize()
})
</script>

<template>
  <div
    class="rounded-2xl border border-gray-200 bg-white shadow-sm transition focus-within:border-gray-400"
  >
    <textarea
      ref="input"
      v-model="prompt"
      class="block max-h-[180px] min-h-[64px] w-full resize-none bg-transparent px-3.5 pt-3 text-[13px] leading-6 outline-none placeholder:text-gray-400"
      aria-label="描述你的想法"
      :placeholder="
        steering
          ? '补充要求，助手会在后续步骤中看到…（Enter 发送，Shift Enter 换行）'
          : (placeholder ?? '描述想法，或选中画布内容添加批注…（Enter 发送，Shift Enter 换行）')
      "
      :disabled="disabled || (running && !steering) || !available"
      :maxlength="8000"
      rows="3"
      @input="resize"
      @keydown="keydown"
      @compositionstart="composing = true"
      @compositionend="composing = false"
    />
    <div class="flex items-center gap-2 px-2.5 pb-2.5">
      <div class="apb-model min-w-0 flex-1">
        <FormKit
          v-if="available"
          v-model="model"
          type="aiModelSelector"
          model-type="language"
          required-features="tool-call"
          aria-label="模型"
          placeholder="选择模型"
          :disabled="disabled || running"
        />
      </div>
      <slot name="footer" />
      <button
        v-if="running"
        type="button"
        class="grid h-8 w-8 shrink-0 place-items-center rounded-full border border-gray-200 text-gray-600 transition-colors hover:bg-gray-100 disabled:opacity-40"
        v-tooltip="'停止'"
        aria-label="停止"
        :disabled="!canStop"
        @click="emit('stop')"
      >
        <IconStop class="text-sm" />
      </button>
      <button
        v-if="steering"
        type="button"
        class="grid h-8 w-8 shrink-0 place-items-center rounded-full bg-gray-900 text-white transition hover:bg-gray-700 disabled:pointer-events-none disabled:opacity-40"
        v-tooltip="'发送补充要求'"
        aria-label="发送补充要求"
        :disabled="steerDisabled"
        @click="emit('steer')"
      >
        <IconArrowUp class="text-base" />
      </button>
      <button
        v-else-if="!running"
        type="button"
        class="grid h-8 w-8 shrink-0 place-items-center rounded-full bg-gray-900 text-white transition hover:bg-gray-700 disabled:pointer-events-none disabled:opacity-40"
        v-tooltip="'发送'"
        aria-label="发送"
        :disabled="sendDisabled"
        @click="emit('send')"
      >
        <IconArrowUp class="text-base" />
      </button>
    </div>
  </div>
</template>

<!-- FormKit renders its own wrapper; only :deep() can reach it. -->
<style scoped>
.apb-model :deep(.formkit-outer) {
  padding: 0;
}
</style>
