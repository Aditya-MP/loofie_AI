from datetime import date, datetime

import pytest

from app.importer import parse as p


@pytest.mark.parametrize(
    "raw, want",
    [
        ("glowwithmira", "glowwithmira"),
        ("@GlowWithMira ", "glowwithmira"),
        ("https://www.instagram.com/glowwithmira/", "glowwithmira"),
        ("https://instagram.com/glow.with_mira?igsh=abc", "glow.with_mira"),
        ("", None),
        (None, None),
    ],
)
def test_handle(raw, want):
    assert p.handle(raw) == want


@pytest.mark.parametrize("raw", ["https://www.instagram.com/reel/DbX1/", "two words", "a" * 31])
def test_handle_rejects(raw):
    with pytest.raises(p.ParseError):
        p.handle(raw)


def test_handles_list():
    assert p.handles("@brand_one, brand.two  brand3") == ["brand_one", "brand.two", "brand3"]
    assert p.handles(None) == []


@pytest.mark.parametrize(
    "raw, want",
    [
        (12400, 12400),
        (12400.0, 12400),
        ("12,400", 12400),
        ("12.4K", 12400),
        ("1.2M", 1_200_000),
        ("1.5 lakh", 150_000),
        ("₹15,000", 15000),
        ("", None),
        ("N/A", None),
        (None, None),
    ],
)
def test_integer(raw, want):
    assert p.integer(raw) == want


@pytest.mark.parametrize("raw", ["about 5k", "12k+", True])
def test_integer_rejects(raw):
    with pytest.raises(p.ParseError):
        p.integer(raw)


def test_price_range():
    assert p.price_range("349-799") == (349, 799)
    assert p.price_range("299 to 899") == (299, 899)
    assert p.price_range("499") == (499, 499)
    assert p.price_range(None) == (None, None)


def test_yes_no():
    assert p.yes_no("Yes") is True
    assert p.yes_no("no") is False
    assert p.yes_no("") is None
    with pytest.raises(p.ParseError):
        p.yes_no("maybe")


@pytest.mark.parametrize(
    "raw, want",
    [
        ("2026-11-14", date(2026, 11, 14)),
        ("05/12/2026", date(2026, 12, 5)),  # day first, as written in India
        ("5 Dec 2026", date(2026, 12, 5)),
        (datetime(2026, 11, 14, 9, 30), date(2026, 11, 14)),
        (None, None),
    ],
)
def test_date(raw, want):
    assert p.a_date(raw) == want


def test_date_rejects():
    with pytest.raises(p.ParseError):
        p.a_date("next friday")


def test_choice_is_case_insensitive_and_returns_canonical():
    assert p.choice("kannada", {"Kannada", "English"}) == "Kannada"
    with pytest.raises(p.ParseError):
        p.choice("Klingon", {"Kannada"})


def test_post_url_strips_tracking():
    raw = "https://www.instagram.com/reel/Dbnmc8_TsAH/?utm_source=ig_web_copy_link"
    assert p.post_url(raw) == "https://www.instagram.com/reel/Dbnmc8_TsAH/"
    with pytest.raises(p.ParseError):
        p.post_url("https://www.modash.io/x")
