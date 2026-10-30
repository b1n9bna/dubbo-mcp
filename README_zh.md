<p align="right">
  <b><a href="README.md">English</a></b> | <b>中文说明</b>
</p>

# Dubbo-MCP

<p align="center">
  <b>Enterprise Apache Dubbo RPC Bridge for Model Context Protocol (MCP) & AI Coding Agents</b><br>
  <i>Connect Cursor, Claude Desktop, Claude Code, Windsurf, and Codex directly to your Dubbo microservices.</i>
</p>

<p align="center">
  <a href="https://github.com/b1n9bna/dubbo-mcp/releases/latest"><img src="https://img.shields.io/github/v/release/b1n9bna/dubbo-mcp?style=flat-square&color=blue" alt="Latest Release"></a>
  <a href="https://github.com/b1n9bna/dubbo-mcp/actions/workflows/ci.yml"><img src="https://github.com/b1n9bna/dubbo-mcp/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <img src="https://img.shields.io/badge/Model_Context_Protocol-MCP-green?style=flat-square" alt="MCP Compatible">
  <a href="https://glama.ai/mcp/servers/b1n9bna/dubbo-mcp"><img src="https://glama.ai/mcp/servers/b1n9bna/dubbo-mcp/badges/score.svg" alt="Glama Score"></a>
  <img src="https://img.shields.io/badge/JDK-8_|_11_|_17_|_21-blue?style=flat-square&logo=openjdk" alt="JDK 8+">
  <img src="https://img.shields.io/badge/Cold_Start-<100_ms-brightgreen?style=flat-square&logo=lightning" alt="Cold Start">
  <img src="https://img.shields.io/badge/RAM_RSS-~25_MB-success?style=flat-square&logo=speedtest" alt="RAM RSS">
  <img src="https://img.shields.io/badge/Dubbo-2.7.x_|_3.x-orange?style=flat-square&logo=apache" alt="Dubbo Compatible">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache_2.0-blue.svg?style=flat-square" alt="License"></a>
</p>

Dubbo-MCP 是连接 AI 编程助手（Cursor、Claude Code、Claude Desktop、Codex CLI、Windsurf 等）与 Apache Dubbo 微服务的 [Model Context Protocol (MCP)](https://modelcontextprotocol.io/) 官方协议服务端。

在日常排查业务问题、接口联调与单元测试时，让 AI 助手直接检索微服务接口定义、反查复杂 DTO 入参结构并基于原生泛化调用（Generic Invocation）发起实时 RPC 调试，**无需手动编写测试用例，无需在本地工程引入任何业务 API 依赖 Jar 包**。

---

## 💡 实际交互体感 (Cursor / Claude Desktop)

告别在浏览器登录 Dubbo Admin 翻找接口、用 Postman 拼装参数、或者写一堆仅用于联调的临时单元测试，直接在对话框向 AI 发出指令：

> **开发者：**  
> *“帮我查一下测试环境的仓库接口，调一下按仓库 ID=2001 查询库存的方法。”*  
>  
> **AI Agent（通过 dubbo-mcp 自动协作）：**  
> 1. 🔍 `dubbo_list_services(keyword="Warehouse")`  
>    ↳ 发现目标服务：`cn.wangoon.access.api.rpc.WarehouseServiceRpc:1.0.0`  
> 2. 📋 `dubbo_get_service_detail(interfaceName="...")`  
>    ↳ 提取方法签名：`queryStock(cn.wangoon.access.dto.WarehouseQueryDTO)`  
> 3. ⚡ `dubbo_invoke(interfaceName="...", method="queryStock", args=[{"warehouseId": 2001}])`  
>    ↳ Dubbo 原生泛化调用执行完成（耗时 **14 ms**）  
>  
> **实时返回给大模型：**  
> ```json
> {
>   "code": 200,
>   "data": {
>     "warehouseId": 2001,
>     "warehouseName": "华东一号中心仓",
>     "availableStock": 1420,
>     "lockedStock": 35,
>     "status": "OPERATIONAL"
>   }
> }
> ```

---

## 🚀 快速安装

### 1. 一键脚本安装

**macOS / Linux：**
```bash
curl -fsSL https://raw.githubusercontent.com/b1n9bna/dubbo-mcp/main/install.sh | sh
```

**Windows (PowerShell)：**
```powershell
irm https://raw.githubusercontent.com/b1n9bna/dubbo-mcp/main/install.ps1 | iex
```

脚本会下载可执行文件至 `~/.dubbo-mcp/dubbo-mcp.jar`，并创建全局命令 `dubbo-mcp`。

*(也可通过本地源码编译：`mvn clean package -DskipTests`)*

### 2. 自动注入 AI 助手配置

安装后执行以下命令，可自动检测本机已安装的 Codex、Cursor、Claude 等工具并写入配置：
```bash
dubbo-mcp install
```

---

## ⚙️ 环境配置

配置文件路径：`~/.dubbo-mcp/config.properties`

```properties
# 默认激活的环境 (test / uat / prod)
active.env=test

# ==============================================================================
# 1. 开发/测试环境 (直连 ZooKeeper，泛化调用)
# ==============================================================================
env.test.type=zookeeper
env.test.registry=zookeeper://127.0.0.1:2181
env.test.timeout=5000
env.test.readonly=false

# ==============================================================================
# 2. 预发环境 (UAT)
# ==============================================================================
env.uat.type=zookeeper
env.uat.registry=zookeeper://10.20.1.15:2181
env.uat.timeout=5000
env.uat.readonly=true

# ==============================================================================
# 3. 生产环境 (对接 dubbo-admin HTTP API)
# ==============================================================================
env.prod.type=dubbo-admin
env.prod.admin_url=https://dubbo.yourcompany.com
env.prod.readonly=true
env.prod.data_masking=true

# 只读校验正则：开启 readonly=true 时，仅允许匹配该正则的方法名被执行
security.readonly.regex=^(get|query|find|select|list|count|check|is|search|fetch|read).*$

# 自定义 dubbo-admin 上下文路径（可选，如 /dubbo-admin）
# env.prod.admin_api_prefix=/dubbo-admin
```

---

## 🔐 生产环境登录授权 (dubbo-admin)

企业内部的 Dubbo Admin 通常接入了单点登录（SSO/OAuth）。若配置了 `env.prod.type=dubbo-admin`，首次使用或会话过期后，需在终端执行一次凭据保存：

1. **获取登录态**：在浏览器打开并登录 Dubbo Admin 页面，按 <kbd>F12</kbd> 打开控制台 Console，执行：
   ```javascript
   copy(JSON.stringify({
     cookie: document.cookie,
     authorization: localStorage.getItem('token') || localStorage.getItem('Admin-Token')
   }))
   ```
2. **终端保存凭据**：在本地终端直接执行（macOS 下 `--clipboard` 会自动从系统剪贴板读取，避免终端长文本截断）：
   ```bash
   dubbo-mcp login --clipboard
   ```

凭据通过验证后将保存在 `~/.dubbo-mcp/session-prod.json`（权限 `0600`）。MCP 进程调用时会自动读取该文件，会话过期后重新执行上述命令即可，无需重启 AI 助手。

---

## 🔌 客户端接入配置

若未使用 `dubbo-mcp install` 自动注入，也可手动在对应工具的配置文件中添加：

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
可通过 Git 仓库添加插件：
```bash
/plugin marketplace add https://github.com/b1n9bna/dubbo-mcp.git
```
或以 `stdio` 方式配置命令 `dubbo-mcp`。

---

## 💬 使用示例

配置完成后，可以在 AI 对话中直接用自然语言发起请求：

### 场景 1：排查业务问题
> **提问**：“生产环境查一下订单号 2026103001 的详情，分析为什么没发货？”  
> **AI 动作**：
> 1. 调用 `dubbo_invoke`（传入 `env="prod"`, `interfaceName="com.corp.OrderQueryService"`, `method="getOrderDetail"`）；
> 2. Dubbo-MCP 通过 dubbo-admin 接口代理发起查询，并自动脱敏手机号、身份证等敏感字段；
> 3. AI 根据返回结果分析：“订单当前状态为已支付（PAID），仓库物流单号为空，停留在配货状态（STOCKING）。”

### 场景 2：检索服务与接口定义
> **提问**：“测试环境有哪些与用户 (user) 相关的 Dubbo 服务？查看它们提供了哪些方法及参数结构。”  
> **AI 动作**：
> 1. 调用 `dubbo_list_services(keyword="user")` 获取匹配的接口列表；
> 2. 调用 `dubbo_get_service_detail` 反查接口下的方法签名及 DTO 入参字段；
> 3. 整理列出服务的方法结构供后续调用。

### 场景 3：多环境动态切换
> **提问**：“把当前环境切到 UAT 环境。”  
> **AI 动作**：
> 调用 `dubbo_switch_env(env="uat")`，后续请求无缝切换至预发集群。

---

## 🛠️ MCP 工具列表

| 工具名 | 参数 | 说明 |
| :--- | :--- | :--- |
| `dubbo_list_services` | `keyword` (可选) | 按关键字搜索 Dubbo 服务接口（支持双轨匹配：Java 接口包名 + 服务端 application 应用名） |
| `dubbo_get_service_detail` | `service` | 获取接口的方法列表、参数类型、DTO 字段属性、元数据可用性及版本/分组信息 |
| `dubbo_invoke` | `service`, `method`, `args`, `paramTypes` (可选), `attachments` (可选), `jsonPath` (可选), `env` (可选), `version` (可选), `group` (可选) | 发起接口调用，内置类型强转容错、隐式参数透传及可选的 JSONPath 字段投影 |
| `dubbo_list_envs` | 无 | 查看已配置的环境列表及当前激活的环境 |
| `dubbo_switch_env` | `env` | 切换当前默认环境（如 `test` / `uat` / `prod`） |

---

## 🛡️ 企业级生产健壮性与调用容错

为解决大模型在生产级企业微服务调用中的真实痛点，内置五大高阶保护机制：
1. **应用名双轨检索**：用户输入“查 oms 的订单”时，即使接口名为 `cn.wangoon.trade.OrderRpcService`，也能通过服务端 application 属性匹配并展示 `cn.wangoon.trade.OrderRpcService (app: oms-provider)`；
2. **雪花 ID 精度防丢失与类型强转**：支持将 18~19 位超长数值单号以字符串形式传入（避免 JavaScript 53 位浮点数溢出截断），底层自动强转为 Java `Long`/`BigDecimal`；
3. **隐式参数透传（`RpcContext` Attachments）**：支持在请求中或配置文件（`env.<env>.attachment.<key>`）中注入租户号 `tenantId`、链路追踪 `traceId` 及灰度标；
4. **大 JSON 上下文防打爆与 JSONPath**：返回报文超过 24KB 时自动进行结构化安全缩略，保留关键信封与字段，并支持通过 `jsonPath`（如 `$.data.items[0]`）进行精准投影；
5. **弱元数据集群反射自愈**：集群未开启元数据中心时，通过解析 Dubbo 服务端反馈的 `Available methods: [...]` 错误提示，指导大模型在下一轮对话中自动修正方法签名自愈调用；
6. **只读方法校验**：开启 `readonly=true` 的环境拦截非只读操作（如 `update*`、`delete*`）；
7. **敏感数据脱敏**：开启 `data_masking=true` 自动对手机号与身份证进行掩码脱敏。

---

## 🏗️ 架构与工作原理

```
 ┌────────────────────────────────────────────────────────┐
 │   AI 编程助手 (Codex / Cursor / Claude Code / ZCode)    │
 └───────────────────────────┬────────────────────────────┘
                             │
               STDIO 标准输入输出 (JSON-RPC 2.0)
                             │
 ┌───────────────────────────▼────────────────────────────┐
 │  1. Transport 层: StdioJsonRpcServer (纯 Java 8 实现)   │
 │     - 日志严格重定向至 stderr，保证 stdout 纯净用于通信    │
 ├────────────────────────────────────────────────────────┤
 │  2. 安全过滤与两阶段调度层                              │
 │     - 按需探测：搜索服务 -> 获取参数元数据 -> 发起调用     │
 │     - 生产环境安全保护：只读正则校验 + 敏感信息脱敏        │
 ├────────────────────────────────────────────────────────┤
 │  3. 双通道核心驱动 (Dual-Engine Driver)                 │
 │     - 测试/开发环境：直连 ZooKeeper，原生 RPC 泛化调用     │
 │     - 生产环境：通过 dubbo-admin HTTP 接口中转调用         │
 └───────────────────────────┬────────────────────────────┘
              ┌──────────────┴──────────────┐
              ▼                             ▼
   [开发/测试环境: ZooKeeper]        [生产环境: HTTP 中转]
     Dubbo Provider 节点           企业已部署的 Dubbo Admin
```

---

## 📊 性能与 Token 消耗实测

<p align="center">
  <img src="assets/token-benchmark.svg?v=2" alt="单次服务排查任务 Token 消耗实测对比" width="880">
</p>

> **实测结果**：在单次微服务接口排查任务中，Dubbo-MCP 的结构化调用相比传统浏览器 Agent 方案 **节省约 98% 上下文 Token**，**响应提速 30 倍以上**，且无网页 DOM 噪音与多轮截图历史累加的包袱。

### 1. 大模型交互开销对比（单次服务排查）

| 方案 | 交互方式 | 交互轮数 | 上下文总 Token (实测) | 耗时 | 确定性 / 稳定性 | 单次估算成本 |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Dubbo-MCP** | 纯结构化 JSON-RPC | **1 轮直达** | **572** | **< 1s** | **100%** (纯接口) | **~$0.001** (0.7分钱) |
| **DOM 浏览器 Agent**<br>*(Playwright / Browser-Use)* | 页面 DOM 树状态机循环 | 5 轮循环 | **40,500** *(70.8×)* | ~28s | 易受网络慢/遮罩层阻断 | ~$0.12 (8毛钱) |
| **视觉浏览器 Agent**<br>*(Computer Use / 截图)* | 1280×800 界面截图切片 | 6 轮截图 | **33,465** *(58.5×)* | ~35s | 存在图像识别误触概率 | ~$0.10 (7毛钱) |

<details>
<summary><b>点击查看测试方法与 Token 计量推导 (Methodology)</b></summary>

- **分词计量基准**：统一采用 OpenAI `cl100k_base` BPE 分词器编码，以真实生产订单详情排查为基准场景；
- **Dubbo-MCP 实测**：`tools/list` 基础 Schema（397 Token）+ 单次请求入参（51 Token）+ 接口返回真实数据（90 Token）+ AI 总结回答（34 Token）= **572 Token**；
- **视觉浏览器 Agent**：Anthropic 官方视觉公式（1280×800 截图每张约 1,365 Token），在 Dubbo Admin 完成“搜索 -> 点击 -> 填参 -> 提交 -> 查看弹窗”共 6 轮截图，按多轮对话上下文历史复合累加（$O(N^2)$）计算；
- **DOM 浏览器 Agent**：以 Dubbo Admin（Vue + Element UI）渲染后的页面可访问性树（单步约 2,500 Token），5 轮状态机历史复合累加计算。
</details>

### 2. 本地进程资源占用

| 指标 | 实测数值 | 说明 |
| :--- | :--- | :--- |
| **冷启动耗时** | **< 100 ms** | 进程轻量，随起随用 |
| **常驻内存 (RSS)** | **~25 MB** | 内存开销小，长时间运行无负担 |
| **标准流隔离** | **严格分离** | 日志重定向至 `stderr`，确保 `stdout` 仅用于 JSON-RPC 通信 |
| **运行依赖** | **仅需 JRE 8+** | 单个 Fat-JAR 运行，内置 Dubbo 泛化调用与 Curator |

---

## ❓ 常见问题

<details>
<summary><b>1. 复杂的 Java DTO 入参（嵌套对象、枚举）怎么传？</b></summary>

Dubbo-MCP 基于 Dubbo 原生泛化调用（Generic Invocation）：
* **字段结构感知**：调用 `dubbo_get_service_detail` 时，会从 ZooKeeper 元数据中心反查入参 DTO 的属性结构（如 `current`, `size`, `calculationDateList` 等）；
* **类型自动匹配**：AI 在 `dubbo_invoke` 的 `args` 中直接传入对应的 JSON Map 即可，底层会自动匹配目标 DTO 类型并完成序列化转换。
</details>

<details>
<summary><b>2. 多版本（version）或多分组（group）如何路由？</b></summary>

* 底层会自动从注册中心读取 Provider 列表，若该接口仅注册了单一版本或单一分组，会自动选用；
* 若存在多个不同版本或分组，可在 `dubbo_invoke` 中显式指定 `version` 或 `group` 参数进行精准路由。
</details>

<details>
<summary><b>3. 生产环境登录态过期后如何处理？</b></summary>

若企业 SSO 会话过期，接口调用会返回 401/403。此时只需在浏览器重新打开 Dubbo Admin 执行复制脚本，在终端重新执行 `dubbo-mcp login --clipboard` 即可。运行中的 MCP 进程会在下一次请求时自动热加载新凭据，无需重启 AI 助手。
</details>

<details>
<summary><b>4. 如何在 Cursor 或 Claude Code 中直接发起 Dubbo 接口联调？</b></summary>

执行 `dubbo-mcp install` 后，Cursor 会在 `~/.cursor/mcp.json` 中自动挂载服务。在 Cursor 对话框中直接输入自然语言，例如：
* *"帮我查一下生产环境订单接口 `cn.corp.trade.OrderRpcService` 的入参格式"*
* *"用测试环境调用 `UserServiceRpc.getUserById`，入参 userId=10001"*

Cursor 将自动调用 `dubbo_get_service_detail` 获取签名，并调用 `dubbo_invoke` 执行泛化调用返回实时 JSON，全程无需离开编辑器。
</details>

<details>
<summary><b>5. 相较于让浏览器 Agent 操作 Dubbo Admin 网页，为什么选择 MCP 协议？</b></summary>

* **Token 消耗直降 98.6%**：单次排查实测仅需 **572 Token**，而视觉或 DOM 浏览器 Agent 多轮对话会复合累加产生 **33,000 ~ 40,000 Token**（参见上方基准图表）；
* **毫秒级直接响应**：直连 RPC/API，避免了浏览器自动化中的网页加载、DOM 解析、模拟点击和截图延时（通常耗时 30~60 秒）；
* **确定性与安全性**：杜绝了前端页面改版、弹窗漏点或 OCR 识别失误，且具备严格的只读正则守卫与敏感字段脱敏。
</details>

<details>
<summary><b>6. 支持哪些注册中心？如果公司用 Nacos 而不是 ZooKeeper 可以使用吗？</b></summary>

* **Dubbo Admin 模式 (`type=dubbo-admin`)**：**完全原生支持，开箱即用。** Dubbo Admin 本身具备多注册中心聚合网关能力，其服务端原生对接 Nacos、ZooKeeper、Consul、Apollo 等。如果企业使用的是 Nacos + Dubbo Admin 架构，`dubbo-mcp` 无需额外配置即可直接检索与调用服务；
* **直连模式 (`type=zookeeper`)**：目前直连模式原生支持 ZooKeeper 集群。针对无需 Dubbo Admin 的纯 Nacos 客户端直连 Driver 正在规划实现中。
</details>

---

## 💻 本地构建与测试

```bash
# 运行单元测试
LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8 mvn test

# 打包可执行 Fat-JAR
LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8 mvn package -DskipTests
```
