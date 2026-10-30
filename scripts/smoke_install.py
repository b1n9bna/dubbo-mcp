#!/usr/bin/env python3
"""Offline POSIX installer regression; uses a copied script, fake Java and temporary paths only."""
from pathlib import Path
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent.parent
with tempfile.TemporaryDirectory(prefix="dubbo-installer-") as temp:
    sandbox = Path(temp)
    home = sandbox / "fresh-user"
    fake_bin = sandbox / "bin"
    fake_bin.mkdir()
    # Redirect only home references in the copied script; the real home is never changed.
    script = sandbox / "install.sh"
    script.write_text((ROOT / "install.sh").read_text().replace("$HOME", "$DUBBO_INSTALL_TEST_HOME"))
    java = fake_bin / "java"
    java.write_text('#!/bin/sh\nif [ "$1" = "-version" ]; then echo \'openjdk version "17"\' >&2; fi\nexit 0\n')
    java.chmod(0o755)
    (sandbox / "target").mkdir()
    (sandbox / "target/dubbo-mcp.jar").write_bytes(b"offline test artifact")
    env = dict(os.environ, DUBBO_INSTALL_TEST_HOME=str(home), SHELL="/bin/zsh", PATH=str(fake_bin) + ":" + os.environ["PATH"])
    for iteration in range(2):
        if iteration:
            env["PATH"] = str(home / ".local/bin") + ":" + env["PATH"]
        result = subprocess.run(["sh", str(script)], cwd=sandbox, env=env, capture_output=True, text=True, timeout=10)
        assert result.returncode == 0, result.stderr
        rc = home / ".zshrc"
        assert rc.exists(), "Fresh startup file was not created"
        assert rc.read_text().count("# Dubbo-MCP PATH") == 1, "PATH injection must be idempotent"
        result = subprocess.run(["sh", "-c", '. "$1"; command -v dubbo-mcp', "sh", str(rc)], env=env, capture_output=True, text=True, timeout=5)
        assert result.returncode == 0 and result.stdout.strip() == str(home / ".local/bin/dubbo-mcp")
    print("Fresh startup file, persistent PATH and repeat-install idempotence: PASS")
