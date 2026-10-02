import { test as base } from '@playwright/test'
import { DashboardPage } from '../pages/DashboardPage'
import { createProject, type SeededProject, uploadHistory } from '../support/seed'

/**
 * Custom fixtures: each test declares what it needs (`dashboard`, `checkoutProject`) and
 * Playwright builds it. Seeding goes through the API, not the UI, which keeps tests fast and
 * focused on the behaviour they actually verify.
 */
export const test = base.extend<{ dashboard: DashboardPage; checkoutProject: SeededProject }>({
  dashboard: async ({ page }, use) => {
    await use(new DashboardPage(page))
  },

  checkoutProject: async ({ request }, use) => {
    const project = await createProject(request, 'checkout')
    await uploadHistory(request, project, {
      'CheckoutTest.addsItemToCart': 'PPPPPPPPPP', // stable
      'CheckoutTest.appliesDiscount': 'PFPPFPFPPF', // flaky
      'CheckoutTest.processesRefund': 'PPPPPPPFFF', // broken
    })
    await use(project)
  },
})

export { expect } from '@playwright/test'
