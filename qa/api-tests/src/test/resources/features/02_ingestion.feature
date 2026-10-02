Feature: Uploading JUnit reports from CI
  Every CI build uploads its JUnit XML so FlakeHunter can track each test over time.

  Background:
    Given a project named "shop" exists

  Scenario: Upload a report with passed, failed and skipped tests
    When I upload a report for commit "a1b2c3d4" with:
      | test                     | outcome |
      | CartTest.addsItem        | passed  |
      | CartTest.appliesDiscount | failed  |
      | CartTest.usesWallet      | skipped |
    Then the response status is 201
    And the response matches the "ingestion-response" schema
    And the run has 3 tests: 1 passed, 1 failed and 1 skipped

  Scenario: Re-uploading the same CI build is idempotent
    Given I uploaded a passing report for build "ci-100"
    When I upload the same report for build "ci-100" again
    Then the response status is 200
    And the run is marked as a duplicate of the first upload

  Scenario: The summary counts uploaded runs
    Given I uploaded a passing report for build "ci-1"
    When I request the summary of project "shop"
    Then the summary shows 1 runs and 1 tests

  @security
  Scenario: XML with a DOCTYPE (XXE attack) is rejected
    When I upload a report containing an XXE payload
    Then the response status is 422
    And the error code is "invalid_report"
    And the error message mentions "DOCTYPE"

  Scenario: Malformed XML is rejected
    When I upload a report that is not valid XML
    Then the response status is 422
    And the error code is "invalid_report"

  Scenario: The commit SHA must be hexadecimal
    When I upload a report for the invalid commit "not-a-sha"
    Then the response status is 400
    And the error message mentions "commitSha"

  @security
  Scenario: Uploads require an API key
    When I upload a report without an API key
    Then the response status is 401
    And the error code is "missing_api_key"

  @security
  Scenario: A made-up API key is rejected
    When I upload a report with the API key "fh_this-key-does-not-exist"
    Then the response status is 401
    And the error code is "invalid_api_key"
