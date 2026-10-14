import { describe, expect, it } from 'vitest'
import { adjustPreviewScale, calculateFitScale, PREVIEW_DEVICES } from '../preview-canvas'

describe('preview canvas zoom', () => {
  it('fits the viewport into the stage without zooming in', () => {
    expect(calculateFitScale({ width: 800, height: 600 }, PREVIEW_DEVICES.desktop)).toBe(0.625)
    expect(calculateFitScale({ width: 1600, height: 450 }, PREVIEW_DEVICES.desktop)).toBe(0.5)
    expect(calculateFitScale({ width: 1600, height: 1200 }, PREVIEW_DEVICES.desktop)).toBe(1)
    expect(calculateFitScale({ width: 800, height: 512 }, PREVIEW_DEVICES.tablet)).toBe(0.5)
    expect(calculateFitScale({ width: 390, height: 422 }, PREVIEW_DEVICES.mobile)).toBe(0.5)
    expect(calculateFitScale({ width: 0, height: 0 }, PREVIEW_DEVICES.mobile)).toBe(1)
  })
  it('steps the manual scale and clamps it to the supported range', () => {
    expect(adjustPreviewScale(1, 1)).toBeCloseTo(1.1)
    expect(adjustPreviewScale(1, -1)).toBeCloseTo(0.9)
    expect(adjustPreviewScale(1.25, 1)).toBe(1.25)
    expect(adjustPreviewScale(0.4, -1)).toBe(0.4)
  })
})
