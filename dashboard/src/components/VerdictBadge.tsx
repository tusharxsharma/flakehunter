import type { Verdict } from '../api/types'
import { verdictLabel } from '../lib/format'

export function VerdictBadge({ verdict }: { verdict: Verdict }) {
  return (
    <span className={`badge badge-${verdict.toLowerCase()}`} data-testid="verdict-badge">
      {verdictLabel(verdict)}
    </span>
  )
}
