import { describe, expect, it } from 'vitest'
import { formatDuration, formatPercent, relativeTime, shortSha, verdictLabel } from './format'

describe('formatPercent', () => {
  it.each([
    [0.9531, 1, '95.3%'],
    [1, 0, '100%'],
    [0, 1, '0.0%'],
    [Number.NaN, 1, '-'],
  ])('%s with %s digits -> %s', (ratio, digits, expected) => {
    expect(formatPercent(ratio, digits)).toBe(expected)
  })
})

describe('formatDuration', () => {
  it.each([
    [0, '0 ms'],
    [999, '999 ms'],
    [1500, '1.5 s'],
    [59_900, '59.9 s'],
    [125_000, '2m 5s'],
    [-1, '-'],
  ])('%s ms -> %s', (ms, expected) => {
    expect(formatDuration(ms)).toBe(expected)
  })
})

describe('relativeTime', () => {
  const now = Date.parse('2026-09-26T12:00:00Z')

  it.each([
    ['2026-09-26T11:59:30Z', 'just now'],
    ['2026-09-26T11:55:00Z', '5 minutes ago'],
    ['2026-09-26T09:00:00Z', '3 hours ago'],
    ['2026-09-24T12:00:00Z', '2 days ago'],
    ['not a date', '-'],
  ])('%s -> %s', (iso, expected) => {
    expect(relativeTime(iso, now)).toBe(expected)
  })
})

describe('labels', () => {
  it('shortens commit SHAs to 7 characters', () => {
    expect(shortSha('9fceb02d0ae598e95dc970b74767f19372d61af8')).toBe('9fceb02')
  })

  it('humanises verdicts', () => {
    expect(verdictLabel('FLAKY')).toBe('Flaky')
    expect(verdictLabel('INSUFFICIENT_DATA')).toBe('Needs data')
  })
})
