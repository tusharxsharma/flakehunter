import { useEffect, useState } from 'react'
import { api as defaultApi, type ApiClient } from './api/client'
import { ClustersPanel } from './components/ClustersPanel'
import { RunsTable } from './components/RunsTable'
import { SummaryCards } from './components/SummaryCards'
import { TestDetailPanel } from './components/TestDetailPanel'
import { TestsTable, type VerdictFilter } from './components/TestsTable'
import { useAsync } from './hooks/useAsync'

function readProjectFromUrl(): number | undefined {
  const raw = new URLSearchParams(window.location.search).get('project')
  const id = raw ? Number.parseInt(raw, 10) : Number.NaN
  return Number.isFinite(id) ? id : undefined
}

export default function App({ api = defaultApi }: { api?: ApiClient }) {
  const projects = useAsync('projects', () => api.listProjects())
  const [chosenProjectId, setProjectId] = useState<number | undefined>(readProjectFromUrl)
  const [filter, setFilter] = useState<VerdictFilter>('FLAKY')
  const [selectedTestId, setSelectedTestId] = useState<number>()

  // Until the user picks one, show the first project
  const projectId = chosenProjectId ?? projects.data?.[0]?.id

  // Keep the selected project in the URL so a view can be shared as a link
  useEffect(() => {
    if (projectId === undefined) return
    const url = new URL(window.location.href)
    url.searchParams.set('project', String(projectId))
    window.history.replaceState(null, '', url)
  }, [projectId])

  const selectProject = (id: number) => {
    setProjectId(id)
    setSelectedTestId(undefined)
  }

  return (
    <div className="app">
      <header className="topbar">
        <div className="brand">
          <span className="logo" aria-hidden="true">◎</span> FlakeHunter
        </div>
        {projects.data && projects.data.length > 0 && (
          <label className="project-picker">
            <span>Project</span>
            <select
              value={projectId ?? ''}
              onChange={(e) => selectProject(Number(e.target.value))}
              data-testid="project-select"
            >
              {projects.data.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name}
                </option>
              ))}
            </select>
          </label>
        )}
      </header>

      <main className="content">
        {projects.error && (
          <p className="error" role="alert" data-testid="error">
            Could not reach the FlakeHunter API: {projects.error.message}
          </p>
        )}
        {projects.data && projects.data.length === 0 && (
          <section className="panel onboarding" data-testid="no-projects">
            <h2>No projects yet</h2>
            <p>Create one, then upload a JUnit report from CI:</p>
            <pre>{`curl -X POST localhost:8080/api/v1/projects \\
  -H 'Content-Type: application/json' -d '{"name":"my-app"}'

curl -X POST "localhost:8080/api/v1/runs?commitSha=$GIT_SHA&branch=main" \\
  -H "X-API-Key: $FLAKEHUNTER_KEY" -H 'Content-Type: application/xml' \\
  --data-binary @target/surefire-reports/TEST-report.xml`}</pre>
          </section>
        )}
        {projectId !== undefined && (
          <ProjectView
            key={projectId}
            api={api}
            projectId={projectId}
            filter={filter}
            onFilterChange={setFilter}
            selectedTestId={selectedTestId}
            onSelectTest={setSelectedTestId}
          />
        )}
      </main>
    </div>
  )
}

interface ProjectViewProps {
  api: ApiClient
  projectId: number
  filter: VerdictFilter
  onFilterChange: (f: VerdictFilter) => void
  selectedTestId: number | undefined
  onSelectTest: (id: number | undefined) => void
}

function ProjectView({ api, projectId, filter, onFilterChange, selectedTestId, onSelectTest }: ProjectViewProps) {
  const summary = useAsync(`summary:${projectId}`, () => api.getProjectSummary(projectId))
  const tests = useAsync(`tests:${projectId}:${filter}`, () => api.listTests(projectId, filter))
  const runs = useAsync(`runs:${projectId}`, () => api.listRuns(projectId))
  const clusters = useAsync(`clusters:${projectId}`, () => api.listClusters(projectId))

  if (summary.error) {
    return (
      <p className="error" role="alert" data-testid="error">
        {summary.error.message}
      </p>
    )
  }
  if (!summary.data) {
    return <p className="muted" data-testid="loading">Loading…</p>
  }

  return (
    <>
      <SummaryCards summary={summary.data} />
      <div className={selectedTestId ? 'grid with-detail' : 'grid'}>
        <div className="stack">
          <TestsTable
            tests={tests.data ?? []}
            filter={filter}
            onFilterChange={onFilterChange}
            onSelect={onSelectTest}
            selectedTestId={selectedTestId}
          />
          <RunsTable runs={runs.data?.items ?? []} />
          <ClustersPanel clusters={clusters.data} unavailable={Boolean(clusters.error)} />
        </div>
        {selectedTestId !== undefined && (
          <TestDetail api={api} projectId={projectId} testId={selectedTestId} onClose={() => onSelectTest(undefined)} />
        )}
      </div>
    </>
  )
}

function TestDetail(props: { api: ApiClient; projectId: number; testId: number; onClose: () => void }) {
  const { api, projectId, testId, onClose } = props
  const detail = useAsync(`detail:${projectId}:${testId}`, () => api.getTestDetail(projectId, testId))
  if (detail.error) return <p className="error panel">{detail.error.message}</p>
  if (!detail.data) return <aside className="panel detail muted">Loading…</aside>
  return <TestDetailPanel detail={detail.data} onClose={onClose} />
}
