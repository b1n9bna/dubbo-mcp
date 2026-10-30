#!/usr/bin/env sh
set -e

# ==============================================================================
# Dubbo-MCP Standalone Installer (macOS & Linux)
# Inspired by CodeGraph: 1-line curl install + auto-wiring for Codex, Cursor, Claude
# ==============================================================================

BOLD="$(tput bold 2>/dev/null || echo '')"
GREEN="$(tput setaf 2 2>/dev/null || echo '')"
BLUE="$(tput setaf 4 2>/dev/null || echo '')"
YELLOW="$(tput setaf 3 2>/dev/null || echo '')"
RESET="$(tput sgr0 2>/dev/null || echo '')"

echo "${BOLD}${BLUE}🧭 Starting Dubbo-MCP installation...${RESET}\n"

# 1. Check Java dependency
if ! command -v java >/dev/null 2>&1; then
    echo "${YELLOW}⚠️ Java is required but was not found on your system.${RESET}"
    echo "Please install Java 8 or above (e.g. brew install openjdk) and re-run this script."
    exit 1
fi

JAVA_VERSION=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}')
echo "✓ Detected Java runtime: ${GREEN}${JAVA_VERSION}${RESET}"

# 2. Setup install directories
INSTALL_DIR="$HOME/.dubbo-mcp"
BIN_DIR="$HOME/.local/bin"
mkdir -p "$INSTALL_DIR"
mkdir -p "$BIN_DIR"

JAR_PATH="$INSTALL_DIR/dubbo-mcp.jar"
CLI_WRAPPER="$BIN_DIR/dubbo-mcp"

# 3. Stage and validate before replacing the installed JAR.
RELEASE_URL="https://github.com/b1n9bna/dubbo-mcp/releases/latest/download/dubbo-mcp.jar"
STAGED_JAR=$(mktemp "$INSTALL_DIR/dubbo-mcp.XXXXXX")
trap 'rm -f "$STAGED_JAR"' EXIT HUP INT TERM
if [ -f "./target/dubbo-mcp-1.0.0-SNAPSHOT.jar" ]; then
    cp "./target/dubbo-mcp-1.0.0-SNAPSHOT.jar" "$STAGED_JAR"
else
    curl -fSL --connect-timeout 10 --max-time 120 -o "$STAGED_JAR" "$RELEASE_URL"
fi
java -jar "$STAGED_JAR" --help >/dev/null 2>&1 || {
    echo "Invalid or incompatible JAR; existing installation was preserved." >&2
    exit 1
}
mv "$STAGED_JAR" "$JAR_PATH"

# 4. Generate the CLI wrapper script
cat << 'EOF' > "$CLI_WRAPPER"
#!/usr/bin/env sh
exec java -jar "$HOME/.dubbo-mcp/dubbo-mcp.jar" "$@"
EOF

chmod +x "$CLI_WRAPPER"
echo "✓ Installed executable to: ${GREEN}$CLI_WRAPPER${RESET}"

# 5. Ensure ~/.local/bin is in PATH
SHELL_RC=""
case "$SHELL" in
    */zsh)  SHELL_RC="$HOME/.zshrc" ;;
    */bash) SHELL_RC="$HOME/.bashrc" ;;
    *)      SHELL_RC="$HOME/.profile" ;;
esac

if ! echo "$PATH" | grep -q "$BIN_DIR"; then
    if [ -f "$SHELL_RC" ]; then
        if ! grep -q "$BIN_DIR" "$SHELL_RC"; then
            echo "\n# Dubbo-MCP PATH" >> "$SHELL_RC"
            echo "export PATH=\"$BIN_DIR:\$PATH\"" >> "$SHELL_RC"
            echo "✓ Added $BIN_DIR to $SHELL_RC"
        fi
    fi
    export PATH="$BIN_DIR:$PATH"
fi

# 6. Auto-wire detected AI Coding Agents
echo ""
"$CLI_WRAPPER" install

echo "\n${BOLD}${GREEN}🎉 Dubbo-MCP installed successfully!${RESET}"
echo "Run ${BOLD}dubbo-mcp --help${RESET} to inspect commands."
echo "If the command is not immediately found, run: ${BOLD}source $SHELL_RC${RESET}\n"
