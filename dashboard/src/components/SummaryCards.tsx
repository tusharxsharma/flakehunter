import type { ProjectSummary } from '../api/types'
import { formatPercent, relativeTime } from '../lib/format'

interface CardProps {
  label: string
  value: string | number
  hint?: string
  tone?: 'neutral' | 'warn' | 'bad'
  testId: string
}

function Card({ label, value, hint, tone = 'neutral', testId }: CardProps) {
  return (
    <div className={`card card-${tone}`} data-testid={testId}>
      <div className="card-label">{label}</div>
      <div className="card-value">{value}</div>
      {hint && <div className="card-hint">{hint}</div>}
    </div>
  )
}

export function SummaryCards({ summary }: { summary: ProjectSummary }) {
  return (
    <section className="cards" aria-label="Project summary">
      <Card
        label="CI runs"
        value={summary.totalRuns}
        hint={summary.lastRun ? `latest run ${relativeTime(summary.lastRun.createdAt)}` : 'no runs yet'}
        testId="card-runs"
      />
      <Card label="Tests tracked" value={summary.totalTests} testId="card-tests" />
      <Card
        label="Pass rate"
        value={summary.runsInWindow ? formatPercent(summary.passRate) : '-'}
        hint={`last ${summary.runsInWindow} runs`}
        testId="card-pass-rate"
      />
      <Card label="Flaky" value={summary.flakyTests} tone={summary.flakyTests ? 'warn' : 'neutral'} testId="card-flaky" />
      <Card label="Broken" value={summary.brokenTests} tone={summary.brokenTests ? 'bad' : 'neutral'} testId="card-broken" />
      <Card label="Quarantined" value={summary.quarantinedTests} testId="card-quarantined" />
    </section>
  )
}
