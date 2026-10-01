import type { ApiClient } from '../api/client'
import type { FailureCluster, HistoryEntry, ProjectSummary, Run, TestAnalysis } from '../api/types'

export const run = (overrides: Partial<Run> = {}): Run => ({
  id: 1,
  commitSha: 'abc1234def5678',
  branch: 'main',
  buildId: 'ci-1',
  total: 10,
  passed: 9,
  failed: 1,
  skipped: 0,
  durationMs: 12_500,
  createdAt: new Date().toISOString(),
  ...overrides,
})

export const summary = (overrides: Partial<ProjectSummary> = {}): ProjectSummary => ({
  id: 1,
  name: 'checkout-web',
  createdAt: '2026-09-01T00:00:00Z',
  totalRuns: 42,
  totalTests: 120,
  runsInWindow: 30,
  passRate: 0.9531,
  flakyTests: 3,
  brokenTests: 1,
  quarantinedTests: 2,
  lastRun: run(),
  ...overrides,
})

export const testAnalysis = (overrides: Partial<TestAnalysis> = {}): TestAnalysis => ({
  testId: 7,
  testKey: 'com.shop.CartTest::appliesDiscount',
  suite: 'com.shop.CartTest',
  name: 'appliesDiscount',
  file: null,
  quarantined: false,
  quarantineReason: null,
  verdict: 'FLAKY',
  score: 0.77,
  flipRate: 0.77,
  failureRate: 0.4,
  inconsistentCommits: 0,
  runsAnalyzed: 10,
  lastStatus: 'FAILED',
  ...overrides,
})

export const history = (pattern: string): HistoryEntry[] =>
  // pattern is oldest -> newest ("PFP"); the API returns newest first
  [...pattern]
    .map((c, i) => ({
      runId: i + 1,
      commitSha: `c0ffee${i}`,
      branch: 'main',
      createdAt: new Date().toISOString(),
      status: c === 'P' ? ('PASSED' as const) : c === 'F' ? ('FAILED' as const) : ('SKIPPED' as const),
      durationMs: 10,
      failureMessage: c === 'F' ? `failure in run ${i + 1}` : null,
    }))
    .reverse()

export const cluster = (overrides: Partial<FailureCluster> = {}): FailureCluster => ({
  id: 1,
  project_id: 1,
  fingerprint: 'a1b2c3d4e5f60718',
  signature: 'timed out after <n> ms',
  category: 'TIMEOUT',
  root_cause: 'The test waited for something that did not finish in time.',
  likely_flaky: true,
  suggested_action: 'Use explicit waits.',
  classified_by: 'heuristic',
  occurrences: 5,
  retry_passes: 2,
  first_seen: '2026-09-20T00:00:00Z',
  last_seen: '2026-09-26T00:00:00Z',
  sample_message: 'Timed out after 500 ms',
  tests: [{ test_key: 'Cart::a', occurrences: 5 }],
  ...overrides,
})

/** An ApiClient whose every method is replaceable; defaults return a healthy one-project world. */
export function fakeApi(overrides: Partial<ApiClient> = {}): ApiClient {
  return {
    listProjects: async () => [{ id: 1, name: 'checkout-web', createdAt: '2026-09-01T00:00:00Z' }],
    getProjectSummary: async () => summary(),
    listTests: async () => [testAnalysis()],
    getTestDetail: async () => ({ test: testAnalysis(), history: history('PFPPFPFPPF') }),
    listRuns: async () => ({ items: [run()], page: 0, size: 10, total: 1 }),
    listClusters: async () => [cluster()],
    ...overrides,
  }
}
