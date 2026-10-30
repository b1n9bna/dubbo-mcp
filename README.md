<p align="right">
  <b>English</b> | <b><a href="README_zh.md">中文说明</a></b>
</p>

# Dubbo-MCP

<p align="center">
  <b>Enterprise Apache Dubbo RPC Bridge for Model Context Protocol (MCP) & AI Coding Agents</b><br>
  <i>Connect Cursor, Claude Desktop, Claude Code, Windsurf, and Codex directly to your Dubbo microservices.</i>
</p>

<p align="center">
  <a href="https://github.com/b1n9bna/dubbo-mcp/releases/latest"><img src="https://img.shields.io/github/v/release/b1n9bna/dubbo-mcp?style=flat-square&color=blue" alt="Latest Release"></a>
  <img src="https://img.shields.io/badge/Model_Context_Protocol-MCP-green?style=flat-square" alt="MCP Compatible">
  <a href="https://glama.ai/mcp/servers/b1n9bna/dubbo-mcp"><img src="https://glama.ai/mcp/servers/b1n9bna/dubbo-mcp/badges/score.svg" alt="Glama Score"></a>
  <img src="https://img.shields.io/badge/JDK-8_|_11_|_17_|_21-blue?style=flat-square&logo=openjdk" alt="JDK 8+">
  <img src="https://img.shields.io/badge/Cold_Start-<100_ms-brightgreen?style=flat-square&logo=lightning" alt="Cold Start">
  <img src="https://img.shields.io/badge/RAM_RSS-~25_MB-success?style=flat-square&logo=speedtest" alt="RAM RSS">
  <img src="https://img.shields.io/badge/Dubbo-2.7.x_|_3.x-orange?style=flat-square&logo=apache" alt="Dubbo Compatible">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache_2.0-blue.svg?style=flat-square" alt="License"></a>
</p>

Dubbo-MCP is a [Model Context Protocol (MCP)](https://modelcontextprotocol.io/) server that connects AI coding assistants (Cursor, Claude Code, Claude Desktop, Codex CLI, Windsurf, etc.) directly to Apache Dubbo microservices.

During daily troubleshooting, interface debugging, and unit testing, it allows AI coding agents to discover registered service definitions, inspect complex DTO parameter structures, and execute real-time RPC calls via native Generic Invocation — **with no need to write manual test cases and zero requirement to import API dependency JARs into local projects.**

---

## 🚀 Quick Start

### 1. One-Line Script Installation

**macOS / Linux:**
```bash
curl -fsSL https://raw.githubusercontent.com/b1n9bna/dubbo-mcp/main/install.sh | sh
```

**Windows (PowerShell):**
```powershell
irm https://raw.githubusercontent.com/b1n9bna/dubbo-mcp/main/install.ps1 | iex
```

The script downloads the standalone executable to `~/.dubbo-mcp/dubbo-mcp.jar` and creates a global command `dubbo-mcp`.

*(Or compile locally from source: `mvn clean package -DskipTests`)*

### 2. Auto-Wire AI Coding Agents

After installation, run the following command to automatically detect and configure installed AI agents (Codex, Cursor, Claude Desktop):
```bash
dubbo-mcp install
```

---

## ⚙️ Configuration

Configuration file location: `~/.dubbo-mcp/config.properties`

```properties
# Default active environment (test / uat / prod)
active.env=test

# ==============================================================================
# 1. Dev / Test Environment (Direct ZooKeeper connection, Generic Invocation)
# ==============================================================================
env.test.type=zookeeper
env.test.registry=zookeeper://127.0.0.1:2181
env.test.timeout=5000
env.test.readonly=false

# ==============================================================================
# 2. Staging / UAT Environment
# ==============================================================================
env.uat.type=zookeeper
env.uat.registry=zookeeper://10.20.1.15:2181
env.uat.timeout=5000
env.uat.readonly=true

# ==============================================================================
# 3. Production Environment (Proxy via Dubbo Admin HTTP API)
# ==============================================================================
env.prod.type=dubbo-admin
env.prod.admin_url=https://dubbo.yourcompany.com
env.prod.readonly=true
env.prod.data_masking=true

# Read-only method regex guard: when readonly=true, only matching methods are permitted
security.readonly.regex=^(get|query|find|select|list|count|check|is|search|fetch|read).*$

# Custom dubbo-admin context path (optional, e.g. /dubbo-admin)
# env.prod.admin_api_prefix=/dubbo-admin
```

---

## 🔐 Production Authentication (dubbo-admin)

Enterprise Dubbo Admin consoles are typically secured behind corporate Single Sign-On (SSO / OAuth). When configured with `env.prod.type=dubbo-admin`, save credentials once via terminal upon first run or after session expiry:

1. **Copy Session in Browser**: Log into your company's Dubbo Admin console, press <kbd>F12</kbd> to open Developer Tools Console, and run:
   ```javascript
   copy(JSON.stringify({
     cookie: document.cookie,
     authorization: localStorage.getItem('token') || localStorage.getItem('Admin-Token')
   }))
   ```
2. **Save Credentials in Terminal**: Run the following in your terminal (macOS `--clipboard` reads directly from the system clipboard to prevent terminal line buffer truncation):
   ```bash
   dubbo-mcp login --clipboard
   ```

Validated credentials are saved securely in `~/.dubbo-mcp/session-prod.json` (permissions `0600`). Running MCP instances automatically hot-reload renewed credentials on the next request without restarting your AI assistant.

---

## 🔌 Manual Agent Configuration

If you do not use `dubbo-mcp install` for automated setup, you can manually configure your tools:

### Codex
Add to `~/.codex/config.toml`:
```toml
[mcp_servers.dubbo]
command = "dubbo-mcp"
args = []
```

### Cursor
Add to `.cursor/mcp.json`:
```json
{
  "mcpServers": {
    "dubbo": {
      "command": "dubbo-mcp",
      "args": []
    }
  }
}
```

### Claude Code / ZCode
Add via Git repository:
```bash
/plugin marketplace add https://github.com/b1n9bna/dubbo-mcp.git
```
Or configure as a `stdio` command `dubbo-mcp`.

---

## 💬 Usage Examples

Once configured, interact with your microservices using plain natural language in your AI chat:

### Scenario 1: Troubleshooting Production Issues
> **Prompt**: *"Query order 2026103001 in production and analyze why it hasn't shipped yet."*  
> **AI Workflow**:
> 1. Calls `dubbo_invoke` (`env="prod"`, `interfaceName="com.corp.OrderQueryService"`, `method="getOrderDetail"`);
> 2. Dubbo-MCP proxies the call via Dubbo Admin and automatically masks sensitive fields (phone numbers, national IDs);
> 3. AI analyzes the returned JSON: *"The order status is PAID, but the logistics tracking number is null. The order is currently held in STOCKING status."*

### Scenario 2: Service & Interface Discovery
> **Prompt**: *"What Dubbo services related to 'user' are available in the test environment? List their methods and parameter structures."*  
> **AI Workflow**:
> 1. Calls `dubbo_list_services(keyword="user")` to retrieve matching interfaces;
> 2. Calls `dubbo_get_service_detail` to inspect method signatures and DTO properties;
> 3. Summarizes available methods for subsequent invocations.

### Scenario 3: Switching Environments Dynamically
> **Prompt**: *"Switch the active environment to UAT."*  
> **AI Workflow**:
> Calls `dubbo_switch_env(env="uat")`. Subsequent requests seamlessly route to the UAT cluster.

---

## 🛠️ MCP Tools Reference

| Tool Name | Parameters | Description |
| :--- | :--- | :--- |
| `dubbo_list_services` | `keyword` *(optional)* | Search registered Dubbo service interfaces on the registry |
| `dubbo_get_service_detail` | `interfaceName`, `version` *(opt)*, `group` *(opt)* | Retrieve method lists, signatures, DTO field structures, and provider details |
| `dubbo_invoke` | `interfaceName`, `method`, `args`, `env` *(opt)*, `version` *(opt)*, `group` *(opt)* | Invoke Dubbo RPC service with JSON arguments via generic invocation |
| `dubbo_list_envs` | *none* | List all configured environments and show the currently active profile |
| `dubbo_switch_env` | `env` | Switch the active default environment (`test` / `uat` / `prod`) |

---

## 🛡️ Enterprise Safety Controls

To safeguard production stability, two enterprise protective mechanisms are enforced:
1. **Read-Only Method Regex Guard**: In environments with `readonly=true`, only method names matching `security.readonly.regex` (e.g. `get*`, `query*`, `find*`, `select*`) are executed. Mutating operations (`update*`, `delete*`, `save*`) are blocked immediately;
2. **Sensitive Data Masking**: When `data_masking=true` is enabled, Chinese mobile numbers (`138****5678`) and 18-digit National IDs (`110101********2345`) are automatically masked in responses to prevent privacy data leakage into AI context windows.

---

## 🏗️ Architecture & How It Works

```
 ┌────────────────────────────────────────────────────────┐
 │   AI Coding Agents (Cursor / Claude Code / Codex CLI)   │
 └───────────────────────────┬────────────────────────────┘
                             │
                STDIO Transport (JSON-RPC 2.0)
                             │
 ┌───────────────────────────▼────────────────────────────┐
 │  1. Transport Layer: StdioJsonRpcServer (Pure Java 8)  │
 │     - Redirects logs to stderr; stdout pure JSON-RPC   │
 ├────────────────────────────────────────────────────────┤
 │  2. Security Guard & Two-Stage Scheduler Layer         │
 │     - On-demand: Discover -> Inspect Metadata -> Call  │
 │     - Production Protection: Read-Only Regex + Masking │
 ├────────────────────────────────────────────────────────┤
 │  3. Dual-Engine Driver Core                            │
 │     - Dev/Test: Direct ZooKeeper, native Generic RPC   │
 │     - Production: Proxy via Dubbo Admin HTTP Gateway   │
 └───────────────────────────┬────────────────────────────┘
              ┌──────────────┴──────────────┐
              ▼                             ▼
     [Dev/Test: ZooKeeper]       [Production: HTTP Relay]
      Dubbo Provider Nodes        Corporate Dubbo Admin
```

---

## 📊 Performance & Token Benchmark

<p align="center">
  <img src="assets/token-benchmark.svg?v=2" alt="Token Overhead Benchmark per Service Troubleshooting Session" width="880">
</p>

> **Empirical Result**: In a real service troubleshooting session, Dubbo-MCP's structured RPC invocation **saves ~98.6% context tokens** and **responds 30× faster** compared to browser-based automation agents, completely avoiding webpage DOM noise and cascading screenshot history overhead.

### 1. LLM Context Overhead Comparison (Single Troubleshooting Session)

| Solution | Interaction Model | Rounds | Total Context Tokens (Measured) | Latency | Determinism / Stability | Est. Cost per Session |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Dubbo-MCP** | Pure Structured JSON-RPC | **1 Round** | **572** | **< 1s** | **100%** (Direct RPC) | **~$0.001** |
| **DOM Browser Agent**<br>*(Playwright / Browser-Use)* | DOM Tree State Machine Loop | 5 Loops | **40,500** *(70.8×)* | ~28s | Susceptible to slow load/modal blocks | ~$0.12 |
| **Vision Browser Agent**<br>*(Computer Use / Screenshots)* | 1280×800 Screenshot Slices | 6 Rounds | **33,465** *(58.5×)* | ~35s | Risk of OCR / visual misclick | ~$0.10 |

<details>
<summary><b>Click to expand methodology and token calculation details</b></summary>

- **Tokenization Benchmark**: Measured with OpenAI `cl100k_base` BPE tokenizer, based on an actual production order troubleshooting workflow;
- **Dubbo-MCP**: `tools/list` schema (397 tokens) + single tool call request (51 tokens) + actual RPC response (90 tokens) + AI summary (34 tokens) = **572 tokens**;
- **Vision Browser Agent**: Anthropic official vision formula (1280×800 screenshot ≈ 1,365 tokens/image). Navigating Dubbo Admin (search -> click -> fill inputs -> submit -> view popup) required 6 screenshot rounds, calculated with compounding conversational history ($O(N^2)$);
- **DOM Browser Agent**: Dubbo Admin (Vue + Element UI) rendered accessibility DOM tree (~2,500 tokens/step), across 5 state-machine iterations with compounding history.
</details>

### 2. Local Process Resource Footprint

| Metric | Measured Value | Description |
| :--- | :--- | :--- |
| **Cold Start Latency** | **< 100 ms** | Ultra-lightweight process, instant on-demand startup |
| **Resident Memory (RSS)** | **~25 MB** | Minimal RAM overhead, runs in the background effortlessly |
| **Stream Isolation** | **Strictly Separated** | All library logging rerouted to `stderr`; `stdout` reserved for JSON-RPC |
| **Runtime Dependency** | **JRE 8+ Only** | Single shaded Fat-JAR, zero external dependencies required |

---

## ❓ FAQ

<details>
<summary><b>1. How are complex Java DTO arguments (nested objects, enums) handled?</b></summary>

Dubbo-MCP relies on native Dubbo Generic Invocation (`GenericService`):
* **Schema Introspection**: Calling `dubbo_get_service_detail` inspects the ZooKeeper metadata report to determine DTO properties (e.g. `current`, `size`, `calculationDateList`);
* **Automatic Type Matching**: The AI agent passes standard JSON maps in `args`. The underlying driver matches target DTO definitions and handles serialization automatically.
</details>

<details>
<summary><b>2. How is routing handled when multiple versions or groups exist?</b></summary>

* The driver automatically queries registered provider URLs. If an interface has only a single version and group, it is selected automatically;
* When multiple versions or groups exist, explicitly provide `version` or `group` parameters in `dubbo_invoke` for targeted routing.
</details>

<details>
<summary><b>3. How do I renew production credentials after session expiration?</b></summary>

If your corporate SSO session expires, requests return HTTP 401/403. Simply log into Dubbo Admin again in your browser, copy the credentials via Console, and re-run `dubbo-mcp login --clipboard`. The active MCP process hot-reloads the new credentials on the next invocation without needing a restart.
</details>

<details>
<summary><b>4. How do I initiate Dubbo debugging directly inside Cursor or Claude Code?</b></summary>

Running `dubbo-mcp install` automatically configures `~/.cursor/mcp.json`. In Cursor chat, type natural language prompts such as:
* *"Inspect the parameter structure of `cn.corp.trade.OrderRpcService` in production"*
* *"Invoke `UserServiceRpc.getUserById` in the test environment with userId=10001"*

Cursor automatically executes `dubbo_get_service_detail` to retrieve signatures and calls `dubbo_invoke` to return real-time JSON results without leaving your editor.
</details>

<details>
<summary><b>5. Why choose MCP over browser automation agents (Playwright / Browser-Use)?</b></summary>

* **98.6% Token Savings**: Real-world inspections require only **572 tokens** vs **33,000 ~ 40,000 tokens** for browser agents;
* **Sub-Second Latency**: Direct RPC responses avoid webpage rendering, DOM parsing, simulated typing, and screenshot latencies (which take 30~60s);
* **Deterministic & Safe**: Completely immune to frontend UI redesigns or popup misclicks, with strict read-only regex guards and automated sensitive data masking.
</details>

---

## 💻 Local Build & Test

```bash
# Run unit test suite
LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8 mvn test

# Build shaded Fat-JAR
LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8 mvn package -DskipTests
```
