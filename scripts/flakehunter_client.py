"""Tiny standard-library client for the FlakeHunter API, shared by the scripts in this folder."""

from __future__ import annotations

import json
import os
import uuid
from pathlib import Path
from typing import Any
from urllib import error, parse, request

API_URL = os.environ.get("FLAKEHUNTER_API_URL", "http://localhost:8080").rstrip("/")
TRIAGE_URL = os.environ.get("FLAKEHUNTER_TRIAGE_URL", "http://localhost:8000").rstrip("/")


def _send(req: request.Request) -> tuple[int, Any]:
    try:
        with request.urlopen(req, timeout=30) as response:
            body = response.read()
            return response.status, json.loads(body) if body else None
    except error.HTTPError as e:
        body = e.read()
        return e.code, json.loads(body) if body else None


def get(url: str) -> Any:
    status, body = _send(request.Request(url, headers={"Accept": "application/json"}))
    if status != 200:
        raise RuntimeError(f"GET {url} -> {status}: {body}")
    return body


def create_project(name: str) -> dict[str, Any]:
    req = request.Request(
        f"{API_URL}/api/v1/projects",
        data=json.dumps({"name": name}).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    status, body = _send(req)
    if status != 201:
        raise RuntimeError(f"Could not create project {name}: {status} {body}")
    return body


def upload_xml(api_key: str, xml: str, commit_sha: str, branch: str = "main", build_id: str | None = None) -> Any:
    params = {"commitSha": commit_sha, "branch": branch}
    if build_id:
        params["buildId"] = build_id
    req = request.Request(
        f"{API_URL}/api/v1/runs?{parse.urlencode(params)}",
        data=xml.encode("utf-8"),
        headers={"Content-Type": "application/xml", "X-API-Key": api_key},
        method="POST",
    )
    status, body = _send(req)
    if status not in (200, 201):
        raise RuntimeError(f"Upload failed: {status} {body}")
    return body


def upload_files(api_key: str, files: list[Path], commit_sha: str, branch: str, build_id: str | None) -> Any:
    """Multipart upload of several JUnit XML files as ONE run (e.g. Surefire's TEST-*.xml)."""
    boundary = uuid.uuid4().hex
    parts = []
    for path in files:
        parts.append(
            f'--{boundary}\r\nContent-Disposition: form-data; name="files"; filename="{path.name}"\r\n'
            f"Content-Type: application/xml\r\n\r\n".encode()
            + path.read_bytes()
            + b"\r\n"
        )
    body = b"".join(parts) + f"--{boundary}--\r\n".encode()
    params = {"commitSha": commit_sha, "branch": branch}
    if build_id:
        params["buildId"] = build_id
    req = request.Request(
        f"{API_URL}/api/v1/runs?{parse.urlencode(params)}",
        data=body,
        headers={"Content-Type": f"multipart/form-data; boundary={boundary}", "X-API-Key": api_key},
        method="POST",
    )
    status, response = _send(req)
    if status not in (200, 201):
        raise RuntimeError(f"Upload failed: {status} {response}")
    return response
