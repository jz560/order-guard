# 订单异常诊断与受控处置 Agent

面向订单运营人员的个人演示项目。第一版只处理“支付成功但订单仍待支付”。模型提出建议，审批后的 Java 服务执行并验证结果。订单、支付和回调均为模拟数据，不接入真实资金。

当前实现 **T01 工程基础**：两个 Spring Boot 服务、独立数据库与账号、Flyway、默认模型替身，以及 Vue 环境状态页。诊断、业务工具、任务、审批、订单修复和演示场景尚未实现。验收记录见 [T01](docs/tasks/T01.md)。

## 环境

- Java 21，设置 `JAVA_HOME`，并将其 `bin` 加入 PATH。
- Node.js 22.13+（22.x）或 24+（本次使用 Node 24）、npm。
- Docker Engine / Docker Desktop，切换到 Linux 容器。集成测试需要可用 Docker。
- 首次构建需要访问 Maven Central、npm 和 Docker Hub。
- 无需全局 Maven，仓库提供 Maven Wrapper 3.9.11，附分发包 SHA-256 校验。
- 默认 `MODEL_MODE=stub`，不需要模型密钥，不发送模型网络请求。T01 对其他模式拒绝启动，真实接入属于 T02。

## 启动与停止

在项目根目录执行 PowerShell：

```powershell
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
docker compose config --quiet
docker compose up -d --build
docker compose ps
Invoke-RestMethod http://localhost:8080/actuator/health
Invoke-RestMethod http://localhost:8081/actuator/health
```

两个健康接口都应返回 `status: UP`，数据库不可用时不会报告健康。当前仅开放健康检查，业务及其他管理路径默认拒绝访问；预置业务账号在 T06 实现。

本机 5432 端口不可绑定时，将 `.env` 的 `POSTGRES_PORT` 改为 `15432`，再执行启动命令。后端和模拟服务默认端口分别为 8080、8081，可通过 `.env` 的 `BACKEND_PORT`、`SIMULATOR_PORT` 修改。对外端口只绑定回环地址。

```powershell
docker compose logs --tail=200 backend simulator
docker compose down
```

停止保留数据库卷。初始化脚本只在空卷执行；修改 `.env` 密码不会自动修改已有数据库角色密码。不要为了修复配置错误直接删除数据卷。

前端在独立终端启动：

```powershell
cd frontend
npm ci
npm run dev
```

打开终端给出的本地地址。页面显示两个服务的实际健康状态；连接失败显示“暂不可用”。开发代理默认连接 8080 和 8081；服务端口变化时，在启动 Vite 前设置 `BACKEND_PROXY_URL`、`SIMULATOR_PROXY_URL`。这些地址仅供开发代理使用，不包含凭证。T01 前端暂不加入 Compose；`npm run preview` 仅预览静态构建，不提供开发健康代理。

## 构建与检查

根目录后端命令（Linux/macOS 将 `./mvnw.cmd` 换成 `./mvnw`）：

```powershell
./mvnw.cmd -B -ntp compile
./mvnw.cmd -B -ntp test
./mvnw.cmd -B -ntp verify
./mvnw.cmd -B -ntp spotless:check
./mvnw.cmd -B -ntp spotless:apply
```

`test` 执行 `*Test`；`verify` 同时执行 `*IT`、打包和格式检查。集成测试通过 Testcontainers 创建临时真实 PostgreSQL，验证空库迁移、重启不重建数据、账号隔离、服务启动与 HTTP 健康检查。无 Docker 时失败，不静默跳过。不使用 H2。

```powershell
cd frontend
npm ci
npm run lint
npm run typecheck
npm run test:unit
npm run build
```

也可以在根目录执行 `./scripts/verify.ps1`，统一运行后端完整检查和上述前端检查。脚本遇到失败立即退出。

容器构建阶段跳过测试只用于生成镜像，**不能作为验证通过的证据**；交付前仍须运行 `verify`。

如需在 IDE 中启动 Java 服务，先仅启动数据库，然后为每个进程分别设置环境变量：

| 服务 | 入口类 | 必需环境变量 |
| --- | --- | --- |
| backend | `com.example.orderagent.backend.BackendApplication` | `BACKEND_DB_PASSWORD`，非默认数据库端口时设置 `BACKEND_DB_URL` |
| simulator | `com.example.orderagent.simulator.SimulatorApplication` | `SIMULATOR_DB_PASSWORD`，非默认数据库端口时设置 `SIMULATOR_DB_URL` |

JDBC URL 示例：`jdbc:postgresql://localhost:15432/agent_app` 和 `jdbc:postgresql://localhost:15432/agent_simulator`。密码取自本机 `.env`；Spring Boot 不会自行读取此文件。默认数据库账号分别是 `backend_app` 和 `simulator_app`。

## 工程结构与边界

```text
backend/         业务服务基础、模型调用接口与替身
simulator/       独立模拟服务基础
frontend/        Vue + TypeScript 环境状态页
infra/           数据库初始化、容器健康探针
scripts/         统一验证入口
docs/tasks/      任务与实测记录
docs/decisions/  设计决策
文档/            原始需求、设计、图示和开发规范
```

两套数据库角色没有超级用户、建库、建角色权限；数据库及 public schema 撤销 PUBLIC 默认访问权限。各服务仅获得自身数据库凭证，模拟数据未来通过 HTTP 获取。Flyway V1 仅创建服务归属标记，业务表随对应任务逐步增加。已经执行的迁移不得原地修改。

第一版金额使用整数最小货币单位，不支持多币种业务、部分支付、退款或撤销支付；当前场景的支付成功事实不逆转。未来真实支付系统接入需重新设计事实核验协议。

T01 不包含真实模型评测、业务执行可靠性证明或生产收益。后续按 T02—T16 推进，权限、审批、幂等、未知结果处理和恢复机制均需独立验收。
