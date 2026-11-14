"""Cross-language assertion contract test.

The Python Signer must produce a compact JWS in the same format that the BFF's
DelegatedAssertionAuthenticator (Java) can verify.  The contract is:
  - Three dot-separated base64url parts (no padding)
  - Header JSON: {"alg": "EdDSA", "typ": "JWT"}
  - Payload JSON: required claims with integer iat/exp
  - Signature: Ed25519 over ASCII bytes of "header.payload"

The BFF test helper DelegatedAssertionTestKeys.PRIMARY_* uses the same Ed25519 key pair
that is embedded in fixtures.TEST_SIGNING_KEY_PEM / TEST_PUBLIC_KEY_B64, so an assertion
produced by the Python Signer can be verified by the BFF.

This test verifies the Python Signer's output STRUCTURALLY matches what the BFF expects,
using the same algorithm and claim set.  The companion Java test
(McpAssertionContractTest) verifies the reverse: that Java-produced tokens are accepted
by the Python verifier.
"""
from __future__ import annotations

import base64
import json
import time

from tests.gateway.fixtures import ACME_SUB, TEST_PUBLIC_KEY_B64, TEST_SIGNING_KEY_PEM


def _b64url_decode(s: str) -> bytes:
    pad = 4 - len(s) % 4
    return base64.urlsafe_b64decode(s + "=" * (pad % 4))


def test_pythonSigner_producesExpectedFormat():
    """Python Signer produces a compact JWS the BFF verifier can parse."""
    from ohmytradeagent_mcp.gateway.assertion import Signer

    signer = Signer(TEST_SIGNING_KEY_PEM)
    token = signer.assertion(ACME_SUB, "acme", "test-client")

    # Exactly two dots (compact JWS)
    parts = token.split(".")
    assert len(parts) == 3, f"expected 3 parts, got {len(parts)}: {token!r}"

    # Header
    hdr = json.loads(_b64url_decode(parts[0]))
    assert hdr["alg"] == "EdDSA", f"wrong alg: {hdr}"
    assert hdr.get("typ") == "JWT"

    # Payload: required claims
    pay = json.loads(_b64url_decode(parts[1]))
    assert pay["iss"] == "omt-mcp-gateway"
    assert pay["aud"] == "tenant-dashboard-bff"
    assert pay["provider"] == "google"
    assert pay["sub"] == ACME_SUB
    assert pay["tenant"] == "acme"
    assert pay["client_id"] == "test-client"

    # Timestamps: integers; exp - iat == 60; exp > now
    assert isinstance(pay["iat"], int), "iat must be integer"
    assert isinstance(pay["exp"], int), "exp must be integer"
    assert pay["exp"] - pay["iat"] == 60
    assert pay["exp"] > int(time.time()), "assertion must not already be expired"

    # No padding in any part (base64url no-padding)
    for part in parts:
        assert "=" not in part, f"padding found in JWS part: {part!r}"


def test_pythonSigner_signatureVerifiable_withEd25519():
    """The signature can be verified with the matching Ed25519 public key."""
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
    from cryptography.hazmat.primitives.serialization import (
        load_der_public_key,
    )

    from ohmytradeagent_mcp.gateway.assertion import Signer

    signer = Signer(TEST_SIGNING_KEY_PEM)
    token = signer.assertion(ACME_SUB, "acme", "test-client")
    parts = token.split(".")

    # Reconstruct the signed input
    sig_input = (parts[0] + "." + parts[1]).encode("ascii")
    sig_bytes = _b64url_decode(parts[2])

    # Load the matching public key (same as Java DelegatedAssertionTestKeys.PRIMARY_PUBLIC_KEY_B64)
    pub_der = base64.b64decode(TEST_PUBLIC_KEY_B64)
    pub_key = load_der_public_key(pub_der)
    assert isinstance(pub_key, Ed25519PublicKey)

    # This must not raise
    pub_key.verify(sig_bytes, sig_input)


def test_pythonSigner_javaCompatibleClaims_claimTypes():
    """Integer claims (iat, exp) are JSON integers, not strings — matches Java expectation.

    Java's DelegatedAssertionAuthenticator.requireLong() checks for Integer or Long
    JSON types; a string "1234" would fail.
    """
    from ohmytradeagent_mcp.gateway.assertion import Signer

    signer = Signer(TEST_SIGNING_KEY_PEM)
    token = signer.assertion(ACME_SUB, "acme", "test-client")
    parts = token.split(".")
    raw_payload = _b64url_decode(parts[1]).decode("utf-8")

    # Parse as raw JSON to check types
    pay = json.loads(raw_payload)
    assert type(pay["iat"]) is int, f"iat should be int, got {type(pay['iat'])}"
    assert type(pay["exp"]) is int, f"exp should be int, got {type(pay['exp'])}"
    # sub, tenant, client_id must be strings
    assert type(pay["sub"]) is str
    assert type(pay["tenant"]) is str
    assert type(pay["client_id"]) is str
