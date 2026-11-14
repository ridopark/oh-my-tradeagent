"""OmtAuthorizationServerProvider — the OAuth 2.1 AS provider for the gateway.

Implements the full ``OAuthAuthorizationServerProvider`` protocol from the MCP
SDK, federating to Google for the resource-owner sign-in and storing all state
in ``InMemoryStore`` (or any ``OAuthStore``-compatible store).

Security-critical invariants (each has a mutation test in tests/gateway/):
- Redirect-URI policy enforced at DCR (``register_client``).
- PKCE S256 is enforced by the SDK's ``/token`` handler; the code challenge is
  passed through unchanged so the verifier at exchange time covers the original.
- Authorization codes are single-use: ``load_authorization_code`` detects reuse
  via ``consumed_at`` and revokes the whole grant (RFC 6749 §4.1.2).
- Refresh tokens rotate: ``load_refresh_token`` detects reuse via ``rotated_at``
  and revokes the whole grant.
- Non-members are denied in the Google callback (before the consent page).
- Email must be verified (``email_verified == True``).
- Nonce is verified: the nonce stored in ``PendingAuth`` must equal the nonce
  the IdP returns in the ``Identity``.
- CSRF protection and same-origin check are in ``app.py``'s consent handler.
- ``load_access_token`` re-checks membership on every request.
"""
from __future__ import annotations

import secrets
import time
from typing import Any

from mcp.server.auth.provider import (
    AccessToken,
    AuthorizationCode,
    AuthorizationParams,
    RefreshToken,
    RegistrationError,
    TokenError,
)
from mcp.shared.auth import OAuthClientInformationFull, OAuthToken
from pydantic import AnyUrl

from ohmytradeagent_mcp.gateway.cimd import is_cimd_url
from ohmytradeagent_mcp.gateway.redirects import validate_redirect_uri
from ohmytradeagent_mcp.gateway.store import RefreshTokenRecord

_ACCESS_TOKEN_LIFETIME = 3600       # 1 hour (seconds)
_REFRESH_TOKEN_LIFETIME = 30 * 86400  # 30 days (seconds)
_CLIENT_LIFETIME = 30 * 86400       # 30 days (seconds)


class OmtAuthorizationServerProvider:
    """Full AS provider: DCR, CIMD, Google federation, code/refresh flows.

    Args:
        store: The OAuth store (InMemoryStore for tests; PostgresStore for prod).
        idp: The identity provider (GoogleIdP or FakeIdP).
        resource: The OAuth resource indicator (the /mcp endpoint URL).
        google_callback_url: The URL Google redirects to after sign-in.
        fetch_cimd_fn: Optional injectable CIMD fetcher; defaults to the
            cached real fetcher.
    """

    def __init__(
        self,
        store: Any,
        idp: Any,
        *,
        resource: str,
        google_callback_url: str,
        fetch_cimd_fn=None,
    ) -> None:
        self._store = store
        self._idp = idp
        self._resource = resource
        self._google_callback_url = google_callback_url
        if fetch_cimd_fn is None:
            from ohmytradeagent_mcp.gateway.cimd import make_cimd_fetcher
            fetch_cimd_fn = make_cimd_fetcher()
        self._fetch_cimd = fetch_cimd_fn

    # ------------------------------------------------------------------
    # get_client / register_client
    # ------------------------------------------------------------------

    async def get_client(self, client_id: str) -> OAuthClientInformationFull | None:
        """Return client info, optionally fetching a CIMD document."""
        if is_cimd_url(client_id):
            try:
                doc = await self._fetch_cimd(client_id)
                # Document's client_id must equal the URL (may be validated in
                # fetch_cimd_document already, but re-check here for injected fetchers)
                if doc.get("client_id") != client_id:
                    return None
                return _make_client_from_cimd_doc(doc)
            except Exception:  # noqa: BLE001
                return None

        return await self._store.get_client_record(client_id)

    async def register_client(self, client_info: OAuthClientInformationFull) -> None:
        """Validate redirect URIs and auth method, then save the client.

        Only ``token_endpoint_auth_method: none`` is accepted (public PKCE
        clients).  Confidential clients that request any other method are
        rejected with ``invalid_client_metadata``.
        """
        redirect_uris = client_info.redirect_uris or []
        if not redirect_uris:
            raise RegistrationError(
                error="invalid_client_metadata",
                error_description="redirect_uris is required and must not be empty",
            )
        for uri in redirect_uris:
            validate_redirect_uri(str(uri))

        client_name = client_info.client_name or ""
        if len(client_name) > 80:
            raise RegistrationError(
                error="invalid_client_metadata",
                error_description="client_name must not exceed 80 characters",
            )

        auth_method = client_info.token_endpoint_auth_method or "none"
        if auth_method != "none":
            raise RegistrationError(
                error="invalid_client_metadata",
                error_description=(
                    f"token_endpoint_auth_method must be 'none'; "
                    f"got {auth_method!r}.  This gateway only supports "
                    f"public PKCE clients."
                ),
            )

        # Sweep expired-never-used clients before inserting
        await self._store.sweep_expired_clients()
        await self._store.put_client(client_info)

    # ------------------------------------------------------------------
    # authorize (redirects to Google)
    # ------------------------------------------------------------------

    async def authorize(
        self,
        client: OAuthClientInformationFull,
        params: AuthorizationParams,
    ) -> str:
        """Store pending auth and return the Google authorization URL."""
        from ohmytradeagent_mcp.gateway.store import PendingAuth

        our_state = secrets.token_hex(32)
        our_nonce = secrets.token_hex(32)
        now = time.time()

        pending = PendingAuth(
            state=our_state,
            client_id=client.client_id,
            client_state=params.state,
            redirect_uri=str(params.redirect_uri),
            redirect_uri_provided_explicitly=params.redirect_uri_provided_explicitly,
            code_challenge=params.code_challenge,
            scopes=list(params.scopes or []),
            resource=params.resource,
            nonce=our_nonce,
            created_at=now,
            expires_at=now + 600,  # 10-minute window
            client_name=client.client_name,
        )
        await self._store.put_pending_auth(pending)
        return self._idp.authorize_url(our_state, our_nonce, self._google_callback_url)

    # ------------------------------------------------------------------
    # load_authorization_code / exchange_authorization_code
    # ------------------------------------------------------------------

    async def load_authorization_code(
        self,
        client: OAuthClientInformationFull,
        authorization_code: str,
    ) -> AuthorizationCode | None:
        """Return the AuthorizationCode, detecting and handling reuse."""
        record = await self._store.get_code(authorization_code)
        if record is None:
            return None

        if record.consumed_at is not None:
            # Code reuse: revoke the whole grant (RFC 6749 §4.1.2)
            await self._store.revoke_grant_by_id(record.grant_id)
            return None

        return AuthorizationCode(
            code=authorization_code,
            scopes=record.scopes,
            expires_at=record.expires_at,
            client_id=record.client_id,
            code_challenge=record.code_challenge,
            redirect_uri=AnyUrl(record.redirect_uri),
            redirect_uri_provided_explicitly=record.redirect_uri_provided_explicitly,
            resource=record.resource,
            subject=record.subject,
        )

    async def exchange_authorization_code(
        self,
        client: OAuthClientInformationFull,
        authorization_code: AuthorizationCode,
    ) -> OAuthToken:
        """Mark code consumed, mint access + refresh tokens, return OAuthToken."""
        consumed = await self._store.consume_code(authorization_code.code)
        if not consumed:
            # Concurrent double-use — revoke the grant
            code_rec = await self._store.get_code(authorization_code.code)
            if code_rec:
                await self._store.revoke_grant_by_id(code_rec.grant_id)
            raise TokenError(
                error="invalid_grant",
                error_description="authorization code already consumed",
            )

        # Retrieve grant to get the tenant list
        code_rec = await self._store.get_code(authorization_code.code)
        if code_rec is None:
            raise TokenError(
                error="invalid_grant",
                error_description="authorization code not found",
            )
        grant_id = code_rec.grant_id
        grant = await self._store.get_grant(grant_id)
        tenants = grant.tenants if grant else []

        access_token = secrets.token_hex(32)
        refresh_token_str = secrets.token_hex(32)
        now = int(time.time())

        # Store access token
        await self._store.store_access_token(
            token=access_token,
            sub=authorization_code.subject or "",
            tenants=tenants,
            client_id=authorization_code.client_id,
            expires_at=now + _ACCESS_TOKEN_LIFETIME,
            resource=authorization_code.resource,
            grant_id=grant_id,
        )

        # Store refresh token
        await self._store.put_refresh_token(
            RefreshTokenRecord(
                token=refresh_token_str,
                grant_id=grant_id,
                client_id=authorization_code.client_id,
                scopes=authorization_code.scopes,
                subject=authorization_code.subject or "",
                resource=authorization_code.resource,
                expires_at=now + _REFRESH_TOKEN_LIFETIME,
            )
        )

        return OAuthToken(
            access_token=access_token,
            token_type="Bearer",
            expires_in=_ACCESS_TOKEN_LIFETIME,
            refresh_token=refresh_token_str,
        )

    # ------------------------------------------------------------------
    # load_refresh_token / exchange_refresh_token
    # ------------------------------------------------------------------

    async def load_refresh_token(
        self,
        client: OAuthClientInformationFull,
        refresh_token: str,
    ) -> RefreshToken | None:
        """Return RefreshToken, detecting reuse and revoking the grant."""
        record = await self._store.get_refresh_token(refresh_token)
        if record is None:
            return None

        if record.rotated_at is not None:
            # Reuse detected: revoke the whole grant
            await self._store.revoke_grant_by_id(record.grant_id)
            return None

        # Also check whether the grant was externally revoked (e.g. by another token's reuse)
        grant = await self._store.get_grant(record.grant_id)
        if grant is None or grant.revoked_at is not None:
            return None

        return RefreshToken(
            token=refresh_token,
            client_id=record.client_id,
            scopes=record.scopes,
            expires_at=record.expires_at,
            resource=record.resource,
            subject=record.subject,
        )

    async def exchange_refresh_token(
        self,
        client: OAuthClientInformationFull,
        refresh_token: RefreshToken,
        scopes: list[str],
    ) -> OAuthToken:
        """Rotate refresh token: mark old as rotated, mint new pair."""
        # Retrieve existing record for grant_id + subject
        old_record = await self._store.get_refresh_token(refresh_token.token)
        if old_record is None:
            raise TokenError(error="invalid_grant", error_description="refresh token not found")

        grant_id = old_record.grant_id
        grant = await self._store.get_grant(grant_id)
        tenants = grant.tenants if grant else []

        # Generate the new refresh token FIRST so its hash can be stored in
        # replaced_by_hash by the atomic rotation (audit correlation).
        new_access = secrets.token_hex(32)
        new_refresh = secrets.token_hex(32)
        now = int(time.time())

        # Atomic rotation: returns False on concurrent double-refresh reuse.
        # Pass new_refresh so replaced_by_hash stores the successor's hash.
        rotated = await self._store.rotate_refresh_token(refresh_token.token, new_refresh)
        if not rotated:
            await self._store.revoke_grant_by_id(old_record.grant_id)
            raise TokenError(
                error="invalid_grant",
                error_description="refresh token reuse detected",
            )

        await self._store.store_access_token(
            token=new_access,
            sub=old_record.subject,
            tenants=tenants,
            client_id=old_record.client_id,
            expires_at=now + _ACCESS_TOKEN_LIFETIME,
            resource=old_record.resource,
            grant_id=grant_id,
        )

        await self._store.put_refresh_token(
            RefreshTokenRecord(
                token=new_refresh,
                grant_id=grant_id,
                client_id=old_record.client_id,
                scopes=scopes,
                subject=old_record.subject,
                resource=old_record.resource,
                expires_at=now + _REFRESH_TOKEN_LIFETIME,
            )
        )

        return OAuthToken(
            access_token=new_access,
            token_type="Bearer",
            expires_in=_ACCESS_TOKEN_LIFETIME,
            refresh_token=new_refresh,
        )

    # ------------------------------------------------------------------
    # load_access_token (token verification for /mcp)
    # ------------------------------------------------------------------

    async def load_access_token(self, token: str) -> AccessToken | None:
        """Verify bearer token with expiry, revocation, resource, and membership checks."""
        rec = await self._store.get_access_token(token)
        if rec is None:
            return None

        if rec.expires_at < int(time.time()):
            return None

        if rec.revoked_at is not None:
            return None

        # Membership re-check on every request
        current = set(await self._store.get_membership(rec.subject))
        valid_tenants = [t for t in rec.tenants if t in current]
        if not valid_tenants:
            return None

        await self._store.bump_last_used(rec.grant_id)

        return AccessToken(
            token=token,
            client_id=rec.client_id,
            scopes=["read"],
            expires_at=rec.expires_at,
            resource=rec.resource,
            subject=rec.subject,
            claims={"tenants": valid_tenants, "grant_id": rec.grant_id},
        )

    # ------------------------------------------------------------------
    # revoke_token
    # ------------------------------------------------------------------

    async def revoke_token(self, token: AccessToken | RefreshToken) -> None:
        """Revoke the whole grant associated with *token*."""
        if isinstance(token, AccessToken):
            grant_id = (token.claims or {}).get("grant_id")
            if grant_id:
                await self._store.revoke_grant_by_id(grant_id)
        elif isinstance(token, RefreshToken):
            record = await self._store.get_refresh_token(token.token)
            if record:
                await self._store.revoke_grant_by_id(record.grant_id)


# ------------------------------------------------------------------
# Helpers
# ------------------------------------------------------------------


def _make_client_from_cimd_doc(doc: dict) -> OAuthClientInformationFull:
    """Build an OAuthClientInformationFull from a validated CIMD document."""
    from ohmytradeagent_mcp.gateway.redirects import validate_redirect_uri

    redirect_uris = doc.get("redirect_uris", [])
    for uri in redirect_uris:
        validate_redirect_uri(str(uri))

    return OAuthClientInformationFull(
        client_id=doc["client_id"],
        redirect_uris=[AnyUrl(u) for u in redirect_uris],
        client_name=doc.get("client_name"),
        grant_types=doc.get("grant_types", ["authorization_code"]),
        response_types=doc.get("response_types", ["code"]),
        scope=doc.get("scope", "read"),
        token_endpoint_auth_method="none",
        # CIMD clients have no server-issued secret
        client_secret=None,
    )


