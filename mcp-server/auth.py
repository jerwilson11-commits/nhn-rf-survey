"""
OAuth 2.1 Resource Server token verification.

## Which role this server plays, and why it matters

MCP's authorization model has three parties: the **client**, an **Authorization Server** (AS) that
issues tokens, and the **Resource Server** (RS) that accepts them. This MCP server is a Resource
Server. It validates bearer tokens and publishes metadata telling clients which AS to go to. It
does not issue tokens.

That distinction is worth stating plainly because it is easy to garble:

**CIMD — Client ID Metadata Documents — is an Authorization Server feature, not a Resource Server
one.** Under CIMD a client hosts a small JSON metadata document at a stable HTTPS URL and uses that
URL *as* its `client_id`, replacing the Dynamic Client Registration round-trip that the MCP
2026-07-28 specification deprecates. The party that has to understand and fetch that URL is the AS,
during authorization. A Resource Server never sees a registration request at all — it sees an
access token, and the `client_id` inside it happens to be a URL rather than an opaque string.

So "this server implements CIMD" would be the wrong claim. The correct ones are:

- It is a Resource Server that validates tokens from a CIMD-capable AS.
- It handles `client_id` values that are URLs, because that is what CIMD produces.
- It deliberately does **not** implement an Authorization Server.

That last point is a design decision, not a gap. Rolling your own OAuth AS is a well-known way to
introduce subtle, exploitable bugs, and every serious deployment delegates to a real one — Auth0,
Okta, Entra ID, Keycloak. The MCP SDK exposes `auth_server_provider` for anyone who wants to build
one; this does not use it.

## Verification performed

Signature against the AS's JWKS, plus issuer, audience, expiry and required scopes. Audience is
checked against this server's own resource identifier so a token minted for a different resource
cannot be replayed here — that is RFC 8707 resource indicators, and skipping it is how a token for
one service ends up granting access to another.
"""

from __future__ import annotations

import logging
import os
import time
from typing import Any, Optional

import jwt
from jwt import PyJWKClient
from mcp.server.auth.provider import AccessToken, TokenVerifier

log = logging.getLogger(__name__)


class JwtTokenVerifier(TokenVerifier):
    """Validates JWT access tokens issued by an external Authorization Server."""

    def __init__(
        self,
        jwks_url: str,
        issuer: str,
        audience: str,
        required_scopes: Optional[list[str]] = None,
        algorithms: Optional[list[str]] = None,
    ) -> None:
        self.issuer = issuer
        self.audience = audience
        self.required_scopes = set(required_scopes or [])
        # Restricting algorithms is not optional. Accepting whatever the token's header claims
        # permits the classic "alg": "none" and RS256->HS256 confusion attacks, where an attacker
        # signs a token using the public key as an HMAC secret.
        self.algorithms = algorithms or ["RS256", "ES256"]
        self._jwks = PyJWKClient(jwks_url, cache_keys=True)

    async def verify_token(self, token: str) -> Optional[AccessToken]:
        try:
            signing_key = self._jwks.get_signing_key_from_jwt(token)
            claims: dict[str, Any] = jwt.decode(
                token,
                signing_key.key,
                algorithms=self.algorithms,
                issuer=self.issuer,
                audience=self.audience,
                options={"require": ["exp", "iss", "aud"]},
            )
        except jwt.InvalidTokenError as exc:
            # Deliberately coarse in the log and silent to the caller. Telling a caller *why* a
            # token failed helps a legitimate developer slightly and an attacker considerably.
            log.warning("token rejected: %s", type(exc).__name__)
            return None
        except Exception as exc:  # JWKS fetch failure, network, malformed key
            log.warning("token verification unavailable: %s", type(exc).__name__)
            return None

        granted = self._scopes(claims)
        if self.required_scopes and not self.required_scopes.issubset(granted):
            log.warning("token rejected: missing required scopes")
            return None

        return AccessToken(
            token=token,
            # Under CIMD this is a URL rather than an opaque identifier. Nothing here needs to
            # care, which is the point — the RS consumes whatever the AS put in the claim.
            client_id=str(claims.get("client_id") or claims.get("azp") or claims.get("sub") or ""),
            scopes=sorted(granted),
            expires_at=int(claims["exp"]) if "exp" in claims else None,
            resource=self.audience,
            subject=claims.get("sub"),
            claims=claims,
        )

    @staticmethod
    def _scopes(claims: dict[str, Any]) -> set[str]:
        """Scopes appear as a space-delimited `scope` string or a `scp` array depending on the AS."""
        raw = claims.get("scope") or claims.get("scp") or []
        if isinstance(raw, str):
            return set(raw.split())
        if isinstance(raw, list):
            return {str(x) for x in raw}
        return set()


class DevTokenVerifier(TokenVerifier):
    """
    Shared-secret verifier for local development only.

    Exists so the HTTP transport can be exercised without standing up an Authorization Server. It
    is **not** an auth mechanism: a single static bearer token has no expiry, no revocation, no
    audience binding and no per-client identity.

    Guarded so it cannot be enabled by accident — it requires RFTEST_DEV_TOKEN to be set explicitly
    and refuses to run unless RFTEST_ALLOW_DEV_AUTH=1 as well. Two switches rather than one,
    because "it defaulted to the insecure mode in production" is a genuinely common failure.
    """

    def __init__(self, token: str) -> None:
        if os.environ.get("RFTEST_ALLOW_DEV_AUTH") != "1":
            raise RuntimeError(
                "DevTokenVerifier requires RFTEST_ALLOW_DEV_AUTH=1. It is for local testing only "
                "and must never be used for a deployment."
            )
        if not token or len(token) < 16:
            raise ValueError("RFTEST_DEV_TOKEN must be at least 16 characters")
        self._token = token
        log.warning("DEV AUTH ENABLED — static bearer token, not suitable for any deployment")

    async def verify_token(self, token: str) -> Optional[AccessToken]:
        # Constant-time compare. The timing signal on a static secret is small but free to remove.
        import hmac

        if not hmac.compare_digest(token, self._token):
            return None
        return AccessToken(
            token=token,
            client_id="dev-local",
            scopes=["rf:read"],
            expires_at=int(time.time()) + 3600,
            subject="dev",
        )


def verifier_from_env() -> tuple[Optional[TokenVerifier], Optional[str], Optional[str]]:
    """
    Build a verifier from environment configuration.

    Returns (verifier, issuer_url, resource_server_url). All three are None when no auth is
    configured, which is a valid choice for a loopback-bound development server and an invalid one
    for anything reachable.
    """
    issuer = os.environ.get("RFTEST_OAUTH_ISSUER")
    audience = os.environ.get("RFTEST_OAUTH_AUDIENCE")
    jwks = os.environ.get("RFTEST_OAUTH_JWKS_URL")
    scopes = [s for s in os.environ.get("RFTEST_OAUTH_SCOPES", "").split() if s]

    if issuer and audience and jwks:
        return JwtTokenVerifier(jwks, issuer, audience, scopes), issuer, audience

    dev = os.environ.get("RFTEST_DEV_TOKEN")
    if dev:
        return DevTokenVerifier(dev), None, audience

    return None, None, None
