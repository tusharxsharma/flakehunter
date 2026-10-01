import type { FailureCluster } from '../api/types'

interface Props {
  clusters: FailureCluster[] | undefined
  unavailable: boolean
}

export function ClustersPanel({ clusters, unavailable }: Props) {
  return (
    <section className="panel" aria-labelledby="clusters-heading" data-testid="clusters-panel">
      <div className="panel-header">
        <h2 id="clusters-heading">Top failure causes</h2>
        <span className="muted small">grouped by fingerprint</span>
      </div>
      {unavailable ? (
        <p className="empty" data-testid="clusters-unavailable">
          Triage service is unavailable. Flakiness data above is unaffected.
        </p>
      ) : !clusters || clusters.length === 0 ? (
        <p className="empty">No failures to group yet.</p>
      ) : (
        <ul className="clusters">
          {clusters.map((c) => (
            <li key={c.id} className="cluster" data-testid="cluster">
              <div className="cluster-head">
                <span className="badge badge-category">{c.category.replaceAll('_', ' ').toLowerCase()}</span>
                <span className="muted small">
                  {c.occurrences} failure{c.occurrences === 1 ? '' : 's'} · {c.tests.length} test
                  {c.tests.length === 1 ? '' : 's'}
                  {c.likely_flaky ? ' · likely flaky' : ''}
                </span>
              </div>
              <p className="cluster-cause">{c.root_cause}</p>
              <p className="muted small">
                <strong>Next step:</strong> {c.suggested_action}
              </p>
              <code className="signature">{c.signature}</code>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
