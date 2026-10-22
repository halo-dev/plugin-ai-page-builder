import type { PageDocument, ThemeStyleContext } from '../api/generated'

export type PreviewRootState = NonNullable<ThemeStyleContext['colorMode']>['rootStates'][number]
export function rootAttributes(state?: PreviewRootState): string {
  if (!state) return ''
  const classes = state.classes
    .filter((value) => /^[a-zA-Z][a-zA-Z0-9_-]{0,63}$/.test(value))
    .join(' ')
  const attributes = Object.entries(state.attributes).filter(
    ([name, value]) =>
      /^data-[a-z][a-z0-9-]{0,63}$/.test(name) && /^[a-zA-Z0-9_-]{1,64}$/.test(value),
  )
  return (
    (classes ? ` class="${classes}"` : '') +
    attributes.map(([name, value]) => ` ${name}="${value}"`).join('')
  )
}

// Call only for the exact HTML/CSS accepted by server validation. Read-only
// previews use sandbox=""; the canvas uses allow-same-origin without scripts.
// The fragment-only preview cannot load the plugin's modern-normalize baseline,
// so an inline zero body margin mirrors the standalone document.
export function candidatePreview(
  candidate: Pick<PageDocument, 'html' | 'css'>,
  previewCss = '',
  rootState?: PreviewRootState,
): string {
  const css = candidate.css.replace(/</g, '\\3c ')
  const themeStyle = previewCss ? `<style>${previewCss.replace(/</g, '\\3c ')}</style>` : ''
  return `<!DOCTYPE html><html${rootAttributes(rootState)}><head><meta charset="utf-8"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src https: http: data:; style-src 'unsafe-inline'; script-src 'none'; base-uri 'none'; form-action 'none'"><meta name="viewport" content="width=device-width, initial-scale=1">${themeStyle}<style>${css}</style></head><body style="margin:0">${candidate.html}</body></html>`
}
