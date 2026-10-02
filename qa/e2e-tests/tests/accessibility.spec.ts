import AxeBuilder from '@axe-core/playwright'
import { expect, test } from './fixtures'

/** Automated WCAG 2.1 AA checks with axe-core. Catches missing labels, contrast problems, bad ARIA. */
test.describe('Accessibility', () => {
  test('dashboard has no serious WCAG violations', async ({ dashboard, checkoutProject, page }) => {
    await dashboard.open(checkoutProject.id)
    await expect(dashboard.testRows).toHaveCount(1)

    const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze()
    const serious = results.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical')

    expect(serious, JSON.stringify(serious.map((v) => ({ id: v.id, nodes: v.nodes.length })), null, 2)).toEqual([])
  })

  test('test detail panel is accessible', async ({ dashboard, checkoutProject, page }) => {
    await dashboard.open(checkoutProject.id)
    await dashboard.openTest('appliesDiscount')

    const results = await new AxeBuilder({ page }).include('[data-testid="test-detail"]').analyze()
    const serious = results.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical')

    expect(serious.map((v) => v.id)).toEqual([])
  })
})
