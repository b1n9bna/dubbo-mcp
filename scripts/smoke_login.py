#!/usr/bin/env python3
"""Offline CLI regression: long credentials, failed authentication, and POSIX TTY restoration.
Usage: python3 scripts/smoke_login.py /absolute/path/to/dubbo-mcp.jar
Uses only synthetic credentials, a temporary home, and a loopback HTTP server.
"""
import http.server
import json
import os
from pathlib import Path
import select
import subprocess
import sys
import tempfile
import threading
import time

JAR = str(Path(sys.argv[1]).resolve())
CREDENTIAL = json.dumps({"cookie": "session=" + "x" * 6000, "authorization": "offline-test-token"})

class Admin(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/":
            code, body = 200, b"<html></html>"
        elif self.path == "/api/dev/services" and self.headers.get("Authorization") == "offline-test-token":
            code, body = 200, b'["com.example.ReadService"]'
        else:
            code, body = 401, b"{}"
        self.send_response(code)
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass

server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Admin)
threading.Thread(target=server.serve_forever, daemon=True).start()
env = dict(os.environ, LANG="en_US.UTF-8", LC_ALL="en_US.UTF-8")

def prepare(home):
    root = Path(home) / ".dubbo-mcp"
    root.mkdir()
    (root / "config.properties").write_text("env.prod.admin_url=http://127.0.0.1:%s\n" % server.server_port)
    return root / "session-prod.json"

def command(home, *args):
    return ["java", "-Duser.home=" + home, "-jar", JAR, "login", *args]

def verify(session):
    assert json.loads(json.loads(session.read_text())["credential"]) == json.loads(CREDENTIAL)
    if os.name == "posix":
        assert session.stat().st_mode & 0o777 == 0o600

try:
    for kind in ("stdin", "file", "auth_failure"):
        with tempfile.TemporaryDirectory(prefix="dubbo-login-") as home:
            session = prepare(home)
            if kind == "file":
                source = Path(home) / "input.json"
                source.write_text(CREDENTIAL)
                result = subprocess.run(command(home, "--file", str(source)), capture_output=True, env=env, timeout=15)
            else:
                value = CREDENTIAL if kind == "stdin" else '{"authorization":"invalid"}'
                result = subprocess.run(command(home, "--stdin"), input=value.encode(), capture_output=True, env=env, timeout=15)
            assert b"x" * 100 not in result.stdout + result.stderr
            if kind == "auth_failure":
                assert result.returncode != 0 and not session.exists()
            else:
                assert result.returncode == 0, result.stderr.decode(errors="replace")
                verify(session)
            print(kind + ": PASS")
    if os.name == "posix":
        import pty
        import termios
        with tempfile.TemporaryDirectory(prefix="dubbo-login-tty-") as home:
            session = prepare(home)
            pid, master = pty.fork()
            if pid == 0:
                os.execvpe("java", command(home), env)
            output = bytearray()
            reaped = False
            try:
                deadline = time.monotonic() + 15
                while termios.tcgetattr(master)[3] & termios.ICANON:
                    assert time.monotonic() < deadline, "terminal never entered noncanonical mode"
                    ready, _, _ = select.select([master], [], [], .05)
                    if ready:
                        output.extend(os.read(master, 8192))
                data = (CREDENTIAL + "\n").encode()
                while data:
                    data = data[os.write(master, data):]
                while time.monotonic() < deadline:
                    ready, _, _ = select.select([master], [], [], .05)
                    if ready:
                        try:
                            output.extend(os.read(master, 8192))
                        except OSError:
                            pass
                    found, status = os.waitpid(pid, os.WNOHANG)
                    if found:
                        reaped = True
                        assert os.WIFEXITED(status) and os.WEXITSTATUS(status) == 0, output.decode(errors="replace")
                        break
                assert reaped, "long TTY login timed out"
                assert b"x" * 100 not in output, "credentials echoed to TTY"
                assert termios.tcgetattr(master)[3] & termios.ICANON, "terminal mode was not restored"
                verify(session)
                print("6000-byte TTY input + no echo + terminal restoration: PASS")
            finally:
                if not reaped:
                    os.kill(pid, 9)
                    os.waitpid(pid, 0)
                os.close(master)
finally:
    server.shutdown()
    server.server_close()
