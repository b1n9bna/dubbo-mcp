#!/usr/bin/env python3
"""Record real Codex CLI JSONL privately. Refuse to report success on failed RPCs.

Uses the user's existing Codex model/auth configuration, without changing it.
The recording guard exposes only test discovery, metadata and one dictionary RPC.
Raw outputs can contain internal identifiers: keep --output outside a public repo.
"""
import argparse
import json
import os
from pathlib import Path
import queue
import subprocess
import sys
import threading
import time

ROOT = Path(__file__).resolve().parents[2]
EXPECTED = ["dubbo_list_services", "dubbo_get_service_detail", "dubbo_invoke"]


def successful(events):
    if any(event.get("type") in {"error", "turn.failed"} for event in events):
        return False
    calls = [event["item"] for event in events if event.get("type") == "item.completed"
             and event.get("item", {}).get("type") == "mcp_tool_call"]
    if [call.get("tool") for call in calls] != EXPECTED:
        return False
    if any(call.get("status") != "completed" or call.get("error") for call in calls):
        return False
    if any(not isinstance(call.get("result"), dict) or call["result"].get("isError") for call in calls):
        return False
    try:
        content = calls[-1]["result"]["content"]
        result = json.loads(next(item["text"] for item in content if item["type"] == "text"))
        return result["status"] == "SUCCESS" and result["data"]["success"] is True
    except (KeyError, TypeError, ValueError, StopIteration):
        return False


def record(options):
    output = options.output.expanduser().resolve()
    if output == ROOT or ROOT in output.parents:
        raise ValueError("Choose a private --output directory outside this repository")
    os.umask(0o077)
    output.mkdir(parents=True, exist_ok=True)
    bridge = ROOT / "scripts/demo/read_only_bridge.py"
    backend = [options.java] + options.java_arg + ["-jar", str(options.jar.expanduser().resolve()), "--env=test"]
    args = ["codex", "exec", "--json", "--ephemeral", "--sandbox", "read-only", "-C", str(options.cwd.resolve()),
            "-c", "mcp_servers.codegraph.enabled=false", "-c", "mcp_servers.node_repl.enabled=false",
            "-c", "mcp_servers.dubbo.command=" + json.dumps(sys.executable),
            "-c", "mcp_servers.dubbo.args=" + json.dumps([str(bridge), "--interface", options.interface, "--"] + backend), "-"]
    prompt = ('帮我查一下测试环境有哪些装柜类型，列出编码和名称，再统计共有几种类型。\n'
              '这是一次只读演示。只通过 dubbo MCP 依次完成服务发现、目标接口元数据读取、'
              'getLoadingTypeDict() 无参调用。目标接口是 ' + options.interface + '。'
              '所有工具参数必须明确 env=test；不调用其他方法，不执行 shell，不修改文件，不委派。'
              '只根据真实结果回答，隐藏完整公司包名和 DTO class 字段。')
    messages = queue.Queue()
    started = time.monotonic()
    events = []
    with (output / "stderr.log").open("w") as stderr, (output / "events.jsonl").open("w") as trace:
        process = subprocess.Popen(args, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=stderr,
                                   text=True, encoding="utf-8", bufsize=1)
        def read_output():
            for line in process.stdout:
                messages.put(line)
            messages.put(None)
        threading.Thread(target=read_output, daemon=True).start()
        try:
            process.stdin.write(prompt)
            process.stdin.close()
            while True:
                remaining = options.timeout - (time.monotonic() - started)
                line = messages.get(timeout=max(.01, remaining))
                if line is None:
                    break
                event = json.loads(line)
                events.append(event)
                trace.write(json.dumps({"at": round(time.monotonic() - started, 3), "event": event}, ensure_ascii=False) + "\n")
                trace.flush()
            code = process.wait(timeout=max(1, options.timeout - (time.monotonic() - started)))
        except (queue.Empty, subprocess.TimeoutExpired):
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
            raise RuntimeError("CLI recording timed out; private trace retained")
        finally:
            if process.poll() is None:
                process.terminate()
    ok = code == 0 and successful(events)
    status = {"success": ok, "exitCode": code, "durationSeconds": round(time.monotonic() - started, 3)}
    (output / "status.json").write_text(json.dumps(status, indent=2))
    print("CLI recording verified: " + ("PASS" if ok else "FAILED — inspect private events.jsonl; do not publish as successful footage"))
    return 0 if ok else 1


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--interface", required=True)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--java", default="java")
    parser.add_argument("--java-arg", action="append", default=[])
    parser.add_argument("--cwd", type=Path, default=Path.cwd())
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--timeout", type=int, default=180)
    raise SystemExit(record(parser.parse_args()))
