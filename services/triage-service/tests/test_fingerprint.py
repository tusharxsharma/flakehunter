import pytest

from triage.fingerprint import fingerprint, normalize, signature


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        ("Timed out after 512 ms", "timed out after <n> ms"),
        ("expected: <90.0> but was: <100.0>", "expected: <<n>> but was: <<n>>"),
        ("order 3f2b1c9e-8d7a-4b6c-9e5f-1a2b3c4d5e6f not found", "order <uuid> not found"),
        ("at 2026-09-26T10:15:30.123Z the job failed", "at <ts> the job failed"),
        ("segfault at 0x7ffee4b2c8a0", "segfault at <hex>"),
        ("com.shop.Cart@1b6d3586 was null", "com.shop.cart@<hex> was null"),
        ("GET https://api.shop.test/v1/orders/42 returned 503", "get <url> returned <n>"),
        ("cannot open /tmp/junit123/data.json", "cannot open <path>"),
        ("User 'alice' already exists", "user <str> already exists"),
        ("commit 9fceb02d0ae598e95dc970b74767f19372d61af8 missing", "commit <sha> missing"),
        ("  lots   of\t whitespace  ", "lots of whitespace"),
    ],
)
def test_normalize_replaces_volatile_tokens(raw: str, expected: str) -> None:
    assert normalize(raw) == expected


def test_same_root_cause_with_different_numbers_shares_a_fingerprint() -> None:
    a = "java.util.concurrent.TimeoutException: Timed out after 500 ms\n\tat com.shop.Tax.fetch(Tax.java:42)"
    b = "java.util.concurrent.TimeoutException: Timed out after 731 ms\n\tat com.shop.Tax.fetch(Tax.java:57)"
    assert fingerprint(a) == fingerprint(b)


def test_different_root_causes_have_different_fingerprints() -> None:
    assert fingerprint("Connection refused: localhost:6379") != fingerprint("expected 1 but was 2")


def test_top_stack_frame_distinguishes_same_exception_in_different_code() -> None:
    cart = "NullPointerException\n\tat com.shop.CartService.total(CartService.java:10)"
    tax = "NullPointerException\n\tat com.shop.TaxService.rate(TaxService.java:10)"
    assert fingerprint(cart) != fingerprint(tax)


def test_only_the_first_lines_matter() -> None:
    head = "AssertionError: boom\nline two\nline three"
    assert fingerprint(head + "\nnoise A") == fingerprint(head + "\ncompletely different noise B")


@pytest.mark.parametrize("empty", [None, "", "   \n  "])
def test_missing_messages_share_one_bucket(empty: str | None) -> None:
    assert signature(empty) == "<no message>"
    assert fingerprint(empty) == fingerprint(None)


def test_fingerprint_is_short_stable_hex() -> None:
    fp = fingerprint("anything")
    assert len(fp) == 16
    assert int(fp, 16) >= 0
    assert fp == fingerprint("anything")
