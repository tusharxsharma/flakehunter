import { type APIRequestContext, expect } from '@playwright/test'
import { randomUUID } from 'node:crypto'

export const API_URL = process.env.API_URL ?? 'http://localhost:8080'

export interface SeededProject {
  id: number
  name: string
  apiKey: string
}

/** Every test gets its own project, so tests are isolated and can run in parallel. */
export async function createProject(request: APIRequestContext, prefix = 'e2e'): Promise<SeededProject> {
  const name = `${prefix}-${randomUUID().slice(0, 8)}`
  const response = await request.post(`${API_URL}/api/v1/projects`, { data: { name } })
  expect(response.status(), await response.text()).toBe(201)
  return (await response.json()) as SeededProject
}

const escapeXml = (s: string) =>
  s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')

/**
 * Uploads one CI run per column of the patterns. Patterns read oldest to newest: P = pass, F = fail.
 *   uploadHistory(request, project, { 'CartTest.pay': 'PFPF', 'CartTest.browse': 'PPPP' })
 */
export async function uploadHistory(
  request: APIRequestContext,
  project: SeededProject,
  histories: Record<string, string>,
): Promise<void> {
  const runs = Math.max(...Object.values(histories).map((p) => p.length))
  for (let run = 0; run < runs; run++) {
    const cases = Object.entries(histories)
      .filter(([, pattern]) => run < pattern.length)
      .map(([test, pattern]) => {
        const dot = test.lastIndexOf('.')
        const suite = escapeXml(test.slice(0, dot))
        const name = escapeXml(test.slice(dot + 1))
        const failure =
          pattern[run] === 'F' ? `<failure message="${name} failed: expected 200 but was 503 (run ${run})"/>` : ''
        return `<testcase classname="${suite}" name="${name}" time="0.2">${failure}</testcase>`
      })
      .join('')
    const response = await request.post(`${API_URL}/api/v1/runs`, {
      headers: { 'X-API-Key': project.apiKey, 'Content-Type': 'application/xml' },
      params: { commitSha: (0xe2e0000 + run).toString(16), branch: 'main', buildId: `build-${run}` },
      data: `<testsuite name="e2e">${cases}</testsuite>`,
    })
    expect(response.status(), await response.text()).toBe(201)
  }
}

export async function quarantine(
  request: APIRequestContext,
  project: SeededProject,
  testId: number,
  reason: string,
): Promise<void> {
  const response = await request.put(`${API_URL}/api/v1/projects/${project.id}/tests/${testId}/quarantine`, {
    headers: { 'X-API-Key': project.apiKey },
    data: { reason },
  })
  expect(response.status()).toBe(200)
}

export async function findTestId(request: APIRequestContext, project: SeededProject, name: string): Promise<number> {
  const response = await request.get(`${API_URL}/api/v1/projects/${project.id}/tests?verdict=ALL`)
  const tests = (await response.json()) as { testId: number; name: string }[]
  const match = tests.find((t) => t.name === name)
  if (!match) throw new Error(`Test ${name} not found`)
  return match.testId
}
