"""FakeIdP for gateway tests.

Implements the ``IdP`` protocol without any real Google network calls.
The ``authorize_url`` stores the nonce so ``exchange`` can return it
(one pending flow at a time is enough for tests).

Pass ``wrong_nonce=True`` to test the nonce guard.
Pass ``email_verified=False`` to test the email guard.
"""
from __future__ import annotations

from ohmytradeagent_mcp.gateway.google import Identity


class FakeIdP:
    """Fake IdP for tests.

    Args:
        sub: The subject (Google user id) to return.
        wrong_nonce: If True, return a nonce that doesn't match the stored one.
        email_verified: Whether to report the email as verified.
    """

    def __init__(
        self,
        sub: str,
        *,
        wrong_nonce: bool = False,
        email_verified: bool = True,
    ) -> None:
        self.sub = sub
        self.wrong_nonce = wrong_nonce
        self.email_verified = email_verified
        self._last_nonce: str = ""

    def authorize_url(self, state: str, nonce: str, redirect_uri: str) -> str:
        """Record the nonce and return a fake authorization URL."""
        self._last_nonce = nonce
        return f"https://fake-google.test/auth?state={state}"

    async def exchange(self, code: str, redirect_uri: str) -> Identity:
        """Return a fake Identity, optionally with a wrong nonce."""
        nonce = "WRONG_NONCE" if self.wrong_nonce else self._last_nonce
        return Identity(
            sub=self.sub,
            email_verified=self.email_verified,
            nonce=nonce,
        )
