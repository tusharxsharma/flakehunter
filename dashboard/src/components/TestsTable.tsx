import type { TestAnalysis, Verdict } from '../api/types'
import { formatPercent } from '../lib/format'
import { ScoreBar } from './ScoreBar'
import { VerdictBadge } from './VerdictBadge'

export type VerdictFilter = Verdict | 'ALL'

const FILTERS: { value: VerdictFilter; label: string }[] = [
  { value: 'FLAKY', label: 'Flaky' },
  { value: 'BROKEN', label: 'Broken' },
  { value: 'ALL', label: 'All tests' },
]

interface Props {
  tests: TestAnalysis[]
  filter: VerdictFilter
  onFilterChange: (filter: VerdictFilter) => void
  onSelect: (testId: number) => void
  selectedTestId?: number
}

export function TestsTable({ tests, filter, onFilterChange, onSelect, selectedTestId }: Props) {
  return (
    <section className="panel" aria-labelledby="tests-heading">
      <div className="panel-header">
        <h2 id="tests-heading">Tests by flakiness</h2>
        <div className="tabs" role="tablist" aria-label="Filter tests by verdict">
          {FILTERS.map((f) => (
            <button
              key={f.value}
              role="tab"
              aria-selected={filter === f.value}
              className={filter === f.value ? 'tab active' : 'tab'}
              onClick={() => onFilterChange(f.value)}
              data-testid={`filter-${f.value.toLowerCase()}`}
            >
              {f.label}
            </button>
          ))}
        </div>
      </div>

      {tests.length === 0 ? (
        <p className="empty" data-testid="tests-empty">
          {filter === 'FLAKY' ? 'No flaky tests detected. Nice.' : 'No tests match this filter.'}
        </p>
      ) : (
        <div className="table-scroll">
          <table data-testid="tests-table">
            <thead>
              <tr>
                <th scope="col">Test</th>
                <th scope="col">Verdict</th>
                <th scope="col">Score</th>
                <th scope="col" className="num">Fail rate</th>
                <th scope="col" className="num" title="Includes retries within a run">
                  Executions
                </th>
              </tr>
            </thead>
            <tbody>
              {tests.map((t) => (
                <tr
                  key={t.testId}
                  className={t.testId === selectedTestId ? 'selected' : undefined}
                  data-testid="test-row"
                >
                  <td>
                    <button className="link" onClick={() => onSelect(t.testId)} data-testid="test-name">
                      {t.name}
                    </button>
                    <div className="muted small">{t.suite}</div>
                    {t.quarantined && (
                      <span className="badge badge-quarantined" data-testid="quarantined-badge">
                        Quarantined
                      </span>
                    )}
                  </td>
                  <td>
                    <VerdictBadge verdict={t.verdict} />
                  </td>
                  <td>
                    <ScoreBar score={t.score} />
                  </td>
                  <td className="num">{formatPercent(t.failureRate, 0)}</td>
                  <td className="num">{t.runsAnalyzed}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}
