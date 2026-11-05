# Spike 3: the companion's device tool

Part of [the Home Assistant link spec](https://github.com/Timwestberg/game-platform/blob/claude/feat/home-assistant-spec/docs/home-assistant/home-assistant.SPEC.md) (phase 0). No product code changes: the spike tool loads through a project's own configuration.

The spike checks that a work turn can use a Hovel tool with the three access levels, and that Hovel's existing approvals and activity rows cover it:

1. **Use** acts at once and writes an activity row.
2. **Use, ask first** opens Hovel's approval, and acts only after Allow once.
3. **Watch** and watch-only devices refuse at every level.

## The design under test

`hovel_devices.py` is a stdio MCP server, standard library only, with fake devices from `devices.json`. It maps the access levels to two tools, so each runtime's own permission system does the asking:

| Tool | Acts on | In the work turn |
| --- | --- | --- |
| `devices_list` | Lists the companion's devices, state and access | Allowed up front |
| `device_use` | Devices at Use | Allowed up front, so it acts at once |
| `device_request` | Devices at Use, ask first | Not allowed, so the runtime asks Hovel |

Locks, alarm panels, cameras, and covers with the device class garage or gate refuse at every level (safety rule 6). `devices.json` sets the front door lock and the garage door to `use` on purpose, to show that the rule wins over a wrong level. Every call writes one line to `activity.jsonl`.

## What gamero's code already shows

- **Claude Code asks through Hovel.** `ClaudeWorkRuntime` starts `claude -p … --permission-prompt-tool stdio`, so a tool call that is not allowed goes to Hovel's broker. `ClaudeControl.kt` turns an `mcp__` permission into `ApprovalSubject.Connector(server, tool, input)`.
- **Both runtimes record connector activity.** `ClaudeActivity.kt` gives `mcp__` tools `ActivityKind.CONNECTOR` with the label `server · tool`. `CodexWorkRuntime.kt` does the same for `mcpToolCall` items.
- **Chat turns cannot see the tool.** `ClaudeChatRuntime` passes `--disallowedTools mcp__* --strict-mcp-config`. This is safety rule 8, unchanged.
- **The approval text is a gap.** `ApprovalRequest.title` for a connector is `hovel-devices · device_request`, and its detail is the raw input JSON. Safety rule 7 needs "Ember wants to turn on Living room lamp", written from Hovel's own device data. Phase 4 adds that text for `hovel-devices`. The run below records what the dialog shows today.

## Run it on the Mac

1. Make a scratch project folder, for example in a `hovel-local-env-#` folder:

    ```sh
    ./make-project.sh ~/hovel-local-env-1/ha-device-tool
    ```

    It writes `.mcp.json` and `.claude/settings.json` (Claude Code reads both in the working folder), copies `devices.json`, and prints a Codex snippet.
2. Start Hovel on the Mac (`run-gamero`). Create a project on Studio Mac with that folder. Bind a working role to Claude.
3. Send these messages, one at a time, and fill the table:
    1. "List the devices you look after."
    2. "Set the floor lamp to 70%."
    3. "Turn on the living room lamp." Answer Allow once. Then send it again and answer Deny.
    4. "Unlock the front door." Then "Open the garage door."
4. Compare `activity.jsonl` in the project folder with the conversation's activity rows.
5. For Codex: add the printed snippet to `~/.codex/config.toml`, bind the role to Codex, and do step 3 again. Take the snippet out after the run.

## Checks in the cloud

`python3 -m unittest -v` drives the server over stdio as an MCP client does: nine checks, which pass on Python 3.13. They prove the tool's rules, not the runtimes.

## Results

| Runtime | Message | Approval shown? Its text | Device changed? | Hovel activity row | `activity.jsonl` line |
| --- | --- | --- | --- | --- | --- |
| Claude | List | | | | |
| Claude | Floor lamp 70% (Use) | | | | |
| Claude | Living room lamp, Allow once | | | | |
| Claude | Living room lamp, Deny | | | | |
| Claude | Unlock, garage | | | | |
| Codex | List | | | | |
| Codex | Floor lamp 70% (Use) | | | | |
| Codex | Living room lamp, Allow once | | | | |
| Codex | Living room lamp, Deny | | | | |
| Codex | Unlock, garage | | | | |

## What it decides

- Phase 4 can inject the tool per turn (Claude `--mcp-config` and `--allowedTools`) the same way this project configuration does, if Use and Use, ask first behave as above.
- If Codex asks for every MCP call, or for none, Codex needs its own way to split Use from Use, ask first. Record what it does here.
- Every Use call must have a Hovel activity row. A missing row blocks the Use level (spec risk for decision 6).
