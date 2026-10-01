import type { HistoryEntry } from '../api/types'
import { shortSha } from '../lib/format'

const SYMBOL = { PASSED: '✓', FAILED: '✕', SKIPPED: '–' } as const

/**
 * One square per execution, oldest on the left, so a flaky test reads as a checkerboard
 * and a broken one as a solid red tail. Each square has a symbol and a tooltip, not just a colour.
 */
export function HistoryStrip({ history }: { history: HistoryEntry[] }) {
  const chronological = [...history].reverse()
  return (
    <ol className="history" aria-label="Execution history, oldest first" data-testid="history-strip">
      {chronological.map((entry, index) => (
        <li
          key={`${entry.runId}-${index}`}
          className={`history-cell history-${entry.status.toLowerCase()}`}
          title={`Run #${entry.runId} · ${shortSha(entry.commitSha)} · ${entry.status}`}
          data-status={entry.status}
        >
          <span aria-hidden="true">{SYMBOL[entry.status]}</span>
          <span className="visually-hidden">{entry.status}</span>
        </li>
      ))}
    </ol>
  )
}
