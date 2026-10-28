"""WhatsApp Business MCP - connect a WhatsApp Business number to any AI client.

A standard Model Context Protocol server for the WhatsApp Business Cloud API.
Pure Python standard library: no `pip install`, no build step, no daemon.

    python3 -m wamcp setup        # one-time wizard, writes ~/.wamcp/config.json
    python3 -m wamcp doctor       # check the setup end to end
    python3 -m wamcp serve        # MCP server on stdio (what an AI client runs)

Layout:
    config.py    credentials + options (env or ~/.wamcp/config.json)
    phones.py    E.164 normalisation
    graph.py     Cloud API client (urllib) with actionable error mapping
    messages.py  request builders for every WhatsApp message type
    store.py     SQLite inbox, contacts, opt-outs, audit log
    policy.py    allow-list, opt-out, send cap, 24-hour window, dry run
    tools.py     the MCP tool surface
    mcp.py       JSON-RPC 2.0 / MCP over stdio and Streamable HTTP
    webhook.py   inbound webhook receiver (fills the inbox)
    install.py   writes client config for Claude Code, Claude Desktop, Cursor...
    cli.py       command line
"""

__version__ = "1.0.0"

# MCP revisions this server speaks. The client's requested revision is echoed
# back when we know it, otherwise we answer with PROTOCOL_VERSION and let the
# client decide. Newer revisions keep the stdio framing used here.
PROTOCOL_VERSION = "2025-06-18"
KNOWN_PROTOCOL_VERSIONS = ("2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25", "2026-07-28")
KNOWN_PROTOCOL_VERSION_SET = frozenset(KNOWN_PROTOCOL_VERSIONS)

SERVER_NAME = "whatsapp-business"
