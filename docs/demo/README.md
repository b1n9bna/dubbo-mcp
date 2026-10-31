# Demo source and recording

The README animation is a **rendered replay of real MCP calls in a Codex chat**, not a screen recording of Codex CLI. The English edition translates the question and UI labels; the RPC values remain unchanged. It makes no end-to-end latency claim.

The three successful calls were recorded on 2026-10-31 in the test environment:

1. `dubbo_list_services`: discover the transport service.
2. `dubbo_get_service_detail`: inspect its no-argument `getLoadingTypeDict()` method.
3. `dubbo_invoke`: query the real dictionary and count its returned entries.

The published [source record](session.json) contains only the environment, short interface identifier, method, call timestamps, and dictionary `code`/`desc` fields. Full company package names, DTO `class` fields, and unrelated metadata are omitted. The returned values are `0=整车`, `1=散货`, `2=小包`, giving **3 types**. No example order or customer record was fabricated. The server's configured transport is used; this replay does not claim a particular wire protocol.

The clip uses an editorial 15-second timeline so that the tool sequence and JSON can be read. The original MCP timestamps are in the source record. There is currently **no verified successful Codex CLI recording** included in this repository.

## Media

| Edition | Autoplay GIF | MP4 | Static poster |
| --- | --- | --- | --- |
| 中文 | [GIF](../media/dubbo-mcp-replay-zh.gif) | [MP4](../media/dubbo-mcp-replay-zh.mp4) | [PNG](../media/dubbo-mcp-replay-zh.png) |
| English | [GIF](../media/dubbo-mcp-replay-en.gif) | [MP4](../media/dubbo-mcp-replay-en.mp4) | [PNG](../media/dubbo-mcp-replay-en.png) |

GIFs loop automatically in the README. MP4 and static posters are available for sharing or reduced-motion viewing.

## Record a real Codex CLI session

Use Python 3.11+, an authenticated Codex CLI, your configured test environment and a built dubbo-mcp JAR. Choose an interface that exposes the read-only, no-argument dictionary method. Keep the raw recording in a private directory **outside the public repository**:

```bash
python3 scripts/demo/record_cli.py \
  --interface com.example.TransportRpcService \
  --jar "$HOME/.dubbo-mcp/dubbo-mcp.jar" \
  --cwd /path/to/your/project \
  --output "$HOME/dubbo-demo-private"
```

Replace the example interface with your actual test interface. The script uses the existing Codex model and authentication configuration; it does not edit the user's configuration. For native Dubbo mode with Java 17/21, also supply `--java-arg=--add-opens=java.base/java.lang=ALL-UNNAMED` and `--java-arg=--add-opens=java.base/java.math=ALL-UNNAMED`, or select Java 8/11 with `--java`.

The [recording guard](../../scripts/demo/read_only_bridge.py) forwards calls to the real JAR. It only exposes the three tools above, requires `env=test`, restricts metadata and invocation to the selected interface, and allows only `getLoadingTypeDict()` with no arguments or attachments. Its read-only annotations apply to this restricted recording entry point. The project's general `dubbo_invoke` tool retains its existing behavior. See the [MCP tool annotation specification](https://modelcontextprotocol.io/specification/2025-06-18/schema#toolannotations).

The recorder captures the [actual Codex CLI JSONL event stream](https://learn.chatgpt.com/docs/non-interactive-mode). A zero CLI exit code alone is insufficient: all three successful tool calls and a successful RPC response are required. Approval failures, missing calls, timeouts, and account limits fail verification. Review and redact raw recordings before publishing; never relabel the existing MCP replay as CLI footage.

## Rebuild the rendered replay

```bash
python3 -m venv /tmp/dubbo-demo-media
/tmp/dubbo-demo-media/bin/pip install Pillow imageio-ffmpeg
/tmp/dubbo-demo-media/bin/python scripts/demo/render_replay.py
python3 -m unittest discover -s scripts/demo -p 'test_*.py'
```

The renderer makes no network or RPC calls. It needs a CJK font and a monospace font. macOS PingFang/Menlo and Linux Noto Sans CJK/DejaVu Sans Mono are detected automatically; override paths using `DEMO_CJK_FONT` and `DEMO_MONO_FONT`. Generated `*-contact.png` files are QA sheets, not README assets.
