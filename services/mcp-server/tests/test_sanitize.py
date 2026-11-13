"""Tests for the sanitise module (Seam E direct unit tests).

The plan allows direct sanitize() unit tests only for the unparseable-subject
branch and the full redaction+wrap walk, since no tool can be made to emit an
unparseable subject through the stub BFF.
"""
from __future__ import annotations

import json

from ohmytradeagent_mcp.sanitize import sanitize


def test_sanitize_unparseableSubject_isOmitted():
    """An unparseable subject string is replaced with the sentinel."""
    data = {"subject": "{not json"}
    result = sanitize(data)
    assert result == {"subject": "[unparseable subject omitted]"}


def test_sanitize_dropsIdsAtAnyDepth_andWrapsAuthorText():
    """
    account_number, broker_order_id and operator_id (D1) are dropped at any depth
    — including inside a subject JSON string.  author, raw_line and tail are
    wrapped as {"untrusted": true, "text": <value>}.
    """
    # Build a structure with ids at the top level, inside a subject string,
    # and inside a list.
    data = {
        "account_number": "310056593",
        "broker_order_id": "ORD-001",
        "operator_id": "admin@example.com",
        "author": "SomeTrader",
        "raw_line": "BTO 10 SPY 400C @1.50",
        "tail": "extra text",
        "nested": {
            "account_number": "SHOULD_BE_GONE",
            "broker_order_id": "ALSO_GONE",
            "operator_id": "op@example.com",
            "keep_me": "yes",
        },
        "list_field": [
            {"broker_order_id": "LIST_GONE", "value": 42},
        ],
        "subject": json.dumps({
            "account_number": "INNER_ACCOUNT",
            "broker_order_id": "INNER_ORDER",
            "operator_id": "inner@example.com",
            "signal_id": "sig-1",
            "author": "InnerAuthor",
        }),
    }

    result = sanitize(data)
    dumped = json.dumps(result)

    # Redacted ids must not appear anywhere in the output.
    assert "account_number" not in dumped
    assert "310056593" not in dumped
    assert "broker_order_id" not in dumped
    assert "ORD-001" not in dumped
    assert "operator_id" not in dumped
    assert "admin@example.com" not in dumped
    assert "op@example.com" not in dumped
    assert "inner@example.com" not in dumped
    assert "INNER_ACCOUNT" not in dumped
    assert "INNER_ORDER" not in dumped
    assert "LIST_GONE" not in dumped
    assert "SHOULD_BE_GONE" not in dumped
    assert "ALSO_GONE" not in dumped

    # Nested non-redacted key should survive.
    assert isinstance(result, dict)
    assert result["nested"]["keep_me"] == "yes"  # type: ignore[index]

    # author, raw_line, tail are wrapped as untrusted.
    assert result["author"] == {"untrusted": True, "text": "SomeTrader"}  # type: ignore[index]
    assert result["raw_line"] == {"untrusted": True, "text": "BTO 10 SPY 400C @1.50"}  # type: ignore[index]
    assert result["tail"] == {"untrusted": True, "text": "extra text"}  # type: ignore[index]

    # Subject was parsed and walked; signal_id survives; author inside is wrapped.
    subject_val = result["subject"]  # type: ignore[index]
    assert isinstance(subject_val, dict)
    assert subject_val["signal_id"] == "sig-1"
    assert subject_val["author"] == {"untrusted": True, "text": "InnerAuthor"}

    # List item: broker_order_id gone, value kept.
    assert result["list_field"] == [{"value": 42}]  # type: ignore[index]
