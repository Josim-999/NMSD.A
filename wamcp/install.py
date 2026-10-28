"""Write this server into an AI client's MCP configuration.

Every MCP client is configured the same way - a JSON object naming a command
to launch - so one generated block works for all of them; only the file it
goes in differs. `python3 -m wamcp install` merges the block into the right
file, leaving any other servers already configured untouched.

Credentials stay in ~/.wamcp/config.json (mode 0600) rather than in the client
config, which is often synced or committed. Pass with_secrets=True only if a
client cannot see that file, for instance when it runs in another container.
"""
import json
import os
import shlex
import sys

from . import SERVER_NAME, config as config_mod

# Where each client keeps its MCP servers. `None` means the client has no
# single well-known file and the block is printed for pasting instead.
CLIENTS = {
    "claude-code": {
        "label": "Claude Code (CLI, VS Code and JetBrains extensions)",
        "paths": {"*": "~/.claude.json"},
        "key": "mcpServers",
    },
    "claude-code-project": {
        "label": "Claude Code, shared with a project (commit .mcp.json)",
        "paths": {"*": "./.mcp.json"},
        "key": "mcpServers",
    },
    "claude-desktop": {
        "label": "Claude Desktop",
        "paths": {
            "darwin": "~/Library/Application Support/Claude/claude_desktop_config.json",
            "win32": "%APPDATA%/Claude/claude_desktop_config.json",
            "*": "~/.config/Claude/claude_desktop_config.json",
        },
        "key": "mcpServers",
    },
    "cursor": {
        "label": "Cursor",
        "paths": {"*": "~/.cursor/mcp.json"},
        "key": "mcpServers",
    },
    "windsurf": {
        "label": "Windsurf",
        "paths": {"*": "~/.codeium/windsurf/mcp_config.json"},
        "key": "mcpServers",
    },
    "vscode": {
        "label": "VS Code (GitHub Copilot agent mode)",
        "paths": {"*": "./.vscode/mcp.json"},
        "key": "servers",
    },
    "zed": {
        "label": "Zed",
        "paths": {"*": "~/.config/zed/settings.json"},
        "key": "context_servers",
    },
    "print": {
        "label": "Any other MCP client - print the JSON to paste",
        "paths": {"*": None},
        "key": "mcpServers",
    },
}

SECRET_ENV = tuple(config_mod.ENV[f] for f in config_mod.SECRET_FIELDS)


def package_root():
    """The directory that must be on PYTHONPATH for `-m wamcp` to work."""
    return os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def server_block(config=None, with_secrets=False, python=None, root=None):
    """The MCP server definition to put in a client config."""
    root = root or package_root()
    env = {"PYTHONPATH": root}
    if with_secrets and config is not None:
        for field, var in config_mod.ENV.items():
            value = getattr(config, field, None)
            if field in ("db_path", "media_dir", "api_base"):
                continue
            if isinstance(value, bool):
                if value:
                    env[var] = "1"
            elif isinstance(value, (list, tuple)):
                if value:
                    env[var] = ",".join(str(v) for v in value)
            elif value not in (None, "", 0, 0.0) and str(value) != str(config_mod.DEFAULTS[field]):
                env[var] = str(value)
    return {
        "command": python or sys.executable or "python3",
        "args": ["-m", "wamcp", "serve"],
        "env": env,
    }


def config_document(config=None, with_secrets=False, key="mcpServers", **kwargs):
    return {key: {SERVER_NAME: server_block(config, with_secrets, **kwargs)}}


def claude_code_command(config=None, with_secrets=False, scope="user", **kwargs):
    """The one-liner that registers this server with the Claude Code CLI."""
    block = server_block(config, with_secrets, **kwargs)
    parts = ["claude", "mcp", "add", SERVER_NAME, "--scope", scope]
    for name, value in block["env"].items():
        parts += ["--env", f"{name}={value}"]
    parts += ["--", block["command"], *block["args"]]
    return " ".join(shlex.quote(p) if " " in p or '"' in p else p for p in parts)


def client_path(client):
    spec = CLIENTS[client]
    target = spec["paths"].get(sys.platform) or spec["paths"]["*"]
    if target is None:
        return None
    return os.path.abspath(os.path.expanduser(os.path.expandvars(target)))


def install(client, config=None, with_secrets=False, path=None, **kwargs):
    """Merge the server block into a client's config file.

    Returns (path, action) where action is 'created', 'updated' or 'unchanged'.
    """
    if client not in CLIENTS:
        raise KeyError(f"unknown client {client!r}; try one of: {', '.join(sorted(CLIENTS))}")
    spec = CLIENTS[client]
    target = path or client_path(client)
    if target is None:
        raise ValueError("this client has no config file to write; print the JSON instead")
    target = os.path.abspath(os.path.expanduser(target))

    document = {}
    if os.path.exists(target):
        try:
            with open(target, encoding="utf-8") as fh:
                text = fh.read().strip()
            document = json.loads(text) if text else {}
        except ValueError as e:
            raise ValueError(f"{target} is not valid JSON ({e}); fix or move it first")
        if not isinstance(document, dict):
            raise ValueError(f"{target} does not contain a JSON object")

    key = spec["key"]
    servers = document.setdefault(key, {})
    if not isinstance(servers, dict):
        raise ValueError(f"{target} has a {key!r} entry that is not an object")

    block = server_block(config, with_secrets, **kwargs)
    existed = SERVER_NAME in servers
    if existed and servers[SERVER_NAME] == block:
        return target, "unchanged"
    servers[SERVER_NAME] = block

    directory = os.path.dirname(target)
    if directory:
        os.makedirs(directory, exist_ok=True)
    if os.path.exists(target):
        # Keep a copy: these files often hold a user's other servers.
        try:
            with open(target, encoding="utf-8") as fh:
                original = fh.read()
            with open(target + ".wamcp-backup", "w", encoding="utf-8") as fh:
                fh.write(original)
        except OSError:
            pass
    tmp = target + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(document, fh, indent=2)
        fh.write("\n")
    os.replace(tmp, target)
    if with_secrets:
        try:
            os.chmod(target, 0o600)
        except OSError:
            pass
    return target, "updated" if existed else "created"
