<p align="center">
  <h1 align="center">🧭 Dubbo-MCP</h1>
  <p align="center">
    <strong>企业级 Dubbo RPC & HTTP 微服务的大模型 (Model Context Protocol) 运行时底座</strong><br>
    专为 Codex (CLI / Desktop / IDEA)、Cursor 与 Claude Code 设计 · 纯 JDK 8 · 0% Spring 臃肿 · 毫秒级秒起 · 免 Jar 包泛化调用
  </p>
  <p align="center">
    <img src="https://img.shields.io/badge/JDK-8_|_11_|_17_|_21-blue?style=flat-square&logo=openjdk" alt="JDK 8+">
    <img src="https://img.shields.io/badge/Spring_Boot-0%25_(Pure_Java)-success?style=flat-square&logo=spring" alt="Zero Spring">
    <img src="https://img.shields.io/badge/Dubbo-2.7.x_|_3.x_Compatible-orange?style=flat-square&logo=apache" alt="Dubbo Compatible">
    <img src="https://img.shields.io/badge/Cold_Start-<100_ms-brightgreen?style=flat-square&logo=lightning" alt="Cold Start">
    <img src="https://img.shields.io/badge/Target_Client-Codex_|_Cursor_|_Claude-purple?style=flat-square" alt="AI Clients">
    <img src="https://img.shields.io/badge/License-Apache_2.0-blue.svg?style=flat-square" alt="License">
  </p>
</p>

---

## 💡 为什么需要 Dubbo-MCP？

在 AI 编程大模型普及的今天，所有的 Coding Agent（如 Codex CLI、Cursor、Claude Code）都被困在本地沙箱里。

当开发和排查业务问题时，我们最常对 AI 说的一句话是：
> **“帮我查一下生产订单号 2026103001 为什么没发货？”**

然而，目前的现实痛点是：
1. **海外开源生态完全脱节**：市面上的 MCP 全是 PostgreSQL、Slack、GitHub，根本不懂中国庞大的 **Java + Dubbo + ZooKeeper/Nacos** 内部微服务资产；
2. **直接查库（Text-to-SQL）不可行**：绕过业务逻辑校验、无法跨库关联、缺乏数据脱敏，甚至存在线上删库的高危风险；
3. **生产网络隔离死穴**：测试环境在内网，生产环境在外网甚至隔着堡垒机与防火墙，外部工具根本不可能直接连上生产的 ZooKeeper 端口和 Dubbo 端口。

**Dubbo-MCP 就是为打破这一僵局而生的企业级“大模型 RPC 转接头”：**
- **测试环境**：直连内网 ZooKeeper，走纯 Java 泛化调用，亚毫秒级响应；
- **生产环境**：直接桥接企业已有的 **`dubbo-admin` 控制台**，零网络申请、零防火墙门槛，合规穿透内网；
- **极度轻量克制**：基于纯 **JDK 8** 编写，**0% Spring 依赖**，启动 `< 100ms`，内存仅占用约 25MB，STDIO 标准流绝对隔离，绝不污染日志导致 Codex 崩溃。

---

## 🏗️ 架构全景与工作原理

```
 ┌────────────────────────────────────────────────────────┐
 │   AI Clients (Codex CLI / Codex Desktop / Cursor)      │
 └───────────────────────────┬────────────────────────────┘
                             │
               STDIO 标准输入输出 (JSON-RPC 2.0)
                             │
 ┌───────────────────────────▼────────────────────────────┐
 │  1. Transport 层: StdioJsonRpcServer (纯 Java 8 实现)   │
 │     - 严格隔离第三方日志到 System.err，杜绝 Codex 解析崩溃 │
 ├────────────────────────────────────────────────────────┤
 │  2. 安全守卫与两阶段调度层                              │
 │     - 两阶段动态按需探测 (搜索 -> 查元数据 -> 执行调用)      │
 │     - 生产环境只读硬核锁定 (拦截非查询写操作) + 数据脱敏     │
 ├────────────────────────────────────────────────────────┤
 │  3. 核心驱动层 (Dual-Engine Driver)                     │
 │     - 测试环境: DubboDirectDriver (纯编程式泛化调用 + 缓存) │
 │     - 生产环境: DubboAdminDriver (HTTP 桥接已有 dubbo-admin)│
 └───────────────────────────┬────────────────────────────┘
              ┌──────────────┴──────────────┐
              ▼                             ▼
   [测试内网: 纯 RPC 泛化调用]       [生产环境: HTTP 桥接]
   ZooKeeper + Dubbo Pods           企业已有 Dubbo-Admin 控制台
```

---

## ⚡ 极速安装与配置（像 CodeGraph 一样简单）

### 步骤 1：终端一行命令安装

**macOS / Linux 用户：**
```bash
curl -fsSL https://raw.githubusercontent.com/b1n9bna/dubbo-mcp/main/install.sh | sh
```

**Windows (PowerShell) 用户：**
```powershell
irm https://raw.githubusercontent.com/b1n9bna/dubbo-mcp/main/install.ps1 | iex
```

*脚本会自动完成以下动作：*
1. 下载可执行核心至 `~/.dubbo-mcp/dubbo-mcp.jar`；
2. 注册系统全局命令 `dubbo-mcp`（从此无需敲冗长的 `java -jar ...`）；
3. **自动扫描并一键配置本机的全部 AI 助手**（Codex、Cursor、Claude Desktop）！

---

### 步骤 2：自动连接你的 AI 编程助手（一键多端生效）

安装脚本会自动触发检测。如果你后续新安装了 AI 工具，随时只需敲一行：
```bash
dubbo-mcp install
```

终端将自动探测并完成注入，完全无需手动翻找深层配置文件：
```
🚀 [Dubbo-MCP] 正在自动检测并配置本机的 AI Coding Agents...

✅ Codex: 成功配置 ~/.codex/config.toml
✅ Claude Desktop: 成功配置 ~/Library/Application Support/Claude/claude_desktop_config.json
✅ Cursor: 成功配置 ~/.cursor/mcp.json

==================================================================
🎉 [Dubbo-MCP] 配置完成！请重启您的 AI 编程工具即可开始使用。
==================================================================
```

*(如果你更习惯源码构建，也可执行 `mvn clean package -DskipTests` 后使用 `target/dubbo-mcp-1.0.0-SNAPSHOT.jar install`)*

---

## ⚙️ 要配置什么？配置文件详解

在第一次运行后，或手动创建本地用户配置文件：  
📁 **`~/.dubbo-mcp/config.properties`**

你可以自由配置**多套环境矩阵（test / uat / prod）**：

```properties
# 当前默认激活的环境 (test / uat / prod)
active.env=test

# ==============================================================================
# 1. 开发/测试环境 (直接连公司内网 ZooKeeper，纯 RPC 泛化调用，极致性能)
# ==============================================================================
env.test.type=zookeeper
env.test.registry=zookeeper://192.168.1.10:2181
env.test.timeout=5000
env.test.readonly=false                       # 测试环境允许调写接口造数据

# ==============================================================================
# 2. 预发环境 (UAT / Staging)
# ==============================================================================
env.uat.type=zookeeper
env.uat.registry=zookeeper://10.20.1.15:2181
env.uat.timeout=5000
env.uat.readonly=true

# ==============================================================================
# 3. 生产环境 (直接桥接公司已有的 dubbo-admin 控制台，免防火墙、安全合规)
# ==============================================================================
env.prod.type=dubbo-admin
env.prod.admin_url=https://dubbo-admin.yourcompany.com
env.prod.readonly=true                       # 生产强行锁死只读保护！
env.prod.data_masking=true                   # 自动对返回的手机号、身份证脱敏打码

# ==============================================================================
# 4. 安全风控守卫 (Security Guards)
# ==============================================================================
# 当环境为 readonly=true 时，仅允许匹配该正则的方法名被执行，其余写操作硬阻断
security.readonly.regex=^(get|query|find|select|list|count|check|is|search|fetch|read).*$

# 本地回环登录服务端口
auth.callback.port=20888
```

---

## 🔐 核心攻坚：单点登录 (SSO) 到底怎么办？

各大企业内部部署的 `dubbo-admin` 几乎 100% 接入了企业的 **SSO 单点登录（飞书扫码、企业微信扫码、钉钉、CAS、OAuth2、动态口令）**。

> **核心原则**：不要在命令行里去痛苦地抓包或硬编码复杂的 SSO 扫码协议。**让用户在最熟悉的浏览器里完成扫码登录，由 Dubbo-MCP 负责把会话优雅地同步过来并自动保活。**

Dubbo-MCP 提供三种梯级方案：

### 方案 A：浏览器一键回环授权（最推荐，类似 Google 登录体验）

当你在 Codex 中第一次向生产环境发起查询时：
1. **自动唤起浏览器**：终端检测到未登录，自动打开你的默认浏览器；
2. **完成日常登录**：浏览器打开公司的 `dubbo-admin`，你正常使用飞书/企业微信扫码登录；
3. **一键同步会话**：
   - 将授权页面的 **【📌 同步至 Dubbo-MCP】** 按钮拖拽到浏览器收藏夹；
   - 在已登录的 `dubbo-admin` 页面**随手点一下该书签**，页面弹出 *“✅ 登录态已成功同步”*；
4. **后台自动心跳保活**：
   - 底座每 5 分钟向 `dubbo-admin` 发送一次轻量探活请求，维持会话长期有效，**全天无需重复登录**。

---

### 方案 B：静态 Cookie / cURL 粘贴（极客与内网限制模式）

如果企业浏览器开启了极严的跨域策略：
1. 打开浏览器登录 `dubbo-admin`，按 `F12` 打开控制台；
2. 在 Network 界面任意一个成功的接口请求上右键 -> **Copy as cURL**；
3. 直接将里面的 Cookie 粘贴到 `~/.dubbo-mcp/config.properties`：
   ```properties
   env.prod.admin_cookie=SESSION=xxxxxx; JSESSIONID=yyyyyy;
   ```

---

### 方案 C：原生账密全自动托管（0 门槛）

如果你们的 `dubbo-admin` 是标准的账号密码登录（非 SSO 扫码）：
在配置文件填入账号密码，底座启动时全自动调用登录接口换取 Token，**全程 100% 静默无感**：
```properties
env.prod.admin_username=your_username
env.prod.admin_password=your_password
```

---

## 💬 真实使用演示（在 Codex 中怎么用？）

配置完成后，打开 Codex / Cursor，你可以像日常和同事对话一样使用自然语言：

### 场景 1：排查线上业务状态（自动触发生产桥接）
> **你**：*“在生产环境查一下订单号 2026103001 的详情，分析为什么没发货？”*  
> **Codex**：
> 1. 自动调用 `dubbo_invoke`（传入 `env="prod"`、`interfaceName="com.corp.OrderQueryService"`、`method="getOrderDetail"`）；
> 2. Dubbo-MCP 带着安全 Cookie 请求生产 `dubbo-admin` 并对手机号自动脱敏；
> 3. Codex 解析返回的 JSON 回答：  
>    *“根据查询结果，订单当前状态为已支付（PAID），但仓库物流单号为空，停留在配货状态（STOCKING），因此尚未发货。”*

---

### 场景 2：探索内网服务与方法
> **你**：*“内网测试环境有哪些与‘用户 (user)’相关的 Dubbo 服务？查看它们提供了哪些方法。”*  
> **Codex**：
> 1. 调用 `dubbo_list_services(keyword="user")`，返回相关接口；
> 2. 调用 `dubbo_get_service_detail` 获取接口下的全部方法签名；
> 3. 清晰列出用户相关的服务列表供你选择。

---

### 场景 3：跨环境动态秒切
> **你**：*“把当前环境切到 UAT 环境，接下来帮我做联调。”*  
> **Codex**：
> 自动调用 `dubbo_switch_env(env="uat")`，后续所有 RPC 查询无缝走预发集群。

---

## 🛡️ 生产安全与防手滑铁律

针对生产环境调用，Dubbo-MCP 设置了**硬核三重防线**：
1. **只读方法正则拦截**：
   在配置了 `readonly=true` 的环境（如 prod），底层在发包前严格校验方法名前缀。
   - 允许：`get*`, `query*`, `find*`, `select*`, `list*`, `count*`, `is*` 等只读前缀；
   - 阻断：`save*`, `update*`, `delete*`, `remove*`, `create*` 等可能修改数据的操作，直接阻断并报错，防止手滑搞坏线上数据。
2. **敏感隐私自动打码**：
   开启 `data_masking=true` 后，底座内置针对中国大陆手机号（`138****5678`）和 18 位身份证（`110101********2345`）的正则过滤，防止用户数据泄漏到对话上下文。

---

## ❓ 常见问题 (FAQ)

<details>
<summary><b>Q1: 为什么不用 Spring Boot，而是用纯 Java 8？</b></summary>
MCP 依赖进程的标准输入输出（STDIO）进行通信。Spring Boot 启动时默认打印绿色 Banner 和大量的启动日志，会直接污染 stdout 导致 Codex 解析 JSON 崩溃；此外 Spring Boot 冷启动慢（3~8秒）且占用 300MB+ 内存。纯 Java 8 实现了绝对流隔离，启动仅需 100 毫秒，内存仅 25MB。
</details>

<details>
<summary><b>Q2: 复杂的 Java DTO 入参（枚举、嵌套对象）不需要引 Jar 包吗？参数类型怎么填？</b></summary>
完全不需要！Dubbo-MCP 基于 Dubbo 原生的<strong>泛化调用 (Generic Invocation)</strong>，并内置了<strong>元数据自适应引擎</strong>：
1. <strong>全自动类型反查</strong>：Dubbo-MCP 会直接从 ZooKeeper 元数据中心读取接口定义的真实 DTO 类名（如 <code>ClearancePriceInfoQueryDTO</code>），AI Agent 调用时即使不指定 <code>paramTypes</code>，底层也会自动匹配方法签名，杜绝 <code>NoSuchMethodException</code>。
2. <strong>字段结构透出</strong>：调用 <code>dubbo_get_service_detail</code> 时，不仅能看到方法，还会直接返回入参 DTO 的全部字段属性（如 <code>current, size, calculationDateList</code>），AI 助手无需查看 Java 源码即可直接准确构造入参！
3. <strong>版本与分组自适应</strong>：自动探测注册中心上的 <code>version</code>（如 <code>1.0.0</code>）与 <code>group</code>（如 <code>JF</code>），零配置即可精准路由到对应 Provider。
</details>

<details>
<summary><b>Q3: 提示 "Session expired" 怎么办？</b></summary>
在浏览器里重新打开 `dubbo-admin` 页面，随手点一下书签栏的【📌 同步至 Dubbo-MCP】，0.1 秒即可恢复授权。
</details>

---

## 🤝 参与贡献与扩展点 (SPI)

Dubbo-MCP 设计了清晰的扩展接口，欢迎社区提交 PR：
* `RpcDriver`：扩展自定义 RPC 协议（如 gRPC、Dubbo 3.x Triple、SOFARPC）；
* `AuthStrategy`：扩展定制企业专用 SSO 策略；
* `SecurityFilter`：扩展企业自研风控与脱敏审计链。

**如果对你的日常工作有帮助，欢迎点一个 Star ⭐️ 支持！**
