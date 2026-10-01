import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { ApiError } from './api/client'
import { fakeApi, summary } from './test/fixtures'

describe('App', () => {
  beforeEach(() => {
    window.history.replaceState(null, '', '/')
  })

  it('loads the first project and shows its summary and flaky tests', async () => {
    render(<App api={fakeApi()} />)

    expect(await screen.findByTestId('card-runs')).toHaveTextContent('42')
    expect(screen.getByTestId('card-pass-rate')).toHaveTextContent('95.3%')
    expect(await screen.findAllByTestId('test-row')).toHaveLength(1)
    expect(window.location.search).toBe('?project=1')
  })

  it('opens the test detail panel with the execution history', async () => {
    render(<App api={fakeApi()} />)

    await userEvent.click(await screen.findByTestId('test-name'))

    expect(await screen.findByTestId('test-detail')).toBeInTheDocument()
    expect(screen.getByTestId('history-strip').children).toHaveLength(10)

    await userEvent.click(screen.getByTestId('close-detail'))
    expect(screen.queryByTestId('test-detail')).not.toBeInTheDocument()
  })

  it('requests tests again when the verdict filter changes', async () => {
    const listTests = vi.fn().mockResolvedValue([])
    render(<App api={fakeApi({ listTests })} />)

    await userEvent.click(await screen.findByTestId('filter-broken'))

    await waitFor(() => expect(listTests).toHaveBeenLastCalledWith(1, 'BROKEN'))
  })

  it('switches projects from the selector', async () => {
    const getProjectSummary = vi.fn(async (id: number) => summary({ id, totalRuns: id * 10 }))
    const api = fakeApi({
      listProjects: async () => [
        { id: 1, name: 'web', createdAt: '' },
        { id: 2, name: 'mobile', createdAt: '' },
      ],
      getProjectSummary,
    })
    render(<App api={api} />)
    await screen.findByTestId('card-runs')

    await userEvent.selectOptions(screen.getByTestId('project-select'), '2')

    await waitFor(() => expect(screen.getByTestId('card-runs')).toHaveTextContent('20'))
  })

  it('honours ?project= in the URL', async () => {
    window.history.replaceState(null, '', '/?project=5')
    const getProjectSummary = vi.fn(async (id: number) => summary({ id }))
    render(<App api={fakeApi({ getProjectSummary })} />)

    await screen.findByTestId('card-runs')
    expect(getProjectSummary).toHaveBeenCalledWith(5)
  })

  it('shows onboarding help when there are no projects', async () => {
    render(<App api={fakeApi({ listProjects: async () => [] })} />)
    expect(await screen.findByTestId('no-projects')).toHaveTextContent('No projects yet')
  })

  it('shows an error when the API is unreachable', async () => {
    const api = fakeApi({ listProjects: () => Promise.reject(new ApiError(502, 'Bad gateway')) })
    render(<App api={api} />)
    expect(await screen.findByTestId('error')).toHaveTextContent('Bad gateway')
  })

  it('keeps working when only the triage service is down', async () => {
    const api = fakeApi({ listClusters: () => Promise.reject(new ApiError(503, 'down')) })
    render(<App api={api} />)

    expect(await screen.findByTestId('clusters-unavailable')).toBeInTheDocument()
    expect(screen.getByTestId('card-flaky')).toHaveTextContent('3')
  })
})
