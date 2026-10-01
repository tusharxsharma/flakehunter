import type { TestDetail } from '../api/types'
import { formatPercent, relativeTime, shortSha } from '../lib/format'
import { HistoryStrip } from './HistoryStrip'
import { VerdictBadge } from './VerdictBadge'

interface Props {
  detail: TestDetail
  onClose: () => void
}

export function TestDetailPanel({ detail, onClose }: Props) {
  const { test, history } = detail
  const lastFailure = history.find((h) => h.status === 'FAILED' && h.failureMessage)

  return (
    <aside className="panel detail" aria-labelledby="detail-heading" data-testid="test-detail">
      <div className="panel-header">
        <div>
          <h2 id="detail-heading">{test.name}</h2>
          <div className="muted small">{test.suite}</div>
        </div>
        <button className="icon-button" onClick={onClose} aria-label="Close test details" data-testid="close-detail">
          ✕
        </button>
      </div>

      <dl className="stats">
        <div>
          <dt>Verdict</dt>
          <dd>
            <VerdictBadge verdict={test.verdict} />
          </dd>
        </div>
        <div>
          <dt>Score</dt>
          <dd data-testid="detail-score">{test.score.toFixed(2)}</dd>
        </div>
        <div>
          <dt>Flip rate</dt>
          <dd>{formatPercent(test.flipRate, 0)}</dd>
        </div>
        <div>
          <dt>Same-commit flips</dt>
          <dd>{test.inconsistentCommits}</dd>
        </div>
      </dl>

      {test.quarantined && (
        <p className="note" data-testid="quarantine-note">
          Quarantined: {test.quarantineReason}
        </p>
      )}

      <h3>Last {history.length} executions</h3>
      <HistoryStrip history={history} />

      {lastFailure && (
        <>
          <h3>
            Latest failure <span className="muted small">({shortSha(lastFailure.commitSha)}, {relativeTime(lastFailure.createdAt)})</span>
          </h3>
          {/* Rendered as text, never as HTML: failure messages come from untrusted test output. */}
          <pre className="failure" data-testid="failure-message">
            {lastFailure.failureMessage}
          </pre>
        </>
      )}
    </aside>
  )
}
