<script setup lang="ts">
import { onBeforeUnmount, onMounted, useTemplateRef, watch } from 'vue'
import { EditorView, lineNumbers } from '@codemirror/view'
import { EditorState } from '@codemirror/state'
import { defaultHighlightStyle, syntaxHighlighting } from '@codemirror/language'
import { unifiedMergeView } from '@codemirror/merge'
import { html } from '@codemirror/lang-html'
import { css } from '@codemirror/lang-css'
import { javascript } from '@codemirror/lang-javascript'

const props = defineProps<{
  original: string
  modified: string
  language: 'html' | 'css' | 'js'
}>()

const container = useTemplateRef<HTMLDivElement>('container')
let view: EditorView | undefined

const languages = { html, css, js: javascript } as const

const theme = EditorView.theme({
  '&': {
    maxHeight: '24rem',
    fontSize: '12px',
    border: '1px solid var(--color-gray-200, #e5e7eb)',
    borderRadius: '0.5rem',
    backgroundColor: '#fff',
  },
  '.cm-scroller': {
    overflow: 'auto',
    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Consolas, monospace',
  },
  '&.cm-focused': { outline: 'none' },
})

function mount() {
  view?.destroy()
  if (!container.value) return
  view = new EditorView({
    parent: container.value,
    state: EditorState.create({
      doc: props.modified,
      extensions: [
        lineNumbers(),
        EditorView.lineWrapping,
        languages[props.language](),
        syntaxHighlighting(defaultHighlightStyle, { fallback: true }),
        unifiedMergeView({
          original: props.original,
          highlightChanges: true,
          gutter: true,
          syntaxHighlightDeletions: true,
          allowInlineDiffs: true,
          mergeControls: false,
          collapseUnchanged: { margin: 2, minSize: 4 },
        }),
        EditorState.readOnly.of(true),
        EditorView.editable.of(false),
        theme,
      ],
    }),
  })
}

onMounted(mount)
watch(() => [props.original, props.modified, props.language], mount)
onBeforeUnmount(() => view?.destroy())
</script>

<template>
  <div ref="container" />
</template>
