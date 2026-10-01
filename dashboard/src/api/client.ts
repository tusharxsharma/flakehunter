import type {
  FailureCluster,
  Problem,
  Project,
  ProjectSummary,
  RunPage,
  TestAnalysis,
  TestDetail,
  Verdict,
} from './types'

export class ApiError extends Error {
  readonly status: number
  readonly code: string | undefined

  constructor(status: number, message: string, code?: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
  }
}

type Fetch = typeof fetch

/**
 * Thin typed wrapper around fetch. The two backends sit behind one origin:
 * /api/* is the Java API, /triage/* is the Python triage service (see nginx.conf / vite.config.ts).
 */
export function createApiClient(fetchImpl: Fetch = (...args) => fetch(...args)) {
  async function get<T>(path: string): Promise<T> {
    const response = await fetchImpl(path, { headers: { Accept: 'application/json' } })
    if (!response.ok) {
      let problem: Partial<Problem> = {}
      try {
        problem = (await response.json()) as Problem
      } catch {
        // non-JSON error body (e.g. a proxy error page)
      }
      throw new ApiError(response.status, problem.detail ?? `Request failed with status ${response.status}`, problem.code)
    }
    return (await response.json()) as T
  }

  return {
    listProjects: () => get<Project[]>('/api/v1/projects'),
    getProjectSummary: (projectId: number) => get<ProjectSummary>(`/api/v1/projects/${projectId}`),
    listTests: (projectId: number, verdict: Verdict | 'ALL' = 'FLAKY') =>
      get<TestAnalysis[]>(`/api/v1/projects/${projectId}/tests?verdict=${verdict}`),
    getTestDetail: (projectId: number, testId: number, limit = 30) =>
      get<TestDetail>(`/api/v1/projects/${projectId}/tests/${testId}?limit=${limit}`),
    listRuns: (projectId: number, size = 10) => get<RunPage>(`/api/v1/projects/${projectId}/runs?size=${size}`),
    listClusters: (projectId: number, limit = 5) =>
      get<FailureCluster[]>(`/triage/api/v1/projects/${projectId}/clusters?limit=${limit}`),
  }
}

export type ApiClient = ReturnType<typeof createApiClient>

export const api = createApiClient()
