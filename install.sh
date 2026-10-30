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

# 3. Download or install the Fat-JAR
echo "📦 Downloading latest dubbo-mcp release..."
RELEASE_URL="https://github.com/b1n9bna/dubbo-mcp/releases/latest/download/dubbo-mcp.jar"

# If running inside the repo folder, copy local target jar directly for instant testing
if [ -f "./target/dubbo-mcp-1.0.0-SNAPSHOT.jar" ]; then
    echo "ℹ️ Using local build artifact: target/dubbo-mcp-1.0.0-SNAPSHOT.jar"
    cp "./target/dubbo-mcp-1.0.0-SNAPSHOT.jar" "$JAR_PATH"
elif curl -fsSL -I "$RELEASE_URL" >/dev/null 2>&1; then
    curl -fsSL -o "$JAR_PATH" "$RELEASE_URL"
else
    # Fallback to cloning/building or direct raw download
    echo "ℹ️ Downloading default bundle..."
    curl -fsSL -o "$JAR_PATH" "https://raw.githubusercontent.com/b1n9bna/dubbo-mcp/main/target/dubbo-mcp-1.0.0-SNAPSHOT.jar" 2>/dev/null || true
fi

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
"$CLI_WRAPPER" install || true

echo "\n${BOLD}${GREEN}🎉 Dubbo-MCP installed successfully!${RESET}"
echo "Run ${BOLD}dubbo-mcp --help${RESET} to inspect commands."
echo "If the command is not immediately found, run: ${BOLD}source $SHELL_RC${RESET}\n"
