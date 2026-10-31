#!/usr/bin/env python3
"""Recording-only MCP guard. Forward real RPC traffic, limited to one test dictionary.

This is not a mock provider. The underlying dubbo-mcp process supplies every result.
The invoke tool is annotated read-only only because this guard rejects all other
methods, interfaces, arguments and environments before forwarding a request.
"""
import argparse
import json
import subprocess
import sys
import threading

TOOLS = {"dubbo_list_services", "dubbo_get_service_detail", "dubbo_invoke"}
METHOD = "getLoadingTypeDict"
LIMIT = 1024 * 1024


def allowed_call(params, interface):
    if not isinstance(params, dict):
        return False
    name = params.get("name")
    args = params.get("arguments", {})
    if name not in TOOLS or not isinstance(args, dict) or args.get("env") != "test":
        return False
    if name == "dubbo_list_services":
        return set(args) <= {"env", "keyword"}
    if args.get("interfaceName") != interface:
        return False
    if name == "dubbo_get_service_detail":
        return set(args) <= {"env", "interfaceName", "version", "group"}
    return (set(args) <= {"env", "interfaceName", "method", "version", "group", "args", "paramTypes"}
            and args.get("method") == METHOD
            and args.get("args", []) == [] and args.get("paramTypes", []) == [])


def run(interface, command):
    process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE)
    output_lock = threading.Lock()
    listed_ids = set()
    ids_lock = threading.Lock()

    def emit(message):
        with output_lock:
            sys.stdout.write(json.dumps(message, ensure_ascii=False) + "\n")
            sys.stdout.flush()

    def forward_results():
        for line in iter(lambda: process.stdout.readline(LIMIT + 1), b""):
            if len(line) > LIMIT:
                process.terminate()
                return
            message = json.loads(line)
            with ids_lock:
                is_list = message.get("id") in listed_ids
                if is_list:
                    listed_ids.remove(message["id"])
            if is_list and "result" in message:
                tools = [tool for tool in message["result"].get("tools", []) if tool["name"] in TOOLS]
                for tool in tools:
                    tool["annotations"] = {"readOnlyHint": True, "destructiveHint": False,
                                           "idempotentHint": True, "openWorldHint": True}
                    if tool["name"] == "dubbo_invoke":
                        tool["description"] = "Recording guard: test environment, " + METHOD + "(), no arguments, selected interface only."
                message["result"]["tools"] = tools
            emit(message)

    reader = threading.Thread(target=forward_results, daemon=True)
    reader.start()
    try:
        while True:
            line = sys.stdin.buffer.readline(LIMIT + 1)
            if not line:
                break
            if len(line) > LIMIT:
                raise ValueError("Recording request exceeds size limit")
            request = json.loads(line)
            method = request.get("method")
            allowed = method in {"initialize", "ping", "tools/list", "notifications/initialized", "notifications/cancelled"}
            if method == "tools/call":
                allowed = allowed_call(request.get("params", {}), interface)
            if not allowed:
                if "id" in request:
                    emit({"jsonrpc": "2.0", "id": request["id"], "error": {
                        "code": -32602, "message": "Recording guard permits only the selected test dictionary query."}})
                continue
            if method == "tools/list":
                with ids_lock:
                    listed_ids.add(request["id"])
            process.stdin.write(line)
            process.stdin.flush()
    finally:
        process.stdin.close()
        try:
            process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            process.terminate()
            process.wait(timeout=5)
        reader.join(timeout=2)
    return process.returncode


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--interface", required=True)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    options = parser.parse_args()
    command = options.command[1:] if options.command[:1] == ["--"] else options.command
    if not command:
        parser.error("A real dubbo-mcp launch command is required after --")
    raise SystemExit(run(options.interface, command))
