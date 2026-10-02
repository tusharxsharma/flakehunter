import { expect, test } from './fixtures'

/**
 * Network mocking with page.route(): simulate backend failures that are hard to produce with a
 * real environment, and verify the UI degrades gracefully instead of breaking.
 */
test.describe('Resilience', () => {
  test('shows an error banner when the API is down', async ({ dashboard, page }) => {
    await page.route('**/api/v1/projects', (route) =>
      route.fulfill({
        status: 503,
        contentType: 'application/problem+json',
        body: JSON.stringify({ status: 503, detail: 'Service temporarily unavailable', code: 'unavailable' }),
      }),
    )

    await dashboard.open()

    await expect(dashboard.errorBanner).toContainText('Service temporarily unavailable')
  })

  test('still shows flakiness data when only the triage service is down', async ({ dashboard, checkoutProject, page }) => {
    await page.route('**/triage/**', (route) => route.abort('connectionrefused'))

    await dashboard.open(checkoutProject.id)

    await expect(page.getByTestId('clusters-unavailable')).toBeVisible()
    await dashboard.expectTestNames(['appliesDiscount'])
  })

  test('shows onboarding help when no projects exist', async ({ dashboard, page }) => {
    await page.route('**/api/v1/projects', (route) => route.fulfill({ json: [] }))

    await dashboard.open()

    await expect(page.getByTestId('no-projects')).toContainText('No projects yet')
  })
})
