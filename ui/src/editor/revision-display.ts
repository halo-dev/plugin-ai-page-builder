import { utils } from '@halo-dev/ui-shared'

const SOURCE_LABELS: Record<string, string> = {
  ai: 'AI 生成',
  manual: '手动保存',
  restore: '恢复',
}

export function sourceLabel(source: string) {
  return SOURCE_LABELS[source] ?? source
}

export function fullTime(value: string) {
  return new Date(value).toLocaleString()
}

export function timeAgo(value: string) {
  return utils.date.timeAgo(value) ?? fullTime(value)
}
