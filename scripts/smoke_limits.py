#!/usr/bin/env python3
"""Bounded-input regression using a disposable 24 MiB JVM and synthetic STDIO frames."""
import json
import os
from pathlib import Path
import queue
import subprocess
import sys
import tempfile
import threading

jar = str(Path(sys.argv[1]).resolve())
java = sys.argv[2] if len(sys.argv) > 2 else "java"
with tempfile.TemporaryDirectory(prefix="dubbo-frame-limit-") as home:
    log = Path(home) / "stderr.log"
    with log.open("wb") as stderr:
        process = subprocess.Popen([java, "-Xmx24m", "-Duser.home=" + home, "-jar", jar],
                                   stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=stderr)
        responses = queue.Queue()
        failures = []
        def read():
            for line in process.stdout:
                responses.put(json.loads(line))
        threading.Thread(target=read, daemon=True).start()
        def send():
            try:
                process.stdin.write(b'{"jsonrpc":"2.0","id":1,"method":"initialize"}\n')
                process.stdin.flush()
                for _ in range(1024):
                    process.stdin.write(b"x" * 32768)
                process.stdin.write(b'\n{"jsonrpc":"2.0","id":2,"method":"ping"}\n')
                process.stdin.flush()
            except Exception as error:
                failures.append(type(error).__name__)
        writer = threading.Thread(target=send, daemon=True)
        try:
            writer.start()
            first = responses.get(timeout=10)
            assert first["id"] == 1 and "result" in first
            rejected = responses.get(timeout=10)
            assert rejected.get("error") and rejected.get("id") is None
            pong = responses.get(timeout=10)
            assert pong["id"] == 2 and "result" in pong
            writer.join(timeout=5)
            assert not writer.is_alive() and not failures, failures
            process.stdin.close()
            assert process.wait(timeout=5) == 0
            assert "OutOfMemoryError" not in log.read_text(errors="replace")
            print("24 MiB JVM: discarded 32 MiB frame, recovered ping, clean EOF: PASS")
        finally:
            if process.poll() is None:
                process.kill()
                process.wait(timeout=5)
