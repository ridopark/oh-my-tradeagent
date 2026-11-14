"""Test 1 — AS metadata advertisement (Seam H).

Verifies that the AS metadata document advertises exactly what clients need:
- code_challenge_methods_supported == ["S256"]
- registration_endpoint present
- revocation_endpoint present
- client_id_metadata_document_supported is True (our patch)
- grant_types_supported ⊇ {authorization_code, refresh_token}
- issuer == public URL
"""
from __future__ import annotations

import httpx
import pytest

from tests.gateway.fixtures import PUBLIC_URL, make_app


@pytest.mark.asyncio
async def test_asMetadata_advertisesWhatClientsNeed():
    app = make_app()
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app),
        base_url=PUBLIC_URL,
    ) as client:
        resp = await client.get("/.well-known/oauth-authorization-server")

    assert resp.status_code == 200
    data = resp.json()

    assert data["code_challenge_methods_supported"] == ["S256"], data
    assert "registration_endpoint" in data, data
    assert "revocation_endpoint" in data, data
    # Our patch
    assert data["client_id_metadata_document_supported"] is True, data
    assert "authorization_code" in data["grant_types_supported"], data
    assert "refresh_token" in data["grant_types_supported"], data
    assert data["issuer"] == PUBLIC_URL or data["issuer"] == PUBLIC_URL + "/", data


@pytest.mark.asyncio
async def test_asMetadata_cimdFlag_isTrue():
    """Mutation guard: dropping the metadata override must make this test fail."""
    app = make_app()
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app),
        base_url=PUBLIC_URL,
    ) as client:
        resp = await client.get("/.well-known/oauth-authorization-server")

    data = resp.json()
    assert data.get("client_id_metadata_document_supported") is True, (
        "Expected client_id_metadata_document_supported=True in AS metadata; "
        f"got: {data.get('client_id_metadata_document_supported')!r}"
    )


@pytest.mark.asyncio
async def test_asMetadata_tokenEndpointAuthMethodsSupported_isNoneOnly():
    """D5: AS metadata must advertise token_endpoint_auth_methods_supported: ['none'].

    RED: SDK defaults to ['client_secret_post', 'client_secret_basic'].
    GREEN: _patch_as_metadata sets the field to ['none'].
    """
    app = make_app()
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url=PUBLIC_URL
    ) as client:
        resp = await client.get("/.well-known/oauth-authorization-server")
    data = resp.json()
    assert data.get("token_endpoint_auth_methods_supported") == ["none"], (
        f"Expected ['none'], got {data.get('token_endpoint_auth_methods_supported')!r}"
    )


@pytest.mark.asyncio
async def test_asMetadata_revocationEndpointAuthMethodsSupported_isNoneOnly():
    """D5: revocation_endpoint_auth_methods_supported must also be ['none'].

    RED: SDK defaults to ['client_secret_post', 'client_secret_basic'].
    GREEN: _patch_as_metadata sets both fields to ['none'].
    """
    app = make_app()
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url=PUBLIC_URL
    ) as client:
        resp = await client.get("/.well-known/oauth-authorization-server")
    data = resp.json()
    assert data.get("revocation_endpoint_auth_methods_supported") == ["none"], (
        f"Expected ['none'], got {data.get('revocation_endpoint_auth_methods_supported')!r}"
    )


@pytest.mark.asyncio
async def test_dcr_omittedTokenEndpointAuthMethod_treatedAsNone():
    """D5: DCR body omitting token_endpoint_auth_method is accepted as 'none'.

    RED: SDK may default to 'client_secret_post'; provider.register_client rejects it.
    GREEN: provider.register_client treats absent method as 'none' (or "or 'none'" logic).
    """
    app = make_app()
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url=PUBLIC_URL
    ) as client:
        resp = await client.post(
            "/register",
            json={
                "client_name": "Implicit None App",
                "redirect_uris": ["https://example.com/callback"],
                "grant_types": ["authorization_code"],
                "response_types": ["code"],
                # token_endpoint_auth_method intentionally omitted
            },
        )
    assert resp.status_code == 201, (
        f"Expected 201 for omitted token_endpoint_auth_method, got {resp.status_code}: {resp.text}"
    )
    data = resp.json()
    assert data.get("token_endpoint_auth_method") in (None, "none"), (
        f"Expected auth method 'none' or None, got: {data.get('token_endpoint_auth_method')!r}"
    )
