"""Google ID-token verification — negative tests (item 5 of the fix round).

Each test proves that one specific guard in ``GoogleIdP.exchange()`` rejects
bad tokens.  The mutation note below each test shows what to break to make it
go RED, confirming the guard is load-bearing.

Setup uses a locally-generated RSA-2048 key pair so no real Google endpoints
are contacted.  ``PyJWKClient`` is patched so it returns our signing key.
"""
from __future__ import annotations

import base64
import json
import time
from dataclasses import dataclass
from typing import Any
from unittest.mock import MagicMock, patch

import httpx
import pytest

# ---------------------------------------------------------------------------
# RSA key pair generated once per module (fast enough, ~0.2 s)
# ---------------------------------------------------------------------------
from cryptography.hazmat.primitives.asymmetric import rsa

_PRIVATE_KEY = rsa.generate_private_key(public_exponent=65537, key_size=2048)
_PUBLIC_KEY = _PRIVATE_KEY.public_key()

_GOOGLE_ISSUER = "https://accounts.google.com"
_CLIENT_ID = "test-google-client-id"
_REDIRECT_URI = "https://tradeagent-mcp.ridopark.com/oauth/google/callback"


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _make_token(
    *,
    sub: str = "user-sub-123",
    iss: str = _GOOGLE_ISSUER,
    aud: str = _CLIENT_ID,
    exp_offset: int = 300,  # seconds from now; negative = expired
    nonce: str = "test-nonce",
    email_verified: bool = True,
    sign_with=None,  # defaults to _PRIVATE_KEY
    algorithm: str = "RS256",
) -> str:
    """Build a JWT signed with our test RSA key."""
    import jwt as pyjwt

    now = int(time.time())
    payload: dict[str, Any] = {
        "iss": iss,
        "aud": aud,
        "sub": sub,
        "iat": now,
        "exp": now + exp_offset,
        "nonce": nonce,
        "email_verified": email_verified,
    }
    key = sign_with if sign_with is not None else _PRIVATE_KEY
    return pyjwt.encode(payload, key, algorithm=algorithm)


def _make_unsigned_token(payload: dict) -> str:
    """Create a JWT with alg=none (no signature)."""

    def _b64(data: bytes) -> str:
        return base64.urlsafe_b64encode(data).rstrip(b"=").decode()

    header = _b64(json.dumps({"alg": "none", "typ": "JWT"}).encode())
    body = _b64(json.dumps(payload).encode())
    return f"{header}.{body}."  # empty signature


@dataclass
class _FakeSigningKey:
    key: object


def _mock_jwks(signing_key_obj: object):
    """Return a MagicMock that mimics PyJWKClient returning *signing_key_obj*."""
    mock_client = MagicMock()
    mock_client.get_signing_key_from_jwt.return_value = _FakeSigningKey(signing_key_obj)
    mock_jwks_cls = MagicMock(return_value=mock_client)
    return mock_jwks_cls


def _make_idp(id_token: str, signing_key_obj: object):
    """Return a (GoogleIdP, patch_target) pair wired for *id_token*."""
    from ohmytradeagent_mcp.gateway.google import GoogleIdP

    token_resp = httpx.Response(200, json={"id_token": id_token, "token_type": "Bearer"})
    transport = httpx.MockTransport(lambda r: token_resp)

    idp = GoogleIdP(
        client_id=_CLIENT_ID,
        client_secret="secret",
        jwks_uri="https://fake-jwks.example.com/certs",
        token_uri="https://fake-token.example.com/token",
        _transport=transport,
    )
    return idp, _mock_jwks(signing_key_obj)


# ---------------------------------------------------------------------------
# Negative tests
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_exchange_wrongIssuer_raises():
    """Token with wrong issuer is rejected.

    Mutation: drop ``issuer=_GOOGLE_ISSUER`` from ``pyjwt.decode`` → test goes RED
    (exchange succeeds and returns an Identity).
    """
    token = _make_token(iss="https://evil.example.com/issuer")
    idp, mock_jwks_cls = _make_idp(token, _PUBLIC_KEY)

    with patch("jwt.PyJWKClient", mock_jwks_cls), pytest.raises(Exception) as exc_info:
        await idp.exchange("code", _REDIRECT_URI)

    exc_str = str(exc_info.value).lower()
    assert "issuer" in exc_str or "invalid" in exc_str, (
        f"Expected issuer error but got: {exc_info.value!r}"
    )


@pytest.mark.asyncio
async def test_exchange_wrongAudience_raises():
    """Token with wrong audience is rejected.

    Mutation: drop ``audience=self._client_id`` from ``pyjwt.decode`` → test goes RED.
    """
    token = _make_token(aud="wrong-client-id-not-ours")
    idp, mock_jwks_cls = _make_idp(token, _PUBLIC_KEY)

    with patch("jwt.PyJWKClient", mock_jwks_cls), pytest.raises(Exception) as exc_info:
        await idp.exchange("code", _REDIRECT_URI)

    exc_str = str(exc_info.value).lower()
    assert "audience" in exc_str or "invalid" in exc_str, (
        f"Expected audience error but got: {exc_info.value!r}"
    )


@pytest.mark.asyncio
async def test_exchange_expiredToken_raises():
    """Expired token is rejected.

    Mutation: ``options={"verify_exp": False}`` → test goes RED (exchange returns Identity).
    """
    token = _make_token(exp_offset=-300)  # expired 5 minutes ago
    idp, mock_jwks_cls = _make_idp(token, _PUBLIC_KEY)

    with patch("jwt.PyJWKClient", mock_jwks_cls), pytest.raises(Exception) as exc_info:
        await idp.exchange("code", _REDIRECT_URI)

    exc_str = str(exc_info.value).lower()
    assert "expir" in exc_str or "signature has expired" in exc_str or "invalid" in exc_str, (
        f"Expected expiry error but got: {exc_info.value!r}"
    )


@pytest.mark.asyncio
async def test_exchange_badSignature_raises():
    """Token signed by a different key is rejected.

    Mutation: ``algorithms=["RS256", "none"]`` or swap the signing-key check → test goes RED.
    """
    different_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    token = _make_token(sign_with=different_key)  # signed by a different private key

    # But our JWKS mock returns our PUBLIC key — verification will fail
    idp, mock_jwks_cls = _make_idp(token, _PUBLIC_KEY)

    with patch("jwt.PyJWKClient", mock_jwks_cls), pytest.raises(Exception) as exc_info:
        await idp.exchange("code", _REDIRECT_URI)

    exc_str = str(exc_info.value).lower()
    assert "signature" in exc_str or "invalid" in exc_str, (
        f"Expected signature error but got: {exc_info.value!r}"
    )


@pytest.mark.asyncio
async def test_exchange_algHS256_raises():
    """HS256 token is rejected (alg=HS256 not in allowed algorithms).

    Mutation: ``algorithms=["RS256", "HS256"]`` with signing_key.key set to the HMAC
    secret → token would verify and exchange returns Identity → test goes RED.

    Design: we sign with HMAC secret and return the same secret as signing_key.key so
    if HS256 were allowed, the decode would succeed.
    """
    import jwt as pyjwt

    hmac_secret = b"hmac-test-secret-at-least-32-bytes-long"
    now = int(time.time())
    payload = {
        "iss": _GOOGLE_ISSUER,
        "aud": _CLIENT_ID,
        "sub": "user-sub",
        "iat": now,
        "exp": now + 300,
        "nonce": "nonce",
        "email_verified": True,
    }
    hs256_token = pyjwt.encode(payload, hmac_secret, algorithm="HS256")

    # signing_key.key = hmac_secret: if HS256 were allowed, decode would succeed
    idp, mock_jwks_cls = _make_idp(hs256_token, hmac_secret)

    with patch("jwt.PyJWKClient", mock_jwks_cls), pytest.raises(Exception) as exc_info:
        await idp.exchange("code", _REDIRECT_URI)

    # algorithms=["RS256"] → InvalidAlgorithmError("The specified alg value is not allowed")
    exc_str = str(exc_info.value).lower()
    assert any(kw in exc_str for kw in ("alg", "algorithm", "allowed", "not allowed")), (
        f"Expected algorithm-rejection error but got: {exc_info.value!r}"
    )


@pytest.mark.asyncio
async def test_exchange_algNone_raises():
    """alg=none token is rejected (unsigned JWT must not be accepted).

    Mutation: ``algorithms=["RS256", "none"]`` or ``options={"verify_signature": False}``
    → token would be accepted and exchange returns Identity → test goes RED.
    """
    now = int(time.time())
    none_token = _make_unsigned_token({
        "iss": _GOOGLE_ISSUER,
        "aud": _CLIENT_ID,
        "sub": "user-sub",
        "iat": now,
        "exp": now + 300,
        "nonce": "nonce",
        "email_verified": True,
    })

    idp, mock_jwks_cls = _make_idp(none_token, _PUBLIC_KEY)

    with patch("jwt.PyJWKClient", mock_jwks_cls), pytest.raises(Exception) as exc_info:
        await idp.exchange("code", _REDIRECT_URI)

    # algorithms=["RS256"] → InvalidAlgorithmError or DecodeError
    exc_str = str(exc_info.value).lower()
    assert any(kw in exc_str for kw in ("alg", "algorithm", "allowed", "decode", "none")), (
        f"Expected alg=none rejection but got: {exc_info.value!r}"
    )


@pytest.mark.asyncio
async def test_exchange_missingSub_raises():
    """Token missing the 'sub' claim causes an error (KeyError propagates).

    Mutation: change ``claims["sub"]`` to ``claims.get("sub", "")`` → returns
    Identity(sub="") instead of raising → test goes RED.
    """
    import jwt as pyjwt

    now = int(time.time())
    # Build payload without 'sub'
    payload = {
        "iss": _GOOGLE_ISSUER,
        "aud": _CLIENT_ID,
        "iat": now,
        "exp": now + 300,
        "nonce": "nonce",
        "email_verified": True,
        # 'sub' intentionally absent
    }
    token = pyjwt.encode(payload, _PRIVATE_KEY, algorithm="RS256")
    idp, mock_jwks_cls = _make_idp(token, _PUBLIC_KEY)

    with patch("jwt.PyJWKClient", mock_jwks_cls), pytest.raises(KeyError):
        await idp.exchange("code", _REDIRECT_URI)


@pytest.mark.asyncio
async def test_exchange_emailVerifiedFalse_returnsIdentityWithFalse():
    """email_verified=False is returned as False in Identity (not rejected at IdP level).

    The provider layer (not GoogleIdP) is responsible for the access_denied redirect.
    This test verifies the IdP correctly surfaces the flag.
    Mutation: change ``bool(claims.get("email_verified", False))`` to ``True``
    → email_verified would always be True → test goes RED.

    Note: test_callback_unverifiedEmail_redirectsAccessDenied covers the full flow.
    """
    token = _make_token(email_verified=False)
    idp, mock_jwks_cls = _make_idp(token, _PUBLIC_KEY)

    with patch("jwt.PyJWKClient", mock_jwks_cls):
        identity = await idp.exchange("code", _REDIRECT_URI)

    assert identity.email_verified is False, (
        f"Expected email_verified=False, got {identity.email_verified!r}"
    )


@pytest.mark.asyncio
async def test_exchange_happyPath_returnsIdentity():
    """Sanity: valid token with all claims returns a correct Identity."""
    token = _make_token(sub="user-123", nonce="nonce-abc")
    idp, mock_jwks_cls = _make_idp(token, _PUBLIC_KEY)

    with patch("jwt.PyJWKClient", mock_jwks_cls):
        identity = await idp.exchange("code", _REDIRECT_URI)

    assert identity.sub == "user-123"
    assert identity.email_verified is True
    assert identity.nonce == "nonce-abc"
