"""
Stdio entry point — MCP over standard input/output.

This is what Claude Desktop and Claude Code speak to a locally installed server. See
`http_server.py` for the stateless HTTP transport, and `tools.py` for the tools themselves, which
both entry points share.

The two entry points differ only in construction and transport. That is the practical proof of the
stateless design: there is no per-transport tool code to keep in sync.

Run:
    RFTEST_SESSIONS_DIR=/path/to/sessions python server.py
"""

from __future__ import annotations

import os

from mcp.server.mcpserver import MCPServer

import tools

SESSIONS_DIR = os.environ.get("RFTEST_SESSIONS_DIR", os.path.join(os.getcwd(), "sessions"))

# MCP Python SDK 2.x. FastMCP was renamed to MCPServer in 2.0; pinning to v1 would have been the
# smaller change but would mean shipping against a superseded API.
mcp = MCPServer(
    name="rf-test-app",
    description=tools.SERVER_DESCRIPTION,
    instructions=tools.SERVER_INSTRUCTIONS,
)

tools.register(mcp, SESSIONS_DIR)


if __name__ == "__main__":
    mcp.run(transport="stdio")
