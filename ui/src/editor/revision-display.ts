const SOURCE_LABELS: Record<string, string> = {
  ai: 'AI 生成',
  manual: '手动保存',
  restore: '恢复',
}

const RELATIVE_UNITS: [Intl.RelativeTimeFormatUnit, number][] = [
  ['year', 365 * 24 * 60 * 60 * 1000],
  ['month', 30 * 24 * 60 * 60 * 1000],
  ['week', 7 * 24 * 60 * 60 * 1000],
  ['day', 24 * 60 * 60 * 1000],
  ['hour', 60 * 60 * 1000],
  ['minute', 60 * 1000],
  ['second', 1000],
]
const relativeTime = new Intl.RelativeTimeFormat('zh-CN', { numeric: 'auto' })

export function sourceLabel(source: string) {
  return SOURCE_LABELS[source] ?? source
}

export function fullTime(value: string) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString('zh-CN', { hour12: false })
}

export function timeAgo(value: string) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  const difference = date.getTime() - Date.now()
  const magnitude = Math.abs(difference)
  for (const [unit, milliseconds] of RELATIVE_UNITS) {
    if (magnitude >= milliseconds || unit === 'second')
      return relativeTime.format(Math.round(difference / milliseconds), unit)
  }
  return fullTime(value)
}
