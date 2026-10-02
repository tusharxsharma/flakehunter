Feature: Quarantining flaky tests
  A quarantined test keeps running but no longer blocks the build. CI pipelines read the
  quarantine list to skip or soft-fail those tests.

  Background:
    Given a project named "mobile" exists
    And the test "LoginTest.logsIn" has the CI history "PFPFPFPF"
    And the CI history is uploaded

  Scenario: Quarantine a flaky test
    When I quarantine "logsIn" with reason "Flaky on CI, tracked in QA-101"
    Then the response status is 200
    And the response matches the "quarantine-entry" schema
    And the quarantine list contains "logsIn" with reason "Flaky on CI, tracked in QA-101"

  Scenario: Release a test from quarantine
    Given I quarantine "logsIn" with reason "temporary"
    When I release "logsIn" from quarantine
    Then the response status is 204
    And the quarantine list is empty

  @security
  Scenario: A project cannot quarantine another project's tests
    Given a project named "intruder" exists
    When project "intruder" tries to quarantine "logsIn" in project "mobile"
    Then the response status is 403
    And the error code is "forbidden"
