#!/usr/bin/env python3
"""Offline large-HTTP-response regression using a disposable 24 MiB JVM and loopback server."""
import http.server
import json
from pathlib import Path
import queue
import subprocess
import sys
import tempfile
import threading
import zlib

CHUNK = b"x" * 32768
COUNT = 1024
PREFIX, SUFFIX = b'{"data":"', b'"}'

class Admin(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def log_message(self, *args):
        pass
    def do_POST(self):
        request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        mode = request["method"]
        try:
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            if mode == "queryGzip":
                compressor = zlib.compressobj(wbits=31)
                compressed = [compressor.compress(PREFIX)]
                for _ in range(COUNT):
                    compressed.append(compressor.compress(CHUNK))
                compressed.extend([compressor.compress(SUFFIX), compressor.flush()])
                compressed = b"".join(compressed)
                self.send_header("Content-Encoding", "gzip")
                self.send_header("Content-Length", str(len(compressed)))
                self.end_headers()
                self.wfile.write(compressed)
                return
            chunked = mode == "queryChunked"
            if chunked:
                self.send_header("Transfer-Encoding", "chunked")
            else:
                self.send_header("Content-Length", str(len(PREFIX) + COUNT * len(CHUNK) + len(SUFFIX)))
            self.end_headers()
            def write(data):
                if chunked:
                    self.wfile.write(("%x\r\n" % len(data)).encode() + data + b"\r\n")
                else:
                    self.wfile.write(data)
            write(PREFIX)
            for _ in range(COUNT):
                write(CHUNK)
            write(SUFFIX)
            if chunked:
                self.wfile.write(b"0\r\n\r\n")
        except (BrokenPipeError, ConnectionResetError):
            self.close_connection = True

server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Admin)
server.daemon_threads = True
threading.Thread(target=server.serve_forever, daemon=True).start()
try:
    with tempfile.TemporaryDirectory(prefix="dubbo-http-limit-") as home:
        root = Path(home)
        (root / ".dubbo-mcp").mkdir()
        (root / ".dubbo-mcp/config.properties").write_text(
            "env.audit.type=dubbo-admin\nenv.audit.readonly=true\nenv.audit.admin_api_prefix=\n"
            "env.audit.admin_cookie=OFFLINE_FAKE_ONLY\nenv.audit.admin_url=http://127.0.0.1:%s\n" % server.server_port)
        with (root / "stderr.log").open("wb") as stderr:
            java = sys.argv[2] if len(sys.argv) > 2 else "java"
            process = subprocess.Popen([java, "-Xmx24m", "-Duser.home=" + home, "-jar", str(Path(sys.argv[1]).resolve())],
                                       stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=stderr)
            responses = queue.Queue()
            def read():
                for line in process.stdout:
                    responses.put(json.loads(line))
            threading.Thread(target=read, daemon=True).start()
            def send(request):
                process.stdin.write((json.dumps(request) + "\n").encode())
                process.stdin.flush()
                return responses.get(timeout=10)
            try:
                assert send(dict(jsonrpc="2.0", id=0, method="initialize"))["id"] == 0
                for index, method in enumerate(("queryFixed", "queryChunked", "queryGzip"), 1):
                    args = dict(env="audit", interfaceName="com.example.Service:1", method=method, paramTypes=[], args=[])
                    result = send(dict(jsonrpc="2.0", id=index, method="tools/call", params=dict(name="dubbo_invoke", arguments=args)))
                    assert result["id"] == index and result["result"]["isError"]
                    assert "byte limit" in result["result"]["content"][0]["text"]
                    print(method + " bounded response: PASS")
                assert send(dict(jsonrpc="2.0", id=99, method="ping"))["id"] == 99
                process.stdin.close()
                assert process.wait(timeout=5) == 0
                assert "OutOfMemoryError" not in (root / "stderr.log").read_text(errors="replace")
                print("24 MiB JVM: oversized HTTP responses fail cleanly, ping and EOF: PASS")
            finally:
                if process.poll() is None:
                    process.kill()
                    process.wait(timeout=5)
finally:
    server.shutdown()
    server.server_close()
