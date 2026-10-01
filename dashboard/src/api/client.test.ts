import { describe, expect, it, vi } from 'vitest'
import { ApiError, createApiClient } from './client'

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

describe('api client', () => {
  it('calls the Java API for tests with the verdict filter', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse([]))
    const api = createApiClient(fetchMock)

    await api.listTests(3, 'BROKEN')

    expect(fetchMock).toHaveBeenCalledWith('/api/v1/projects/3/tests?verdict=BROKEN', expect.anything())
  })

  it('routes cluster requests to the triage service', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse([]))

    await createApiClient(fetchMock).listClusters(3)

    expect(fetchMock.mock.calls[0][0]).toBe('/triage/api/v1/projects/3/clusters?limit=5')
  })

  it('surfaces problem+json details as ApiError', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ status: 404, detail: 'Project 9 not found', code: 'not_found' }, 404))

    const error = await createApiClient(fetchMock).getProjectSummary(9).catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ status: 404, code: 'not_found', message: 'Project 9 not found' })
  })

  it('handles non-JSON error bodies', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response('<html>Bad Gateway</html>', { status: 502 }))

    await expect(createApiClient(fetchMock).listProjects()).rejects.toThrow('Request failed with status 502')
  })
})
