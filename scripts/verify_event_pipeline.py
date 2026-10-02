#!/usr/bin/env python3
"""End-to-end check of the event pipeline across both services:

    upload report (Java API) -> outbox -> Kafka -> triage consumer (Python) -> clusters API

Exits 0 when the triage service has clustered the uploaded failures, 1 on timeout.
Used by CI against the docker compose stack.
"""

from __future__ import annotations

import sys
import time
import uuid

from flakehunter_client import TRIAGE_URL, create_project, get, upload_xml

TIMEOUT_SECONDS = 90


def main() -> int:
    project = create_project(f"pipeline-{uuid.uuid4().hex[:8]}")
    xml = (
        '<testsuite name="pipeline">'
        '<testcase classname="PipelineTest" name="a"><failure message="Timed out after 812 ms"/></testcase>'
        '<testcase classname="PipelineTest" name="b"><failure message="Timed out after 97 ms"/></testcase>'
        '<testcase classname="PipelineTest" name="c"><failure message="Connection refused: db:5432"/></testcase>'
        "</testsuite>"
    )
    upload_xml(project["apiKey"], xml, commit_sha="abcdef1")
    print(f"Uploaded 3 failures for project {project['id']}; waiting for the triage service...")

    deadline = time.monotonic() + TIMEOUT_SECONDS
    while time.monotonic() < deadline:
        clusters = get(f"{TRIAGE_URL}/api/v1/projects/{project['id']}/clusters")
        if sum(c["occurrences"] for c in clusters) == 3:
            for c in clusters:
                print(f"  {c['category']:<15} x{c['occurrences']}  {c['signature']}")
            # The two timeouts differ only in numbers, so they must share one cluster.
            if len(clusters) != 2:
                print(f"Expected 2 clusters, got {len(clusters)}", file=sys.stderr)
                return 1
            print("Event pipeline OK")
            return 0
        time.sleep(2)

    print(f"Triage service did not process the event within {TIMEOUT_SECONDS}s", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
