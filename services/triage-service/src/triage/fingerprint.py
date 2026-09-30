"""Failure fingerprinting.

Two failures with the same root cause rarely have identical messages: timestamps, ports, ids,
temp paths and line numbers differ on every run. Fingerprinting strips that noise so that
"Timed out after 512 ms" and "Timed out after 498 ms" land in the same cluster.

The pipeline is deterministic and dependency-free, so it is cheap to run on every failure and
easy to unit test. Only the *first* occurrence of a cluster is sent to the (expensive) LLM.
"""

from __future__ import annotations

import hashlib
import re

# Order matters: specific patterns must run before the generic number pattern.
_NORMALIZERS: list[tuple[re.Pattern[str], str]] = [
    (re.compile(r"\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b", re.I), "<uuid>"),
    (re.compile(r"\b\d{4}-\d{2}-\d{2}[t ]\d{2}:\d{2}:\d{2}(?:[.,]\d+)?(?:z|[+-]\d{2}:?\d{2})?", re.I), "<ts>"),
    (re.compile(r"\b0x[0-9a-f]+\b", re.I), "<hex>"),
    (re.compile(r"@[0-9a-f]{6,}\b", re.I), "@<hex>"),  # Java identity hash: Object@1b6d3586
    (re.compile(r"\b[0-9a-f]{12,40}\b", re.I), "<sha>"),  # commit SHAs, request ids
    (re.compile(r"https?://[^\s\"'<>]+", re.I), "<url>"),
    (re.compile(r"(?:[a-z]:)?(?:[\\/][\w.\-]+){2,}", re.I), "<path>"),
    (re.compile(r"\"[^\"]{0,200}\"|'[^']{0,200}'"), "<str>"),
    (re.compile(r"\d+(?:\.\d+)?"), "<n>"),
    (re.compile(r"\s+"), " "),
]

_MAX_SIGNATURE_LINES = 3


def normalize(text: str) -> str:
    """Replaces volatile tokens (numbers, ids, paths, quoted values...) with placeholders."""
    result = text.strip().lower()
    for pattern, replacement in _NORMALIZERS:
        result = pattern.sub(replacement, result)
    return result.strip()


def signature(message: str | None) -> str:
    """The first few meaningful lines of a failure, normalized.

    The first line usually holds the exception type and message; the next lines the top stack
    frames, which distinguish "NPE in CartService" from "NPE in TaxService".
    """
    if not message or not message.strip():
        return "<no message>"
    lines = [line for line in (raw.strip() for raw in message.splitlines()) if line]
    head = lines[:_MAX_SIGNATURE_LINES]
    return " | ".join(normalize(line) for line in head)


def fingerprint(message: str | None) -> str:
    """Stable 16-hex-char identifier of a failure's root cause."""
    return hashlib.sha256(signature(message).encode("utf-8")).hexdigest()[:16]
