import { createProject, findTestId, quarantine, uploadHistory } from '../support/seed'
import { expect, test } from './fixtures'

test.describe('Dashboard', () => {
  test('summarises the project', async ({ dashboard, checkoutProject }) => {
    await dashboard.open(checkoutProject.id)

    await expect(dashboard.card('runs')).toContainText('10')
    await expect(dashboard.card('tests')).toContainText('3')
    await expect(dashboard.card('flaky')).toContainText('1')
    await expect(dashboard.card('broken')).toContainText('1')
    await expect(dashboard.runRows).toHaveCount(10)
  })

  test('lists only flaky tests by default', async ({ dashboard, checkoutProject }) => {
    await dashboard.open(checkoutProject.id)

    await dashboard.expectTestNames(['appliesDiscount'])
    await expect(dashboard.row('appliesDiscount').getByTestId('verdict-badge')).toHaveText('Flaky')
  })

  test('filters broken tests and all tests', async ({ dashboard, checkoutProject }) => {
    await dashboard.open(checkoutProject.id)

    await dashboard.filterBy('broken')
    await dashboard.expectTestNames(['processesRefund'])

    await dashboard.filterBy('all')
    // Sorted by flakiness score, highest first
    await dashboard.expectTestNames(['appliesDiscount', 'processesRefund', 'addsItemToCart'])
  })

  test('shows a test history as a timeline', async ({ dashboard, checkoutProject }) => {
    await dashboard.open(checkoutProject.id)

    await dashboard.openTest('appliesDiscount')

    await expect(dashboard.historyCells).toHaveCount(10)
    const statuses = await dashboard.historyCells.evaluateAll((cells) => cells.map((c) => c.getAttribute('data-status')))
    expect(statuses.map((s) => s?.[0]).join('')).toBe('PFPPFPFPPF')
    await expect(dashboard.failureMessage).toContainText('expected 200 but was 503')

    await dashboard.closeTest()
  })

  test('reflects a quarantine made through the API', async ({ dashboard, checkoutProject, request }) => {
    const testId = await findTestId(request, checkoutProject, 'appliesDiscount')
    await quarantine(request, checkoutProject, testId, 'Tracked in QA-101')

    await dashboard.open(checkoutProject.id)

    await expect(dashboard.row('appliesDiscount').getByTestId('quarantined-badge')).toBeVisible()
    await expect(dashboard.card('quarantined')).toContainText('1')
    await dashboard.openTest('appliesDiscount')
    await expect(dashboard.page.getByTestId('quarantine-note')).toContainText('QA-101')
  })

  test('keeps the selected project in a shareable URL', async ({ dashboard, checkoutProject, page }) => {
    await dashboard.open(checkoutProject.id)
    await expect(dashboard.card('runs')).toContainText('10')

    await page.reload()

    await expect(page).toHaveURL(new RegExp(`project=${checkoutProject.id}`))
    await expect(dashboard.projectSelect).toHaveValue(String(checkoutProject.id))
  })

  test('switches between projects', async ({ dashboard, checkoutProject, request }) => {
    const other = await createProject(request, 'other')
    await uploadHistory(request, other, { 'OtherTest.only': 'PP' })

    await dashboard.open(checkoutProject.id)
    await expect(dashboard.card('runs')).toContainText('10')

    await dashboard.projectSelect.selectOption(String(other.id))

    await expect(dashboard.card('runs')).toContainText('2')
    await expect(dashboard.testsEmpty).toBeVisible()
  })
})

test.describe('Security', () => {
  test('renders hostile test names as text, never as HTML', async ({ dashboard, request, page }) => {
    const project = await createProject(request, 'xss')
    // No "." in the payload: the seed helper splits "Suite.name" on the last dot
    const payload = '<img src=x onerror=alert(1)>'
    await uploadHistory(request, project, { [`EvilTest.${payload}`]: 'PFPFPF' })

    let dialogOpened = false
    page.on('dialog', async (dialog) => {
      dialogOpened = true
      await dialog.dismiss()
    })

    await dashboard.open(project.id)

    await expect(dashboard.testRows.getByTestId('test-name')).toHaveText(payload)
    await expect(page.locator('img[src="x"]')).toHaveCount(0)
    expect(dialogOpened).toBe(false)
  })
})
