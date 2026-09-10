# File System MCP Server in Pure Kotlin Script

A self-contained single file **Model Context Protocol (MCP) server** that exposes file-system
operations and a shell executor to any MCP client (Claude Desktop, LM Studio, IDEs, agents, …).

The entire server is a single executable **Kotlin script** (`fs_mcp_server.main.kts`).
There is no build system, no `build.gradle.kts`, no source tree — dependencies are
declared inline and resolved from Maven Central at first start-up.

## Features

- **MCP over stdio** — speaks JSON-RPC on stdin/stdout
- **Zero-build** — run it directly, the compiler fetches all dependencies automatically.
- **Tools provided:**

  | Tool | Purpose |
  |---|---|
  | `browse_fs` | List files and directories (type, size, name) in a directory |
  | `create_file` | Create a new file; fails if already exists |
  | `write_file` | Write content to a file |
  | `delete_file` | Delete a file, or a directory tree recursively |
  | `read_lines` | Read a line range `[from, to)` with 0-based, half-open indices |
  | `change_lines` | Replace / insert / delete multiple line ranges in one atomic operation |
  | `run_shell` | Execute a shell command; returns `exit_code`, `stdout`, `stderr` separately |

- **Clean error reporting** — tool-level failures are returned through the MCP
  `isError` channel (not mixed into normal text output).
- **Safe stdio usage** — the protocol stream on stdout is never polluted: incidental
  `print` output from libraries is redirected to stderr.
- **Deadlock-safe shell execution** — stdout and stderr of child processes are drained
  concurrently, so long-running commands (>64 KB on an unread stream) can't hang.

## Requirements

- A **Kotlin compiler 2.x** on `PATH` (`kotlinc`), since the file is run as a Kotlin script
- Outbound HTTPS access to Maven Central (first run only, for dependency download)

## Quick Start

Just download the `fs_mcp_server.main.kts`, make it executable and run

> **First run is slow:** `kotlinc` compiles the script and downloads dependencies
> (Kotlin MCP SDK, kotlinx-coroutines, kotlinx-serialization, kotlinx-io, slf4j-nop)
> from Maven Central. Subsequent runs are much faster.

### Example client configuration (Claude Desktop / LM Studio / any MCP client)


```json
{
  "mcpServers": {
    "filesystem": {
      "command": "/path/to/mcp_fs/fs_mcp_server.main.kts"
    }
  }
}
```

## License

This project is in the **public domain**. To the extent possible under law, the
author has dedicated all copyright and related and neighboring rights to this
software to the public domain worldwide. You may use, copy, modify, merge,
publish, distribute and sell the code, with or without fee, for any purpose,
commercial or otherwise, at your own risk. See <https://unlicense.org> for the
full text (The Unlicense) and the accompanying disclaimer.
