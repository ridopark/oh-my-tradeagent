"""EdDSA assertion signer for the MCP gateway.

`Signer.assertion(sub, tenant, client_id)` returns a compact JWS string
(`header.payload.sig`, base64url no padding) signed with the gateway's Ed25519
private key.  The claim set matches what `DelegatedAssertionAuthenticator` on the
BFF expects:

    iss  = "omt-mcp-gateway"
    aud  = "tenant-dashboard-bff"
    provider = "google"
    sub  = <subject>
    tenant = <tenant>
    client_id = <client_id>
    iat  = now (integer seconds)
    exp  = now + 60 (integer seconds)

The signature is Ed25519 over the ASCII bytes of "header.payload".

No dependency on `python-jose`; uses PyJWT with the Ed25519 algorithm, which
delegates to `cryptography` and produces the same compact-JWS format the BFF
verifies with `java.security.Signature("Ed25519")`.
"""
from __future__ import annotations

import time

import jwt
from cryptography.hazmat.primitives.serialization import load_pem_private_key

_HEADER = {"alg": "EdDSA", "typ": "JWT"}

_ISS = "omt-mcp-gateway"
_AUD = "tenant-dashboard-bff"
_LIFETIME = 60  # seconds; BFF cap is 300 s


class Signer:
    """Signs delegated assertions for BFF requests."""

    def __init__(self, private_key_pem: str) -> None:
        """Load the Ed25519 private key from PEM text.

        Raises ValueError if the key is invalid or not Ed25519.
        """
        try:
            self._key = load_pem_private_key(
                private_key_pem.encode() if isinstance(private_key_pem, str) else private_key_pem,
                password=None,
            )
        except Exception as exc:
            raise ValueError(f"Cannot load Ed25519 private key from PEM: {exc}") from exc

    def assertion(self, sub: str, tenant: str, client_id: str) -> str:
        """Return a compact JWS assertion for one BFF request.

        The token is fresh (iat=now, exp=now+60) every call.  Never call this
        ahead of time and cache it — the BFF rejects tokens older than 300 s and
        the assertion must arrive before its own exp.
        """
        now = int(time.time())
        payload = {
            "iss": _ISS,
            "aud": _AUD,
            "provider": "google",
            "sub": sub,
            "tenant": tenant,
            "client_id": client_id,
            "iat": now,
            "exp": now + _LIFETIME,
        }
        # PyJWT encodes the header as {"alg": "EdDSA", "typ": "JWT"} by default
        # for EdDSA keys; explicitly pass headers= to guarantee the typ field.
        return jwt.encode(payload, self._key, algorithm="EdDSA", headers=_HEADER)
