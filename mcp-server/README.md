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

## Structure

```
session_store.py   Parsing, statistics, coverage analysis. No MCP dependency —
                   testable and reusable on its own.
server.py          Tool definitions. A thin wrapper over the above.
```

The split is deliberate: the analysis is the valuable part and should not be reachable only
through a protocol. A report generator or a notebook can import `session_store` directly.

## Transport, and what comes next

Currently stdio, which is what Claude Desktop and Claude Code speak locally.

Nothing in `server.py` holds cross-request state — every tool call reads from disk and returns —
so moving to the stateless `streamable-http` transport with CIMD client registration per the
[MCP 2026-07-28 specification](https://blog.modelcontextprotocol.io/posts/2026-07-28/) touches the
transport only. That is the point of the stateless protocol core, and it is the intended follow-on.

## Compatibility note

Built against **MCP Python SDK 2.x**, where `FastMCP` was renamed to `MCPServer`. Code written for
1.x needs `from mcp.server.mcpserver import MCPServer` in place of
`from mcp.server.fastmcp import FastMCP`.
