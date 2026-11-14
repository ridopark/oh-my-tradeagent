"""Google OpenID Connect IdP integration.

IdP protocol::

    class IdP(Protocol):
        def authorize_url(self, state: str, nonce: str, redirect_uri: str) -> str: ...
        async def exchange(self, code: str, redirect_uri: str) -> Identity: ...

``Identity`` carries ``sub``, ``email_verified``, and the ``nonce`` from the
ID token so the provider can verify it was not tampered.

``GoogleIdP`` has injectable ``jwks_uri``, ``token_uri``, and ``_transport``
parameters so tests can substitute a local RSA key pair for the real JWKS and a
fake token endpoint, without any real Google network calls.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol
from urllib.parse import urlencode


@dataclass(frozen=True)
class Identity:
    """Verified identity returned by the IdP."""

    sub: str
    email_verified: bool
    nonce: str  # nonce claim from the ID token


class IdP(Protocol):
    """Injectable IdP for the Google OIDC federation step."""

    def authorize_url(self, state: str, nonce: str, redirect_uri: str) -> str:
        """Return the authorization URL to redirect the user to."""
        ...

    async def exchange(self, code: str, redirect_uri: str) -> Identity:
        """Exchange an authorization code for a verified Identity."""
        ...


# ------------------------------------------------------------------
# Real GoogleIdP
# ------------------------------------------------------------------

_GOOGLE_AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
_GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token"
_GOOGLE_JWKS_URI = "https://www.googleapis.com/oauth2/v3/certs"
_GOOGLE_ISSUER = "https://accounts.google.com"
# Legacy issuer form used before ~2015; still accepted for compatibility.
_GOOGLE_LEGACY_ISSUER = "accounts.google.com"

_VALID_GOOGLE_ISSUERS = frozenset({_GOOGLE_ISSUER, _GOOGLE_LEGACY_ISSUER})


class GoogleIdP:
    """Real Google IdP using JWKS-backed RS256 ID-token verification.

    Inject ``jwks_uri`` / ``token_uri`` / ``_transport`` for testing with a
    locally generated RSA key pair instead of real Google infrastructure.
    """

    def __init__(
        self,
        client_id: str,
        client_secret: str,
        *,
        jwks_uri: str | None = None,
        token_uri: str | None = None,
        _transport=None,  # httpx transport override for tests
    ) -> None:
        self._client_id = client_id
        self._client_secret = client_secret
        self._jwks_uri = jwks_uri or _GOOGLE_JWKS_URI
        self._token_uri = token_uri or _GOOGLE_TOKEN_URL
        self._transport = _transport
        # PyJWKClient is created lazily on first exchange() so that test patches
        # applied to jwt.PyJWKClient are active when the client is instantiated.
        self._jwks_client = None

    def authorize_url(self, state: str, nonce: str, redirect_uri: str) -> str:
        params = {
            "client_id": self._client_id,
            "redirect_uri": redirect_uri,
            "response_type": "code",
            "scope": "openid email",
            "state": state,
            "nonce": nonce,
            "prompt": "select_account",
        }
        return f"{_GOOGLE_AUTH_URL}?{urlencode(params)}"

    async def exchange(self, code: str, redirect_uri: str) -> Identity:
        import httpx
        import jwt as pyjwt
        from jwt import InvalidIssuerError, PyJWKClient

        async with httpx.AsyncClient(transport=self._transport) as client:
            resp = await client.post(
                self._token_uri,
                data={
                    "code": code,
                    "client_id": self._client_id,
                    "client_secret": self._client_secret,
                    "redirect_uri": redirect_uri,
                    "grant_type": "authorization_code",
                },
                timeout=10.0,
            )
            resp.raise_for_status()
            tokens = resp.json()

        id_token: str = tokens["id_token"]

        # Reuse cached JWKS client across calls to avoid re-fetching the key set.
        if self._jwks_client is None:
            self._jwks_client = PyJWKClient(self._jwks_uri)
        signing_key = self._jwks_client.get_signing_key_from_jwt(id_token)

        # Decode without built-in issuer check; we accept both Google issuer forms.
        claims = pyjwt.decode(
            id_token,
            signing_key.key,
            algorithms=["RS256"],
            audience=self._client_id,
            options={"verify_exp": True, "verify_iss": False},
        )
        iss = claims.get("iss", "")
        if iss not in _VALID_GOOGLE_ISSUERS:
            raise InvalidIssuerError(
                f"Invalid issuer {iss!r}; expected one of {sorted(_VALID_GOOGLE_ISSUERS)}"
            )

        return Identity(
            sub=claims["sub"],
            email_verified=bool(claims.get("email_verified", False)),
            nonce=claims.get("nonce", ""),
        )
