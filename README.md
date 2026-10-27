# 订单异常诊断与受控处置 Agent

面向订单运营人员的个人演示项目。第一版只处理“支付成功但订单仍待支付”。模型提出建议，审批后的 Java 服务执行并验证结果。订单、支付和回调均为模拟数据，不接入真实资金。

当前包含 **T01 工程基础**、**T02 模型接入实验**、**T03 初步价值对照实验**、**T04 幂等与真实进程恢复实验**、**T05 业务模拟场景**、**T06 任务创建与查询**、**T07 后台任务推进**、**T08 方案审批**和 **T09 受控执行与结果确认**：两个 Spring Boot 服务、独立数据库与账号、Vue 环境状态页、只读模型对照、六种业务场景，以及带权限、订单限制、证据、审计和租约 Worker 的任务 API。Worker 当前根据订单快照生成冻结的固定测试方案，可通过接口批准或拒绝。批准后由 Worker 复核、执行并验证业务结果；超时及重启后查询原操作。真实诊断与任务前端页面尚未接入。验收记录见 [T01](docs/tasks/T01.md)、[T02](docs/tasks/T02.md)、[T03](docs/tasks/T03.md)、[T04](docs/tasks/T04.md)、[T05](docs/tasks/T05.md)、[T06](docs/tasks/T06.md)、[T07](docs/tasks/T07.md)、[T08](docs/tasks/T08.md)、[T09](docs/tasks/T09.md)。审批及执行体验见 [方案审批](docs/api/plans.md)、[受控执行](docs/api/execution.md)。

## 环境

- Java 21，设置 `JAVA_HOME`，并将其 `bin` 加入 PATH。
- Node.js 22.13+（22.x）或 24+（本次使用 Node 24）、npm。
- Docker Engine / Docker Desktop，切换到 Linux 容器。集成测试需要可用 Docker。
- 首次构建需要访问 Maven Central、npm 和 Docker Hub。
- 无需全局 Maven，仓库提供 Maven Wrapper 3.9.11，附分发包 SHA-256 校验。
- 默认 `MODEL_MODE=stub`，不需要模型密钥，不发送模型网络请求。`MODEL_MODE=deepseek` 时需要 `DEEPSEEK_API_KEY`；服务启动本身不发起模型请求。

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

两个健康接口都应返回 `status: UP`，数据库不可用时不会报告健康。健康检查匿名可读；模拟业务接口须使用读/执行/演示管理三类凭证，其他管理路径拒绝访问。已有 `.env` 请按 `.env.example` 补充 T05 的 `SIMULATOR_*_TOKEN` 和 T06 的 `DEMO_OPERATOR_PASSWORD`、`DEMO_APPROVER_PASSWORD`，空值禁用对应访问能力。后端任务接口使用预置账号认证及 CSRF 防护。

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

`test` 执行 `*Test`；`verify` 同时执行 `*IT`、打包和格式检查。集成测试通过 Testcontainers 创建临时真实 PostgreSQL，验证空库迁移、重启不重建数据、账号隔离、服务启动、HTTP 健康检查及 T04 故障恢复。请从根目录聚合构建，T04 的独立 Java 进程使用本轮打包的两个 JAR。无 Docker 时失败，不静默跳过。不使用 H2。

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

### T02 模型接入实验

此实验由命令行显式启动，不开放未认证的诊断 API。四个工具只读取固定模拟订单 `T02-ORDER-001` 的订单、支付、回调和历史记录；不访问模拟服务数据库，不产生业务写操作。

```powershell
# 无密钥、无模型费用：脚本替身完成两轮工具往返，验证运行入口
./scripts/t02-probe.ps1 -Mode stub

# 真实调用：先在本机 .env 配置 DEEPSEEK_API_KEY，或通过进程环境变量注入
# 每次实验最多 4 次模型请求、12 次工具调用、每次最多输出 1000 token，不自动重试
$env:T02_ALLOW_LIVE='true'
./scripts/t02-probe.ps1 -Mode deepseek
Remove-Item Env:T02_ALLOW_LIVE
```

真实适配器固定连接 DeepSeek 官方地址，模型为 `deepseek-flash`、非思考模式。凭证不传入前端。`.env` 只由实验脚本读取指定密钥；IDE 启动服务仍需环境变量。实验运行记录逐条写入忽略目录 `.cache/t02-runs/<runId>.jsonl`，包括模型轮次、工具意图与结果、用量、耗时和停止原因。记录不包含认证头、密钥或模型内部推理。

结构化输出只验证建议类型、字段与证据引用；不表示建议在业务上必然正确，也不创建审批或可执行方案。默认单次模型时限 30 秒、总时限 120 秒；超时后停止后续调用，但远端可能已经计费，用量视为未知。T02 不包含进程恢复或持久化任务循环，这些属于 T11。

### T04 幂等与真实进程恢复实验

此实验不调用模型。调用方先在自己的数据库保存操作 ID 和固定参数，再通过 HTTP 请求独立下游。下游在一个 PostgreSQL 事务内保存订单变更和操作结果。响应丢失或调用进程退出后，新进程沿用原操作 ID 查询，并核验订单现状；结果未知时不会自动重发写请求。

```powershell
# Java 21 + 可用 Docker；自动创建并回收独立临时容器
./scripts/t04-verify.ps1
```

默认运行镜像为 `eclipse-temurin:21.0.8_9-jdk-jammy`。可用 `T04_JAVA_IMAGE` 指定已有的 Java 21 JDK 运行镜像，例如本机 T01 的 `order-agent-backend:0.1.0`；测试始终复制本轮新 JAR、覆盖入口运行，不复用镜像里的旧代码。实验记录目录由脚本输出，原始测试报告在 `simulator/target/failsafe-reports/`。详细场景与保存的实测证据见 [T04](docs/tasks/T04.md)。

实验入口仅允许显式的 `T04_MODE=demo/test`，采用临时读写分离凭证与各服务独立数据库账号。使用专用 `t04_probe` schema，不改变普通服务的安全配置、默认迁移或现有数据。T05 的常驻修复接口独立于此实验；上游审批在 T08 实现。

### T05 业务模拟场景

模拟服务提供订单、支付、回调查询，以及按操作 ID 幂等的受控修复与结果查询。demo/test 首次启动预置回调失败、正常延迟、金额冲突、证据缺失、已修复、订单关闭六种场景。普通启动保留数据；管理接口可重置某个场景，新建订单并保留旧操作，避免旧请求作用于新一轮场景。

`SIMULATOR_READ_TOKEN` 仅允许读取；`SIMULATOR_EXECUTE_TOKEN` 用于可信执行端；`SIMULATOR_ADMIN_TOKEN` 仅管理演示场景。三个值至少 16 字符且不能相同，均为空时健康检查仍可启动。Compose 的 `SIMULATOR_PROFILE=demo` 默认启用演示；非 demo/test 禁用管理和故障（包括历史残留故障）。此接口不代替后续上游审批检查。

故障支持一次提交后响应延迟和操作结果查询不可用。完整请求、错误语义和 PowerShell 演示见 [模拟接口说明](docs/api/simulator.md)，设计取舍见 [T05 决策](docs/decisions/005-t05-business-simulator.md)。正常延迟是证据快照，不运行自动回调调度。所有资金数据均为模拟。

```powershell
# 真实 PostgreSQL + HTTP 验证 T05，同时执行模拟服务单元测试
./mvnw.cmd -B -ntp -pl simulator '-Dit.test=BusinessSimulationIT,FoundationIT' verify
# 完整项目与 T04 回归仍从根目录执行
./mvnw.cmd -B -ntp verify
```

### T06 任务创建与查询

预置 `operator`（创建与查询）和 `approver`（查询）账号，只在 demo/test 启用。密码通过环境变量注入，默认订单权限范围为 `T05-ORDER-`。使用 HTTP Basic 建立 Spring 会话，通过 `/api/auth/csrf` 获取写请求令牌；完整 PowerShell 步骤见 [任务 API](docs/api/tasks.md)。

创建任务只读调用模拟订单接口，然后在同一数据库事务中保存 `QUEUED` 任务、订单限制、初始证据、幂等键和审计。并发/重复创建返回原任务；同一账号用相同幂等键提交不同订单返回 409。查询读取持久化记录；T07 增加测试步骤后台推进，任务页面属于 T12。

```powershell
# 从根目录打包两个服务，再验证跨服务 HTTP、真实数据库、并发和独立进程重启
./mvnw.cmd -B -ntp '-Dit.test=T06TaskIT,FoundationIT' '-Dfailsafe.failIfNoSpecifiedTests=false' verify
```

T06 集成测试位于 simulator 模块，使用本轮构建的 backend/simulator JAR，独立容器和两个隔离数据库账号。Java 运行镜像配置沿用 `T04_JAVA_IMAGE`；无 Docker 不跳过。验收与边界见 [T06](docs/tasks/T06.md)、[设计决策](docs/decisions/006-t06-task-creation.md)。

### T07 后台任务推进

demo/test 中默认启用 Worker，在创建请求返回后独立扫描数据库，以测试步骤推进 `QUEUED → DIAGNOSING → WAITING_APPROVAL`。每次认领递增 epoch，使用 owner、epoch、状态、版本和租约有效期共同约束写回。等待下次调度或审批时释放租约和线程。已有 T06 的 QUEUED 任务升级后也会被扫描；设置 `WORKER_ENABLED=false` 可保持静态任务创建行为。

当前步骤明确标记为测试替身，**不调用模型，不生成真实可审批方案，不执行修复**。等待审批状态仅验证调度边界；T08/T11 才补齐方案及诊断。非纯 demo/test 环境不自动运行测试步骤。`GET /api/tasks/{id}/steps` 查询持久化步骤，`nextRunAt` 表示下次运行时间。

```powershell
# 根目录：真实数据库租约、T06 回归以及独立双 Worker/重启测试
./mvnw.cmd -B -ntp '-Dit.test=WorkerLeaseIT,T07WorkerIT,T06TaskIT,FoundationIT' '-Dfailsafe.failIfNoSpecifiedTests=false' verify
```

默认扫描 500ms、租约 30 秒、两步间隔 1 秒、步骤失败最多连续 3 次后转人工且保留订单限制。配置范围与步骤接口见 [任务 API](docs/api/tasks.md)，实际验证与边界见 [T07](docs/tasks/T07.md)、[设计决策](docs/decisions/007-t07-worker-leases.md)。数据库写回失败依靠租约过期恢复，不会假装执行成功。

### 目录

T03 比较同一批 12 个案例上的规则、单次模型和按需查询 Agent，运行方法与结果见 [T03](docs/tasks/T03.md)。`./scripts/t03-evaluate.ps1 -Mode offline` 仅跑规则、零模型费用。真实批量运行另需当次授权和 `T03_ALLOW_LIVE=true`，整批最多 60 次请求，不自动重试。该实验使用静态模拟证据，不依赖常驻容器，也不修改数据库。

本次实际对照中规则、单次模型均自动通过 12/12，Agent 为 8/12，尚未证明 Agent 净收益；当前保留规则作为政策基线，模型职责收窄为只读证据整理与解释。失败与解释偏差见 [对照报告](docs/evaluation/T03-report.md)。

```text
backend/         任务 API、租约 Worker、权限、证据与审计、模型调用接口与替身
simulator/       独立模拟服务、业务场景及受控修复
frontend/        Vue + TypeScript 环境状态页
infra/           数据库初始化、容器健康探针
scripts/         统一验证入口
docs/tasks/      任务与实测记录
docs/decisions/  设计决策
文档/            原始需求、设计、图示和开发规范
```

两套数据库角色没有超级用户、建库、建角色权限；数据库及 public schema 撤销 PUBLIC 默认访问权限。各服务仅获得自身数据库凭证，模拟数据通过 HTTP 获取。Flyway V1 创建服务归属标记，模拟服务 V2 创建业务表，后端 V2 创建任务及关联记录、V3 增加 Worker 租约与步骤。已经执行的迁移不得原地修改。

第一版金额使用整数最小货币单位，不支持多币种业务、部分支付、退款或撤销支付；当前场景的支付成功事实不逆转。未来真实支付系统接入需重新设计事实核验协议。

T01 不包含真实模型评测、业务执行可靠性证明或生产收益。后续按 T02—T16 推进，权限、审批、幂等、未知结果处理和恢复机制均需独立验收。
