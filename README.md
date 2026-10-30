# dubbo-mcp

连接 AI 编程助手（Codex、Cursor、Claude Code、ZCode 等）与 Dubbo 微服务的 [Model Context Protocol (MCP)](https://modelcontextprotocol.io/) 服务端。

在日常排查业务问题或联调时，让 AI 助手可以直接检索微服务、查看接口入参结构、并发起接口调用，无需手动编写测试代码或引入业务依赖 Jar 包。

---

## 功能概述

- **双通道调用**：
  - **开发/测试环境**：直连 ZooKeeper，通过 Dubbo 原生泛化调用（`GenericService`）发起 RPC 请求；
  - **生产环境**：桥接现有的 `dubbo-admin` 控制台 HTTP API，避免外部工具直连生产集群端口。
- **免引入接口依赖**：自动从注册中心识别服务版本（`version`）与分组（`group`），并从 ZooKeeper 元数据反查方法入参类型及 DTO 字段结构。
- **环境管理与安全控制**：
  - 支持多环境配置（`test` / `uat` / `prod`），可在对话中动态切换；
  - 生产环境支持只读方法正则拦截（默认仅允许查询操作）与敏感信息（手机号、身份证）脱敏。
- **轻量独立进程**：基于纯 Java 8 实现（无 Spring 框架依赖），启动快速，日志严格重定向至 `stderr`，确保 `stdout` 仅用于 MCP 的 JSON-RPC 通信。

---

## 快速安装

### 1. 一键脚本安装

**macOS / Linux：**
```bash
curl -fsSL https://raw.githubusercontent.com/b1n9bna/dubbo-mcp/main/install.sh | sh
```

**Windows (PowerShell)：**
```powershell
irm https://raw.githubusercontent.com/b1n9bna/dubbo-mcp/main/install.ps1 | iex
```

脚本会下载可执行文件至 `~/.dubbo-mcp/dubbo-mcp.jar`，并创建全局命令 `dubbo-mcp`。安装后可随时执行以下命令自动为本机已安装的 AI 工具写入配置：
```bash
dubbo-mcp install
```

*(也支持本地源码编译：`mvn clean package -DskipTests`)*

---

## 配置说明

配置文件路径：`~/.dubbo-mcp/config.properties`

```properties
# 默认激活的环境 (test / uat / prod)
active.env=test

# ==============================================================================
# 1. 测试环境 (直连 ZooKeeper)
# ==============================================================================
env.test.type=zookeeper
env.test.registry=zookeeper://127.0.0.1:2181
env.test.timeout=5000
env.test.readonly=false

# ==============================================================================
# 2. 生产环境 (对接 dubbo-admin)
# ==============================================================================
env.prod.type=dubbo-admin
env.prod.admin_url=https://dubbo.yourcompany.com
env.prod.readonly=true
env.prod.data_masking=true

# 只读规则：开启 readonly=true 时，仅允许匹配该正则的方法名被执行
security.readonly.regex=^(get|query|find|select|list|count|check|is|search|fetch|read).*$

# 自定义 dubbo-admin 上下文路径（可选，如 /dubbo-admin）
# env.prod.admin_api_prefix=/dubbo-admin
```

---

## 客户端接入示例

### Codex
在 `~/.codex/config.toml` 中添加：
```toml
[mcp_servers.dubbo]
command = "dubbo-mcp"
args = []
```

### Cursor
在 `.cursor/mcp.json` 中添加：
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
可通过 Git 仓库作为插件添加：
```bash
/plugin marketplace add https://github.com/b1n9bna/dubbo-mcp.git
```
或直接在 MCP 服务器配置中以 `stdio` 方式配置命令 `dubbo-mcp`。

---

## MCP 工具列表

| 工具名 | 说明 |
| :--- | :--- |
| `dubbo_list_services` | 按关键字搜索注册中心上的 Dubbo 服务接口 |
| `dubbo_get_service_detail` | 获取接口下的方法名、参数类型、DTO 字段结构及版本/分组信息 |
| `dubbo_invoke` | 执行接口调用，传入 JSON 入参，返回服务端的执行结果 |
| `dubbo_list_envs` | 查看已配置的环境列表及当前激活的环境 |
| `dubbo_switch_env` | 切换当前默认环境（如 `test` / `prod`） |

---

## 常见问题与说明

### 1. 生产环境（dubbo-admin）如何处理 SSO 登录？
若企业内部的 Dubbo Admin 接入了单点登录（飞书、企业微信、钉钉等）：
1. 在浏览器中打开 Dubbo Admin 完成登录；
2. 按 `F12` 打开控制台，执行以下代码复制凭据：
   ```javascript
   copy(JSON.stringify({cookie: document.cookie, authorization: localStorage.getItem('token')}))
   ```
3. 在本地终端执行：
   ```bash
   dubbo-mcp login --env=prod --clipboard
   ```
   CLI 会验证凭据有效性并保存至 `~/.dubbo-mcp/session-prod.json`（POSIX 权限 `0600`）。MCP 服务在调用时会自动热读取该文件，会话过期后重新执行上述命令即可，无需重启 AI 助手。

### 2. 带版本（version）或分组（group）的接口如何调用？
通常情况下无需手动配置：
- 发起调用前，底层会自动读取注册中心的 Provider 信息并匹配已注册版本（如 `1.0.0`）；
- 若同接口存在特定业务分组（如 `group=JF`）和默认分组，未传 group 时优先路由到默认分组；如需指定特定分组，可在 `dubbo_invoke` 中显式传入 `group` 或 `version` 参数。

### 3. 复杂 DTO 入参怎么传？
Dubbo 泛化调用支持将 JSON Map 映射为 Java 对象：
- 先调用 `dubbo_get_service_detail` 查看方法所需的 DTO 字段属性（如 `current`, `size`, `calculationDateList` 等）；
- 在 `dubbo_invoke` 的 `args` 中直接传入对应字段的 JSON 对象即可，底层会自动匹配真实类名并完成转换。

---

## 本地开发与构建

```bash
# 运行单元测试
LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8 mvn test

# 打包 Fat-JAR
LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8 mvn package -DskipTests
```
