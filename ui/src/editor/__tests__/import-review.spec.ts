import { describe, expect, it } from 'vitest'
import {
  ARCHIVE_LIMIT_BYTES,
  canImportReview,
  categoryLabel,
  formatSize,
  isMalicious,
  isSuspicious,
  primaryLabel,
  reviewFooter,
  severityClass,
  severityLabel,
  validateArchive,
  verdictMeta,
} from '../import-review'

describe('import review helpers', () => {
  it('validates archive name and size', () => {
    expect(validateArchive({ name: 'page.zip', size: 1024 })).toBe('')
    expect(validateArchive({ name: 'PAGE.ZIP', size: 1024 })).toBe('')
    expect(validateArchive({ name: 'page.tar', size: 1024 })).toBe('请选择 .zip 格式的文件。')
    expect(validateArchive({ name: 'page.zip', size: ARCHIVE_LIMIT_BYTES + 1 })).toBe(
      'ZIP 文件大小不能超过 4 MiB。',
    )
  })

  it('formats sizes', () => {
    expect(formatSize(512)).toBe('512 B')
    expect(formatSize(2048)).toBe('2.0 KB')
    expect(formatSize(3 * 1024 * 1024)).toBe('3.0 MB')
  })

  it('maps severity and category labels', () => {
    expect(severityLabel('critical')).toBe('严重')
    expect(severityLabel('unknown')).toBe('unknown')
    expect(severityClass('high')).toBe('bg-red-50 text-red-700')
    expect(severityClass('medium')).toBe('bg-amber-50 text-amber-700')
    expect(severityClass('low')).toBe('bg-gray-100 text-gray-600')
    expect(categoryLabel('phishing')).toBe('钓鱼内容')
    expect(categoryLabel('unknown')).toBe('其他风险')
  })

  it('derives verdict state and footer', () => {
    expect(isSuspicious('suspicious')).toBe(true)
    expect(isMalicious('malicious')).toBe(true)
    expect(canImportReview('safe', false)).toBe(true)
    expect(canImportReview('suspicious', false)).toBe(false)
    expect(canImportReview('suspicious', true)).toBe(true)
    expect(canImportReview('malicious', true)).toBe(false)
    expect(reviewFooter('malicious')).toBe('该页面包 30 分钟内不能重新审查或导入')
    expect(reviewFooter('safe')).toBe('审查结果 30 分钟内有效')
    expect(verdictMeta('safe')).toEqual({ label: 'AI 审查通过', alertType: 'success' })
    expect(verdictMeta('suspicious').alertType).toBe('warning')
    expect(verdictMeta('malicious').alertType).toBe('error')
  })

  it('derives the primary action label', () => {
    expect(primaryLabel('select', true, undefined)).toBe('开始 AI 审查')
    expect(primaryLabel('select', false, undefined)).toBe('AI 审查不可用')
    expect(primaryLabel('report', true, 'safe')).toBe('导入页面')
    expect(primaryLabel('report', true, 'suspicious')).toBe('仍然导入')
    expect(primaryLabel('report', true, 'malicious')).toBe('发现恶意内容，无法导入')
  })
})
