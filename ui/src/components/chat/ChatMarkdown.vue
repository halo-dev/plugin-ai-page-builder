<script setup lang="ts">
import MarkdownRender from 'markstream-vue'

defineProps<{ content: string; final: boolean }>()
</script>

<template>
  <div class="apb-md break-anywhere leading-7">
    <MarkdownRender
      mode="chat"
      :content="content"
      :final="final"
      html-policy="escape"
      :render-code-blocks-as-pre="true"
      :code-block-props="{ showHeader: false, showLineNumbers: false }"
      :fade="false"
    />
  </div>
</template>

<!-- Typography for markdown DOM rendered by the child component; only :deep() can reach it. -->
<style scoped>
.apb-md :deep(p),
.apb-md :deep(ul),
.apb-md :deep(ol),
.apb-md :deep(blockquote),
.apb-md :deep(pre) {
  margin: 0.5em 0;
}
.apb-md :deep(ul) {
  list-style: disc;
  padding-left: 1.5em;
}
.apb-md :deep(ol) {
  list-style: decimal;
  padding-left: 1.5em;
}
.apb-md :deep(pre) {
  white-space: pre-wrap;
  overflow: auto;
  max-height: 280px;
  padding: 10px;
  background: #f3f4f6;
  border-radius: 6px;
  font-size: 12px;
}
.apb-md :deep(code) {
  font-family: ui-monospace, monospace;
}
.apb-md :deep(:not(pre) > code) {
  background: #f3f4f6;
  padding: 1px 5px;
  border-radius: 4px;
  font-size: 0.92em;
}
/* markstream-vue ships no usable stylesheet here; its code shell relies on console Tailwind
   utilities, so only the missing layout pieces are patched (header itself is disabled via props). */
.apb-md :deep(.code-block-container) {
  overflow: hidden;
}
.apb-md :deep(.code-block-shell-content) {
  padding: 8px 10px;
}
.apb-md :deep(.code-block-container pre) {
  margin: 0;
  padding: 0;
  background: transparent;
  border-radius: 0;
}
.apb-md :deep(blockquote) {
  padding-left: 12px;
  border-left: 2px solid #d1d5db;
  color: #6b7280;
}
.apb-md :deep(a) {
  color: #4338ca;
  text-decoration: underline;
}
.apb-md :deep(h1),
.apb-md :deep(h2),
.apb-md :deep(h3) {
  font-size: 1.1em;
  font-weight: 600;
  margin: 0.8em 0 0.4em;
}
.apb-md :deep(table) {
  display: block;
  overflow: auto;
  border-collapse: collapse;
}
.apb-md :deep(td),
.apb-md :deep(th) {
  border: 1px solid #e5e7eb;
  padding: 5px 8px;
}
.apb-md :deep(img) {
  max-width: 100%;
}
</style>
