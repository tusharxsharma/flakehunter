#!/usr/bin/env python3
"""Dogfooding: FlakeHunter analyses FlakeHunter's own test results.

CI collects the JUnit XML written by every suite in this repository (Maven Surefire/Failsafe,
pytest, Vitest, Cucumber, Playwright), uploads them as one run and prints the verdicts. It doubles
as a compatibility test: the parser must accept every report format this project produces.

    python scripts/dogfood.py --commit $GITHUB_SHA --branch main reports/**/*.xml
"""

from __future__ import annotations

import argparse
import glob
import sys
from pathlib import Path

from flakehunter_client import API_URL, create_project, get, upload_files


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("patterns", nargs="+", help="JUnit XML files or glob patterns")
    parser.add_argument("--commit", required=True)
    parser.add_argument("--branch", default="main")
    parser.add_argument("--build-id")
    args = parser.parse_args()

    files = sorted({Path(p) for pattern in args.patterns for p in glob.glob(pattern, recursive=True)})
    files = [f for f in files if f.is_file() and f.stat().st_size > 0]
    if not files:
        print("No JUnit XML files found", file=sys.stderr)
        return 1

    project = create_project(f"flakehunter-self-{args.commit[:7]}")
    result = upload_files(project["apiKey"], files, args.commit[:40], args.branch, args.build_id)
    run = result["run"]
    print(f"Uploaded {len(files)} report files: {run['total']} tests, {run['passed']} passed, "
          f"{run['failed']} failed, {run['skipped']} skipped")

    tests = get(f"{API_URL}/api/v1/projects/{project['id']}/tests?verdict=ALL")
    flaky = [t for t in tests if t["verdict"] == "FLAKY"]
    if flaky:
        print("Flaky tests detected in this build (failed, then passed on retry):")
        for t in flaky:
            print(f"  - {t['testKey']}")
    else:
        print("No flaky tests in this build.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
