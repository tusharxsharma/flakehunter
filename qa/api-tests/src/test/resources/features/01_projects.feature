Feature: Project management
  A team registers a project once and receives an API key for its CI pipeline.

  Scenario: Create a project and receive its API key exactly once
    When I create a project named "checkout"
    Then the response status is 201
    And the response matches the "project-created" schema
    And the API key starts with "fh_"
    And the project appears in the project list without its API key

  Scenario: Project names are unique
    Given a project named "payments" exists
    When I create another project named "payments"
    Then the response status is 409
    And the error code is "conflict"

  Scenario Outline: Invalid project names are rejected with a helpful error
    When I create a project with the exact name "<name>"
    Then the response status is 400
    And the error code is "validation_failed"
    And the response matches the "problem" schema

    Examples:
      | name          |
      | a             |
      | Has Spaces    |
      | UPPERCASE     |
      | -leading-dash |

  Scenario: A new project starts empty
    Given a project named "fresh" exists
    When I request the summary of project "fresh"
    Then the summary shows 0 runs and 0 tests

  Scenario: Unknown projects return a problem+json 404
    When I request the summary of a project that does not exist
    Then the response status is 404
    And the error code is "not_found"
