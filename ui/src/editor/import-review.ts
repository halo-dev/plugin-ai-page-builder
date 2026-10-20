import type { ImportReviewVerdictEnum } from '../api/generated'

export const ARCHIVE_LIMIT_BYTES = 4 * 1024 * 1024

export type ImportPhase = 'select' | 'report'

const severityLabels: Record<string, string> = {
  critical: '严重',
  high: '高',
  medium: '中',
  low: '低',
}
const categoryLabels: Record<string, string> = {
  malicious_script: '恶意脚本',
  data_exfiltration: '数据外传',
  phishing: '钓鱼内容',
  obfuscation: '代码混淆',
  external_resource: '外部资源',
  prompt_injection: '提示注入',
  content_policy: '内容违规',
  other: '其他风险',
}

export function validateArchive(file: Pick<File, 'name' | 'size'>): string {
  if (!file.name.toLowerCase().endsWith('.zip')) {
    return '请选择 .zip 格式的文件。'
  }
  if (file.size > ARCHIVE_LIMIT_BYTES) {
    return 'ZIP 文件大小不能超过 4 MiB。'
  }
  return ''
}

export function formatSize(bytes: number) {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

export function severityLabel(severity: string) {
  return severityLabels[severity] ?? severity
}

export function severityClass(severity: string) {
  if (severity === 'critical' || severity === 'high') return 'bg-red-50 text-red-700'
  if (severity === 'medium') return 'bg-amber-50 text-amber-700'
  return 'bg-gray-100 text-gray-600'
}

export function categoryLabel(category: string) {
  return categoryLabels[category] ?? '其他风险'
}

export function isSuspicious(verdict: ImportReviewVerdictEnum | undefined) {
  return verdict === 'suspicious'
}

export function isMalicious(verdict: ImportReviewVerdictEnum | undefined) {
  return verdict === 'malicious'
}

export function canImportReview(verdict: ImportReviewVerdictEnum | undefined, confirmed: boolean) {
  return verdict === 'safe' || (isSuspicious(verdict) && confirmed)
}

export function reviewFooter(verdict: ImportReviewVerdictEnum | undefined) {
  return isMalicious(verdict) ? '该页面包 30 分钟内不能重新审查或导入' : '审查结果 30 分钟内有效'
}

export function verdictMeta(verdict: ImportReviewVerdictEnum | undefined): {
  label: string
  alertType: 'success' | 'warning' | 'error'
} {
  if (verdict === 'safe') {
    return { label: 'AI 审查通过', alertType: 'success' }
  }
  if (verdict === 'suspicious') {
    return { label: 'AI 审查发现风险', alertType: 'warning' }
  }
  return { label: 'AI 审查判定为恶意页面，禁止导入', alertType: 'error' }
}

export function primaryLabel(
  phase: ImportPhase,
  aiAvailable: boolean,
  verdict: ImportReviewVerdictEnum | undefined,
) {
  if (phase === 'select') {
    return aiAvailable ? '开始 AI 审查' : 'AI 审查不可用'
  }
  if (verdict === 'safe') return '导入页面'
  if (verdict === 'suspicious') return '仍然导入'
  if (verdict === 'malicious') return '发现恶意内容，无法导入'
  return '无法导入'
}
