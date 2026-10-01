import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { cluster, history, testAnalysis } from '../test/fixtures'
import { ClustersPanel } from './ClustersPanel'
import { HistoryStrip } from './HistoryStrip'
import { ScoreBar } from './ScoreBar'
import { TestDetailPanel } from './TestDetailPanel'
import { TestsTable } from './TestsTable'

describe('HistoryStrip', () => {
  it('shows executions oldest first with an accessible status for each', () => {
    render(<HistoryStrip history={history('PPF')} />)

    const cells = within(screen.getByTestId('history-strip')).getAllByRole('listitem')
    expect(cells.map((c) => c.dataset.status)).toEqual(['PASSED', 'PASSED', 'FAILED'])
    expect(cells[2]).toHaveTextContent('FAILED')
  })
})

describe('ScoreBar', () => {
  it.each([
    [0.1, 'score-low'],
    [0.45, 'score-medium'],
    [0.9, 'score-high'],
  ])('score %s uses %s', (score, cls) => {
    const { container } = render(<ScoreBar score={score} />)
    expect(container.querySelector('.score-fill')).toHaveClass(cls)
  })

  it('clamps out-of-range values', () => {
    render(<ScoreBar score={1.7} />)
    expect(screen.getByRole('meter')).toHaveAttribute('aria-valuenow', '1')
    expect(screen.getByText('1.00')).toBeInTheDocument()
  })
})

describe('TestsTable', () => {
  const noop = () => {}

  it('renders one row per test with verdict and quarantine badges', () => {
    render(
      <TestsTable
        tests={[testAnalysis(), testAnalysis({ testId: 8, name: 'refunds', verdict: 'BROKEN', quarantined: true })]}
        filter="ALL"
        onFilterChange={noop}
        onSelect={noop}
      />,
    )

    expect(screen.getAllByTestId('test-row')).toHaveLength(2)
    expect(screen.getAllByTestId('verdict-badge').map((b) => b.textContent)).toEqual(['Flaky', 'Broken'])
    expect(screen.getAllByTestId('quarantined-badge')).toHaveLength(1)
  })

  it('reports filter changes and selections', async () => {
    const onFilterChange = vi.fn()
    const onSelect = vi.fn()
    render(<TestsTable tests={[testAnalysis()]} filter="FLAKY" onFilterChange={onFilterChange} onSelect={onSelect} />)

    await userEvent.click(screen.getByTestId('filter-broken'))
    await userEvent.click(screen.getByTestId('test-name'))

    expect(onFilterChange).toHaveBeenCalledWith('BROKEN')
    expect(onSelect).toHaveBeenCalledWith(7)
    expect(screen.getByTestId('filter-flaky')).toHaveAttribute('aria-selected', 'true')
  })

  it('shows a friendly empty state', () => {
    render(<TestsTable tests={[]} filter="FLAKY" onFilterChange={noop} onSelect={noop} />)
    expect(screen.getByTestId('tests-empty')).toHaveTextContent('No flaky tests detected')
  })
})

describe('TestDetailPanel', () => {
  it('shows the latest failure message as plain text', () => {
    const malicious = history('PF')
    malicious[0].failureMessage = '<img src=x onerror="alert(1)">'

    render(<TestDetailPanel detail={{ test: testAnalysis(), history: malicious }} onClose={() => {}} />)

    const pre = screen.getByTestId('failure-message')
    expect(pre).toHaveTextContent('<img src=x onerror="alert(1)">')
    expect(pre.querySelector('img')).toBeNull()
  })

  it('shows the quarantine reason', () => {
    render(
      <TestDetailPanel
        detail={{ test: testAnalysis({ quarantined: true, quarantineReason: 'JIRA-42' }), history: history('P') }}
        onClose={() => {}}
      />,
    )
    expect(screen.getByTestId('quarantine-note')).toHaveTextContent('JIRA-42')
  })
})

describe('ClustersPanel', () => {
  it('lists clusters with their suggested next step', () => {
    render(<ClustersPanel clusters={[cluster({ category: 'UI_ELEMENT' })]} unavailable={false} />)

    expect(screen.getByTestId('cluster')).toHaveTextContent('ui element')
    expect(screen.getByTestId('cluster')).toHaveTextContent('Use explicit waits.')
  })

  it('degrades gracefully when the triage service is down', () => {
    render(<ClustersPanel clusters={undefined} unavailable />)
    expect(screen.getByTestId('clusters-unavailable')).toBeInTheDocument()
  })
})
