<script setup lang="ts">
import { ref, type Component } from 'vue'
import IconDown from '~icons/mingcute/down-line'
import IconUp from '~icons/mingcute/up-line'

const props = defineProps<{
  icon: Component
  title: string
  summary?: string
  status?: string
  tone?: 'default' | 'error' | 'warn'
  running?: boolean
  expandable?: boolean
}>()

const open = ref(false)

function toggle() {
  if (props.expandable) open.value = !open.value
}
</script>

<template>
  <!-- One process row (thinking, or one tool step): a 24px line with a 14px
       leading glyph, title, dot and a single-line summary, matching the
       disclosure rows used by the agent harness. The leading glyph fades to a
       chevron on hover, and the expanded body indents under the title. -->
  <div class="min-w-0">
    <div
      class="apb-disclosure-row flex h-6 min-w-0 items-center gap-1.5 text-[13px]"
      data-disclosure-row
      :data-tone="tone || 'default'"
      :data-expandable="expandable || undefined"
      :data-running="running || undefined"
      :role="expandable ? 'button' : undefined"
      :tabindex="expandable ? 0 : undefined"
      :aria-expanded="expandable ? open : undefined"
      @click="toggle"
      @keydown.enter.prevent="toggle"
      @keydown.space.prevent="toggle"
    >
      <span
        class="apb-disclosure-leading relative inline-flex h-4 w-4 shrink-0 items-center justify-center"
      >
        <component :is="icon" class="apb-disclosure-icon text-sm" aria-hidden="true" />
        <IconUp
          v-if="open"
          class="apb-disclosure-chevron absolute inset-0 m-auto text-sm"
          aria-hidden="true"
        />
        <IconDown
          v-else
          class="apb-disclosure-chevron absolute inset-0 m-auto text-sm"
          aria-hidden="true"
        />
      </span>
      <span class="apb-disclosure-title shrink-0 whitespace-nowrap">{{ title }}</span>
      <template v-if="summary && !open">
        <span class="h-0.5 w-0.5 shrink-0 rounded-full bg-current opacity-40" aria-hidden="true" />
        <span class="apb-disclosure-summary min-w-0 truncate">{{ summary }}</span>
      </template>
      <span v-if="status" class="apb-disclosure-status shrink-0 whitespace-nowrap text-xs">
        {{ status }}
      </span>
    </div>
    <div v-if="open" class="pl-[22px] pt-1 pb-1" data-disclosure-body>
      <slot />
    </div>
  </div>
</template>

<style scoped>
.apb-disclosure-row {
  color: #6b7280;
  transition: color 100ms ease;
}
.apb-disclosure-row[data-expandable] {
  cursor: pointer;
}
.apb-disclosure-row[data-expandable]:hover {
  color: #6b7280;
}
/* Failures and pending questions keep their own tone instead of the hover grey. */
.apb-disclosure-row[data-tone='error'],
.apb-disclosure-row[data-tone='error']:hover {
  color: #dc2626;
}
.apb-disclosure-row[data-tone='warn'],
.apb-disclosure-row[data-tone='warn']:hover {
  color: #b45309;
}
.apb-disclosure-row:focus-visible {
  border-radius: 4px;
  outline: 2px solid #4ccba0;
  outline-offset: 2px;
}
.apb-disclosure-icon {
  opacity: 1;
  transition: opacity 100ms ease;
}
.apb-disclosure-chevron {
  opacity: 0;
  transition: opacity 100ms ease;
}
.apb-disclosure-row[data-expandable]:hover .apb-disclosure-icon,
.apb-disclosure-row[aria-expanded='true'] .apb-disclosure-icon {
  opacity: 0;
}
.apb-disclosure-row[data-expandable]:hover .apb-disclosure-chevron,
.apb-disclosure-row[aria-expanded='true'] .apb-disclosure-chevron {
  opacity: 1;
}
/* Running rows pulse the leading glyph; the text itself stays at full contrast. */
.apb-disclosure-row[data-running] .apb-disclosure-leading {
  animation: apb-disclosure-pulse 1.6s ease-in-out infinite;
}
@keyframes apb-disclosure-pulse {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.4;
  }
}
@media (prefers-reduced-motion: reduce) {
  .apb-disclosure-row,
  .apb-disclosure-icon,
  .apb-disclosure-chevron {
    transition: none;
  }
  .apb-disclosure-row[data-running] .apb-disclosure-leading {
    animation: none;
  }
}
</style>
