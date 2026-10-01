// Mirrors the Java API's response records (web/dto/Dtos.java) and the triage service's models.

export type Verdict = 'STABLE' | 'FLAKY' | 'BROKEN' | 'INSUFFICIENT_DATA'
export type TestStatus = 'PASSED' | 'FAILED' | 'SKIPPED'

export interface Project {
  id: number
  name: string
  createdAt: string
}

export interface Run {
  id: number
  commitSha: string
  branch: string
  buildId: string | null
  total: number
  passed: number
  failed: number
  skipped: number
  durationMs: number
  createdAt: string
}

export interface ProjectSummary extends Project {
  totalRuns: number
  totalTests: number
  runsInWindow: number
  passRate: number
  flakyTests: number
  brokenTests: number
  quarantinedTests: number
  lastRun: Run | null
}

export interface TestAnalysis {
  testId: number
  testKey: string
  suite: string
  name: string
  file: string | null
  quarantined: boolean
  quarantineReason: string | null
  verdict: Verdict
  score: number
  flipRate: number
  failureRate: number
  inconsistentCommits: number
  runsAnalyzed: number
  lastStatus: TestStatus | null
}

export interface HistoryEntry {
  runId: number
  commitSha: string
  branch: string
  createdAt: string
  status: TestStatus
  durationMs: number
  failureMessage: string | null
}

export interface TestDetail {
  test: TestAnalysis
  history: HistoryEntry[]
}

export interface RunPage {
  items: Run[]
  page: number
  size: number
  total: number
}

export interface FailureCluster {
  id: number
  project_id: number
  fingerprint: string
  signature: string
  category: string
  root_cause: string
  likely_flaky: boolean
  suggested_action: string
  classified_by: 'heuristic' | 'llm'
  occurrences: number
  retry_passes: number
  first_seen: string
  last_seen: string
  sample_message: string | null
  tests: { test_key: string; occurrences: number }[]
}

/** RFC 9457 problem details, as returned by the Java API on every error. */
export interface Problem {
  status: number
  title?: string
  detail?: string
  code?: string
}
