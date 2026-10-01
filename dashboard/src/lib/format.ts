export function formatPercent(ratio: number, digits = 1): string {
  if (!Number.isFinite(ratio)) return '-'
  return `${(ratio * 100).toFixed(digits)}%`
}

export function formatDuration(ms: number): string {
  if (!Number.isFinite(ms) || ms < 0) return '-'
  if (ms < 1000) return `${Math.round(ms)} ms`
  const seconds = ms / 1000
  if (seconds < 60) return `${seconds.toFixed(1)} s`
  const minutes = Math.floor(seconds / 60)
  return `${minutes}m ${Math.round(seconds % 60)}s`
}

export function shortSha(sha: string): string {
  return sha.slice(0, 7)
}

const UNITS: [Intl.RelativeTimeFormatUnit, number][] = [
  ['day', 86_400_000],
  ['hour', 3_600_000],
  ['minute', 60_000],
]

export function relativeTime(iso: string, now: number = Date.now()): string {
  const then = Date.parse(iso)
  if (Number.isNaN(then)) return '-'
  const diff = then - now
  const rtf = new Intl.RelativeTimeFormat('en', { numeric: 'auto' })
  for (const [unit, size] of UNITS) {
    if (Math.abs(diff) >= size) return rtf.format(Math.round(diff / size), unit)
  }
  return 'just now'
}

export function verdictLabel(verdict: string): string {
  return verdict === 'INSUFFICIENT_DATA' ? 'Needs data' : verdict.charAt(0) + verdict.slice(1).toLowerCase()
}
