import { type Locator, type Page, expect } from '@playwright/test'

type Filter = 'flaky' | 'broken' | 'all'

/**
 * Page Object for the dashboard. Tests talk to this class, never to raw selectors, so a markup
 * change is fixed in one place instead of in every test. Locators prefer data-testid and
 * accessible roles over CSS classes, which change often.
 */
export class DashboardPage {
  readonly projectSelect: Locator
  readonly testRows: Locator
  readonly testsEmpty: Locator
  readonly runRows: Locator
  readonly detailPanel: Locator
  readonly historyCells: Locator
  readonly failureMessage: Locator
  readonly clustersPanel: Locator
  readonly errorBanner: Locator

  constructor(readonly page: Page) {
    this.projectSelect = page.getByTestId('project-select')
    this.testRows = page.getByTestId('test-row')
    this.testsEmpty = page.getByTestId('tests-empty')
    this.runRows = page.getByTestId('run-row')
    this.detailPanel = page.getByTestId('test-detail')
    this.historyCells = this.detailPanel.getByTestId('history-strip').getByRole('listitem')
    this.failureMessage = page.getByTestId('failure-message')
    this.clustersPanel = page.getByTestId('clusters-panel')
    this.errorBanner = page.getByTestId('error')
  }

  async open(projectId?: number): Promise<void> {
    await this.page.goto(projectId === undefined ? '/' : `/?project=${projectId}`)
  }

  card(name: 'runs' | 'tests' | 'pass-rate' | 'flaky' | 'broken' | 'quarantined'): Locator {
    return this.page.getByTestId(`card-${name}`)
  }

  async filterBy(filter: Filter): Promise<void> {
    const tab = this.page.getByTestId(`filter-${filter}`)
    await tab.click()
    await expect(tab).toHaveAttribute('aria-selected', 'true')
  }

  row(testName: string): Locator {
    return this.testRows.filter({ has: this.page.getByTestId('test-name').getByText(testName, { exact: true }) })
  }

  async openTest(testName: string): Promise<void> {
    await this.row(testName).getByTestId('test-name').click()
    await expect(this.detailPanel).toBeVisible()
  }

  async closeTest(): Promise<void> {
    await this.page.getByTestId('close-detail').click()
    await expect(this.detailPanel).toBeHidden()
  }

  async expectTestNames(names: string[]): Promise<void> {
    await expect(this.testRows.getByTestId('test-name')).toHaveText(names)
  }
}
