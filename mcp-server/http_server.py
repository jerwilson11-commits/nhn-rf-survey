"""
Stateless HTTP entry point — MCP over `streamable-http`.

Phase 8b. Same five tools as the stdio server, registered from the same `tools.register()`, because
a tool that behaves differently over HTTP than over stdio is a bug that surfaces only in whichever
transport gets tested less.

## Why stateless

The MCP 2026-07-28 specification removed protocol-level sessions and handshakes from the core so a
server can scale horizontally: any instance can serve any request, so you can put N replicas behind
a load balancer without sticky sessions or shared session storage.

That property has to be earned in the application, not just switched on. It holds here because no
tool retains anything between calls — each one resolves a filename, reads it, computes, returns.
The `stateless_http=True` flag below is an assertion about this code, and it would be false the
moment a tool cached a parsed session in module scope.

## Security posture

Binds loopback by default. Binding a wider interface without authentication configured is refused
rather than warned about — an unauthenticated MCP server on 0.0.0.0 exposes every session file it
can read to anyone who can reach the port, and a warning in a log nobody reads is not a control.

See `auth.py` for the Resource Server / Authorization Server split, and for why CIMD is an AS
concern rather than something implemented here.

Run:
    RFTEST_SESSIONS_DIR=./sessions \
    RFTEST_OAUTH_ISSUER=https://your-tenant.example.com/ \
    RFTEST_OAUTH_AUDIENCE=https://rf.example.com/mcp \
    RFTEST_OAUTH_JWKS_URL=https://your-tenant.example.com/.well-known/jwks.json \
    RFTEST_OAUTH_SCOPES="rf:read" \
    python http_server.py
"""

from __future__ import annotations

import logging
import os
import sys

from mcp.server.auth.settings import AuthSettings
from mcp.server.mcpserver import MCPServer

import auth as auth_mod
import tools

logging.basicConfig(level=os.environ.get("RFTEST_LOG_LEVEL", "INFO"))
log = logging.getLogger("rf-test-app.http")

SESSIONS_DIR = os.environ.get("RFTEST_SESSIONS_DIR", os.path.join(os.getcwd(), "sessions"))
HOST = os.environ.get("RFTEST_HTTP_HOST", "127.0.0.1")
PORT = int(os.environ.get("RFTEST_HTTP_PORT", "8000"))
PATH = os.environ.get("RFTEST_HTTP_PATH", "/mcp")

LOOPBACK = {"127.0.0.1", "::1", "localhost"}


def build_server() -> MCPServer:
    verifier, issuer, resource = auth_mod.verifier_from_env()

    if verifier is None and HOST not in LOOPBACK:
        sys.exit(
            f"Refusing to bind {HOST} with no authentication configured.\n"
            "An unauthenticated MCP server exposes every session file it can read to anyone who\n"
            "can reach the port. Either bind 127.0.0.1, or configure RFTEST_OAUTH_ISSUER,\n"
            "RFTEST_OAUTH_AUDIENCE and RFTEST_OAUTH_JWKS_URL."
        )

    kwargs: dict = {
        "name": "rf-test-app",
        "description": tools.SERVER_DESCRIPTION,
        "instructions": tools.SERVER_INSTRUCTIONS,
    }

    if verifier is not None:
        kwargs["token_verifier"] = verifier
        if issuer:
            # Publishes OAuth Protected Resource Metadata (RFC 9728) so a client that gets a 401
            # can discover which Authorization Server to talk to, rather than having it hardcoded.
            # This is the discovery half of the flow whose registration half is CIMD.
            kwargs["auth"] = AuthSettings(
                issuer_url=issuer,
                resource_server_url=resource,
                required_scopes=[s for s in os.environ.get("RFTEST_OAUTH_SCOPES", "").split() if s]
                or None,
            )
            log.info("Resource Server mode: issuer=%s resource=%s", issuer, resource)
        else:
            # The SDK refuses token_verifier without auth settings:
            #   "Cannot specify auth_server_provider or token_verifier without auth settings"
            # which is a reasonable constraint — a server that validates tokens but publishes no
            # Protected Resource Metadata gives a client no way to discover where to get one.
            #
            # Dev mode has no real Authorization Server, so the metadata points at this server's
            # own URL. That is a deliberate fiction to exercise the enforcement path locally, and
            # it is why DevTokenVerifier demands two explicit environment switches before it will
            # construct at all.
            own_url = f"http://{HOST}:{PORT}"
            kwargs["auth"] = AuthSettings(
                issuer_url=own_url,
                resource_server_url=resource or own_url,
            )
            log.warning(
                "DEV AUTH: metadata advertises %s as issuer, which is a local testing fiction",
                own_url,
            )
    else:
        log.warning("no authentication configured; loopback only")

    mcp = MCPServer(**kwargs)
    tools.register(mcp, SESSIONS_DIR)
    return mcp


def main() -> None:
    mcp = build_server()
    log.info("sessions directory: %s", SESSIONS_DIR)
    log.info("listening on http://%s:%d%s (stateless)", HOST, PORT, PATH)
    mcp.run(
        transport="streamable-http",
        host=HOST,
        port=PORT,
        streamable_http_path=PATH,
        # The assertion this whole module is about: no instance affinity required.
        stateless_http=True,
        # Plain JSON responses rather than SSE streams. These tools return a single result and
        # never stream partial output, so a stream would be ceremony with no payload.
        json_response=True,
    )


if __name__ == "__main__":
    main()
