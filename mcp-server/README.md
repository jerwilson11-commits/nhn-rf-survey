# MCP server — RF session analysis

A [Model Context Protocol](https://modelcontextprotocol.io) server that exposes recorded
measurement sessions as typed tools, so an agent can answer acceptance questions directly from the
data:

> *"Which parts of the Tuesday walk failed the −75 dBm threshold, and where were they?"*
> *"Compare the post-tuning walk against the baseline."*
> *"Show me every sample on channel 161 below −70."*

## Why this instead of a report screen

The app's roadmap originally called for a fixed PDF/XLSX report template. A template answers the
questions anticipated at design time; a client asks the ones they actually care about. Exposing the
session corpus as tools moves the question-answering to where the questions are.

It reads the same CSV files the handset writes, through the same schema — so the server and the app
cannot disagree about what a session contains. The RSSI bucket thresholds are mirrored from
`RssiBucket` in the Android source rather than reinvented, which is the same discipline that keeps
the handset plot, the KML export and the QGIS styles showing one story.

## Tools

| Tool | Purpose |
|---|---|
| `list_sessions` | What data exists — names, start times, sample counts |
| `get_session_summary` | RSSI percentiles, GPS accuracy, SSIDs/BSSIDs observed, bounds, velocity coverage |
| `analyze_coverage` | Threshold compliance, bucket distribution, and contiguous coverage holes with position and extent |
| `query_samples` | Filter by RSSI range, band, channel, bounding box, or throughput presence |
| `compare_sessions` | Before/after deltas with an explicit comparability assessment |

### Analysis decisions worth knowing

**A missing value is never a zero.** Every aggregate reports how many samples it was computed from
and how many were missing. A median over 3 samples is not the same claim as one over 400, and a
consumer that cannot see the difference will treat them identically.

**Percentiles, not just means.** A mean RSSI of −62 dBm hides the 5% of a venue sitting at −85.
Coverage arguments are won and lost in the tail, so `min`, `p10`, `median`, `p90` and `max` are all
returned.

**Coverage holes, not just a compliance percentage.** 8% failing scattered evenly across a venue is
a different problem from 8% concentrated in one stairwell, and only the second tells an engineer
where to walk back to. Failing runs are returned individually with their worst reading, position,
duration and physical extent.

**`compare_sessions` reports the change; it does not declare an improvement.** Two walks are rarely
the same route at the same pace, so a median shift can reflect a different path as easily as a
different network. Sample counts, durations and an explicit caveat are returned alongside the
deltas. This is not hypothetical — during development, comparing a driveway walk against a mostly
stationary indoor session produced a +24 dB median delta that was entirely route artefact.

**Errors are returned as data.** A missing session yields the error plus the list of sessions that
do exist. An agent can act on that; it cannot act on a stack trace.

## Running it

```bash
pip install -r requirements.txt
```

Pull sessions off the handset into a directory:

```bash
adb pull /sdcard/Android/data/com.nhnengineering.rftest/files/sessions/ ./sessions
```

Then point the server at it:

```bash
RFTEST_SESSIONS_DIR=./sessions python server.py
```

### Connecting to Claude Desktop

Add to `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "rf-test-app": {
      "command": "python",
      "args": ["/absolute/path/to/mcp-server/server.py"],
      "env": { "RFTEST_SESSIONS_DIR": "/absolute/path/to/sessions" }
    }
  }
}
```

### Connecting to Claude Code

```bash
claude mcp add rf-test-app -e RFTEST_SESSIONS_DIR=/abs/path/to/sessions -- python /abs/path/to/mcp-server/server.py
```

## Transports

Two entry points, one set of tools. `tools.register()` is shared, because a tool that behaves
differently over HTTP than over stdio is a bug that surfaces only in whichever transport gets
tested less.

| Entry point | Transport | Use |
|---|---|---|
| `server.py` | stdio | Claude Desktop, Claude Code, local use |
| `http_server.py` | `streamable-http`, stateless | Hosted deployment |

### Statelessness is a property of the code, not a flag

The MCP 2026-07-28 specification removed protocol-level sessions from the core so a server can
scale horizontally — any replica serves any request, no sticky sessions, no shared session store.

`stateless_http=True` only *asserts* that. It holds here because no tool retains anything between
calls: each resolves a filename, reads it, computes, returns. It would become false the moment a
tool cached a parsed session in module scope.

Verified rather than assumed — a cold request carrying no prior `initialize` and no session header
succeeds on its own, and the server issues no `Mcp-Session-Id`:

```
initialize                          -> 200, Mcp-Session-Id: (none)
tools/call, no init, no session id  -> 200, 4 sessions
```

## Authorization

This server is an OAuth 2.1 **Resource Server**. It validates bearer tokens and publishes metadata
telling clients where to get one. It does not issue tokens.

### On CIMD, which is easy to garble

**Client ID Metadata Documents are an Authorization Server feature, not a Resource Server one.**
Under CIMD a client hosts a JSON metadata document at a stable HTTPS URL and uses that URL *as* its
`client_id`, replacing the Dynamic Client Registration round-trip that MCP 2026-07-28 deprecates.
The party that fetches and understands that URL is the AS, during authorization. A Resource Server
never sees a registration request — it sees an access token whose `client_id` claim happens to be
a URL rather than an opaque string.

So the accurate claims are:

- Resource Server validating tokens from a CIMD-capable Authorization Server.
- Handles URL-shaped `client_id` values, which is what CIMD produces.
- **Deliberately does not implement an Authorization Server.**

That last one is a decision, not a gap. Rolling your own OAuth AS is a well-known way to introduce
subtle, exploitable bugs; real deployments delegate to Auth0, Okta, Entra ID or Keycloak. The SDK
exposes `auth_server_provider` for anyone who wants to build one. This does not use it.

### What is verified

Signature against the AS's JWKS, plus issuer, audience, expiry and required scopes. Algorithms are
pinned — accepting whatever the token header claims permits `"alg": "none"` and the RS256→HS256
confusion attack. Audience is checked against this server's own resource identifier (RFC 8707
resource indicators), so a token minted for a different resource cannot be replayed here.

Verified end to end:

```
no token    -> 401  WWW-Authenticate: Bearer error="invalid_token",
                    resource_metadata=".../.well-known/oauth-protected-resource"
wrong token -> 401
valid token -> 200
PRM endpoint (RFC 9728) -> 200 {"resource":..., "authorization_servers":[...]}
```

That 401 is the start of the discovery chain: the client reads the metadata pointer, fetches the
Protected Resource Metadata, learns which AS to authenticate against, registers with it — by CIMD —
and returns with a token.

### Configuration

```bash
RFTEST_SESSIONS_DIR=./sessions
RFTEST_OAUTH_ISSUER=https://your-tenant.example.com/
RFTEST_OAUTH_AUDIENCE=https://rf.example.com/mcp
RFTEST_OAUTH_JWKS_URL=https://your-tenant.example.com/.well-known/jwks.json
RFTEST_OAUTH_SCOPES="rf:read"
RFTEST_HTTP_HOST=127.0.0.1
RFTEST_HTTP_PORT=8000
python http_server.py
```

### Security posture

- **Binds loopback by default.** Binding a wider interface with no authentication configured is
  *refused*, not warned about. An unauthenticated MCP server on `0.0.0.0` exposes every session
  file it can read to anyone who can reach the port, and a warning in a log nobody reads is not a
  control.
- **The dev verifier needs two switches.** `DevTokenVerifier` is a static shared secret with no
  expiry, revocation or audience binding — it exists so the enforcement path can be tested without
  standing up an AS. It refuses to construct unless both `RFTEST_DEV_TOKEN` and
  `RFTEST_ALLOW_DEV_AUTH=1` are set, because "it defaulted to the insecure mode in production" is a
  common enough failure to design against.
- Token rejection is logged coarsely and returns no detail to the caller. Explaining *why* a token
  failed helps a legitimate developer slightly and an attacker considerably.

## Structure

```
session_store.py   Parsing, statistics, coverage analysis. No MCP dependency.
tools.py           The five tools. Registered onto either transport.
auth.py            Resource Server token verification.
server.py          stdio entry point.
http_server.py     stateless streamable-http entry point.
```

`session_store.py` deliberately has no MCP dependency: the analysis is the valuable part and should
not be reachable only through a protocol. A report generator or a notebook can import it directly.

## Compatibility notes

Built against **MCP Python SDK 2.x**, where `FastMCP` was renamed to `MCPServer`. Code written for
1.x needs `from mcp.server.mcpserver import MCPServer` in place of
`from mcp.server.fastmcp import FastMCP`.

The SDK also rejects `token_verifier` without `auth` settings — a sensible constraint, since a
server that validates tokens but publishes no Protected Resource Metadata gives a client no way to
discover where to obtain one.
