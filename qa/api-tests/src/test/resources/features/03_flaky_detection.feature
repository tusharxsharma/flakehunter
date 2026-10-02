Feature: Flaky test detection
  FlakeHunter separates tests that fail randomly (flaky) from tests that fail consistently (broken),
  using each test's history across CI runs. Histories read oldest to newest: P = pass, F = fail.

  Background:
    Given a project named "detection" exists

  Scenario: A test that passes and fails randomly is flagged as flaky
    Given the test "CheckoutTest.pay" has the CI history "PFPPFPFPPF"
    And the test "CheckoutTest.browse" has the CI history "PPPPPPPPPP"
    And the CI history is uploaded
    When I list the tests with verdict "FLAKY"
    Then the listed tests are:
      | name | verdict |
      | pay  | FLAKY   |
    And the flakiness score of "pay" is at least 0.5
    And the response matches the "test-analysis-list" schema

  Scenario: A test that keeps failing is broken, not flaky
    Given the test "RefundTest.refunds" has the CI history "PPPPPPPFFF"
    And the CI history is uploaded
    When I list the tests with verdict "BROKEN"
    Then the listed tests are:
      | name    | verdict |
      | refunds | BROKEN  |

  Scenario: A test that broke once and was fixed is not flaky
    Given the test "TaxTest.rates" has the CI history "PPPFFPPPPPPPPPPPPPPP"
    And the CI history is uploaded
    When I list the tests with verdict "FLAKY"
    Then no tests are listed

  Scenario: Failing and then passing on retry within one build is flaky
    Given a build where "SearchTest.findsProducts" failed and then passed on retry
    When I list the tests with verdict "FLAKY"
    Then the listed tests are:
      | name          | verdict |
      | findsProducts | FLAKY   |
    And "findsProducts" has 1 inconsistent commit

  Scenario: An unknown verdict filter is rejected
    When I list the tests with verdict "SOMETIMES"
    Then the response status is 400
