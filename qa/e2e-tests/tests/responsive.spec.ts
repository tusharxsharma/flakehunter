import { expect, test } from './fixtures'

/** Runs in the "mobile" project (Pixel 7 viewport) - see playwright.config.ts. */
test('dashboard is usable on a phone', async ({ dashboard, checkoutProject, page }) => {
  await dashboard.open(checkoutProject.id)

  await expect(dashboard.card('flaky')).toBeVisible()
  await dashboard.openTest('appliesDiscount')
  await expect(dashboard.historyCells).toHaveCount(10)

  // No horizontal page scroll: wide tables scroll inside their own container instead.
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)
  expect(overflow).toBeLessThanOrEqual(1)
})
