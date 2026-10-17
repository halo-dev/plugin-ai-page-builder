// Zoom math for the theme-preview canvas. The iframe keeps a fixed layout
// viewport and is visually scaled with CSS transform, so pointer geometry
// inside the frame must be multiplied by the scale to map to stage pixels.
export interface PreviewDimensions {
  width: number
  height: number
}

export const PREVIEW_DEVICES = {
  desktop: { width: 1280, height: 900 },
  tablet: { width: 768, height: 1024 },
  mobile: { width: 390, height: 844 },
} as const
export type PreviewDevice = keyof typeof PREVIEW_DEVICES
export const MIN_PREVIEW_SCALE = 0.4
export const MAX_PREVIEW_SCALE = 1.25
const PREVIEW_SCALE_STEP = 0.1

export function calculateFitScale(stage: PreviewDimensions, viewport: PreviewDimensions): number {
  if (stage.width <= 0 || stage.height <= 0) return 1
  return Math.min(stage.width / viewport.width, stage.height / viewport.height, 1)
}

// Fit by width only: the frame fills the stage height and scrolls vertically,
// like a real browser window at the device width.
export function calculateWidthFitScale(
  stage: PreviewDimensions,
  viewport: PreviewDimensions,
): number {
  if (stage.width <= 0) return 1
  return Math.min(stage.width / viewport.width, 1)
}

export function adjustPreviewScale(current: number, direction: -1 | 1): number {
  const next = Math.round((current + PREVIEW_SCALE_STEP * direction) * 100) / 100
  return Math.min(Math.max(next, MIN_PREVIEW_SCALE), MAX_PREVIEW_SCALE)
}
