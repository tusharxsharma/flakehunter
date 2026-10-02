#!/usr/bin/env python3
"""Fills a running FlakeHunter with a realistic demo project.

    python scripts/seed_demo.py                 # against http://localhost:8080
    FLAKEHUNTER_API_URL=http://host:8080 python scripts/seed_demo.py --runs 40

Simulates 30 CI runs of an e-commerce test suite: mostly stable tests, a few flaky ones with
realistic failure messages, one test that broke recently, and one that only fails on first attempt.
The random seed is fixed, so every run of this script produces the same history.
"""

from __future__ import annotations

import argparse
import random
import time
from html import escape

from flakehunter_client import API_URL, create_project, upload_xml

STABLE = [
    ("com.shop.cart.CartServiceTest", "addsItemToCart"),
    ("com.shop.cart.CartServiceTest", "removesItemFromCart"),
    ("com.shop.cart.CartServiceTest", "calculatesSubtotal"),
    ("com.shop.catalog.SearchTest", "findsProductsByName"),
    ("com.shop.catalog.SearchTest", "sortsByPrice"),
    ("com.shop.catalog.SearchTest", "paginatesResults"),
    ("com.shop.auth.LoginTest", "rejectsWrongPassword"),
    ("com.shop.auth.LoginTest", "locksAccountAfterFiveAttempts"),
    ("com.shop.orders.OrderHistoryTest", "listsPastOrders"),
    ("com.shop.orders.OrderHistoryTest", "downloadsInvoicePdf"),
    ("com.shop.payments.RefundTest", "refundsFullOrder"),
    ("com.shop.payments.RefundTest", "refundsPartialOrder"),
    ("checkout.spec.ts", "guest checkout completes"),
    ("checkout.spec.ts", "saved address is prefilled"),
    ("profile.spec.ts", "user can change avatar"),
]

# (suite, name, failure probability, failure message)
FLAKY = [
    ("com.shop.payments.PaymentGatewayTest", "chargesCardWith3DS", 0.4,
     "java.util.concurrent.TimeoutException: Timed out after 5000 ms waiting for 3DS callback"),
    ("checkout.spec.ts", "applies discount code", 0.35,
     "TimeoutError: locator.click: Timeout 30000ms exceeded waiting for getByRole('button', { name: 'Apply' })"),
    ("com.shop.inventory.StockReservationTest", "reservesLastItemOnce", 0.3,
     "org.opentest4j.AssertionFailedError: expected: <1> but was: <2> (concurrent reservations)"),
    ("com.shop.notifications.EmailTest", "sendsOrderConfirmation", 0.1,
     "java.net.ConnectException: Connection refused: smtp-sandbox:2525"),
]

BROKEN = ("com.shop.tax.TaxCalculatorTest", "appliesEuVatRules",
          "org.opentest4j.AssertionFailedError: expected: <21.00> but was: <19.00>")
RETRY_FLAKE = ("profile.spec.ts", "user can upload documents")


def testcase(suite: str, name: str, failure: str | None = None, flaky_retry: bool = False) -> str:
    duration = f"{random.uniform(0.05, 2.5):.3f}"
    body = ""
    if failure:
        body = f'<failure message="{escape(failure)}">{escape(failure)}\n\tat {escape(suite)}.{escape(name)}</failure>'
    elif flaky_retry:
        body = '<flakyFailure message="StaleElementReferenceException: stale element reference: element is not attached"/>'
    return f'<testcase classname="{escape(suite)}" name="{escape(name)}" time="{duration}">{body}</testcase>'


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--name", default=f"demo-shop-{int(time.time()) % 100000}")
    parser.add_argument("--runs", type=int, default=30)
    args = parser.parse_args()
    random.seed(42)

    project = create_project(args.name)
    print(f"Created project '{project['name']}' (id {project['id']})")

    for run in range(args.runs):
        cases = [testcase(s, n) for s, n in STABLE]
        for suite, name, probability, message in FLAKY:
            cases.append(testcase(suite, name, message if random.random() < probability else None))
        broken_now = run >= args.runs - 4
        cases.append(testcase(BROKEN[0], BROKEN[1], BROKEN[2] if broken_now else None))
        cases.append(testcase(*RETRY_FLAKE, flaky_retry=random.random() < 0.3))

        xml = f'<?xml version="1.0"?><testsuites><testsuite name="shop">{"".join(cases)}</testsuite></testsuites>'
        commit = f"{random.getrandbits(40):010x}"
        upload_xml(project["apiKey"], xml, commit, branch="main", build_id=f"demo-{run}")
        print(f"  uploaded run {run + 1}/{args.runs}", end="\r")

    print(f"\nDone. Dashboard: http://localhost:3000/?project={project['id']}")
    print(f"API:       {API_URL}/api/v1/projects/{project['id']}/tests")
    print(f"API key:   {project['apiKey']}  (shown once - keep it if you want to upload more runs)")


if __name__ == "__main__":
    main()
