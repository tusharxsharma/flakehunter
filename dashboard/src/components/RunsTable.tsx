import type { Run } from '../api/types'
import { formatDuration, relativeTime, shortSha } from '../lib/format'

export function RunsTable({ runs }: { runs: Run[] }) {
  return (
    <section className="panel" aria-labelledby="runs-heading">
      <div className="panel-header">
        <h2 id="runs-heading">Recent CI runs</h2>
      </div>
      {runs.length === 0 ? (
        <p className="empty">No runs uploaded yet.</p>
      ) : (
        <div className="table-scroll">
          <table data-testid="runs-table">
            <thead>
              <tr>
                <th scope="col">Commit</th>
                <th scope="col">Branch</th>
                <th scope="col" className="num">Passed</th>
                <th scope="col" className="num">Failed</th>
                <th scope="col" className="num">Duration</th>
                <th scope="col">When</th>
              </tr>
            </thead>
            <tbody>
              {runs.map((run) => (
                <tr key={run.id} data-testid="run-row">
                  <td>
                    <code>{shortSha(run.commitSha)}</code>
                  </td>
                  <td>{run.branch}</td>
                  <td className="num">{run.passed}</td>
                  <td className={run.failed ? 'num text-bad' : 'num'}>{run.failed}</td>
                  <td className="num">{formatDuration(run.durationMs)}</td>
                  <td className="muted">{relativeTime(run.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}
