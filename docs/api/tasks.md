# 任务 API（T06/T07）

默认后端地址 `http://localhost:8080`。创建时持久化 `QUEUED` 任务；T07 在 demo/test 默认用测试步骤自动推进到 `DIAGNOSING`、`WAITING_APPROVAL`，不调用模型、不创建真实方案、不修改订单。所有响应包含 `X-Correlation-ID`。

## 账号与访问权限

预置账号只在全部生效 profile 为 demo/test 时启用；混合 production/demo 也禁用。密码不设置时该账号不可用。默认订单范围为 `T05-ORDER-` 字面前缀，环境变量可收窄，不能设空或通配符。

| 账号 | 环境变量 | 允许操作 |
| --- | --- | --- |
| `operator` | `DEMO_OPERATOR_PASSWORD`、`DEMO_OPERATOR_ORDER_PREFIX` | 范围内创建、列表、详情、证据、审计 |
| `approver` | `DEMO_APPROVER_PASSWORD`、`DEMO_APPROVER_ORDER_PREFIX` | 范围内列表、详情、证据、审计；T06 尚无批准接口 |

密码至少 12 个字符，UTF-8 最多 72 字节。配置会在进程启动时读取；不持久化明文密码。演示账号以 HTTP Basic 认证，请在本地回环环境使用；远程环境需要 HTTPS 和正式认证方案。

`GET /api/me` 返回用户名、角色、订单范围及已实现的允许操作。HTTP Basic 首次认证后建立 Spring 会话；`GET /api/auth/csrf` 返回 `headerName`、`token`。后续请求保留会话 Cookie，写请求附带 CSRF 头；也可以继续发送同一账号的 Basic 凭证。每个账号使用自己的会话；重启或切换账号后重新认证并获取令牌。

后端只获得 `SIMULATOR_READ_TOKEN`，无模拟执行/管理凭证。`SIMULATOR_BASE_URL` 在直接 Java 启动时默认 localhost:8081；Compose 使用内部 `http://simulator:8081`。

`POST /api/auth/logout` 携带有效会话和 CSRF 令牌退出，返回 204；会话失效后重新认证。退出后的请求若再次发送有效 Basic 凭证，将建立新的登录会话。

## 创建

`POST /api/tasks`，头 `Idempotency-Key: <1—100位英文字母/数字/_/->`，JSON：

```json
{"orderId":"T05-ORDER-从场景列表取得的实际ID"}
```

替换占位文本，订单 ID 同样只接受 1—100 位英文字母、数字、`_`、`-`。未知字段（包括客户端伪造的 `createdBy`）、缺字段、非法 ID 均 400。

新建返回 201，复用返回 200；均提供 `Location: /api/tasks/{id}`，响应结构：

```json
{
  "task": {
    "id": "任务UUID",
    "orderId": "实际订单ID",
    "state": "QUEUED",
    "outcome": null,
    "version": 1,
    "createdBy": "operator",
    "createdAt": "带时区时间",
    "updatedAt": "带时区时间",
    "nextRunAt": "下次运行的带时区时间，等待审批时为null",
    "allowedActions": ["VIEW_EVIDENCE", "VIEW_AUDIT", "VIEW_STEPS", "VIEW_PLAN", "VIEW_EXECUTION"]
  },
  "reused": false
}
```

- 同账号同键同订单返回原任务；同键不同订单 409。
- 不同键提交同订单仍返回已有受限任务，不创建新任务。现有任务已被人工接管但仍受限时，同样返回它。
- 订单不存在 404；模拟服务超时、不可访问或凭证配置错误 503；响应字段异常 502。新任务不会部分落库。
- 网络失败后的调用方可使用原幂等键重试。重复任务和已持久化证据查询不依赖模拟服务当时是否在线。

## 查询

| 方法及路径 | 作用 |
| --- | --- |
| `GET /api/tasks?orderId=...&state=QUEUED&page=0&size=20` | 范围内任务列表；订单/状态可省略；page 从 0 开始，size 为 1—100；返回 items、total、page、size |
| `GET /api/tasks/{id}` | 当前持久化任务详情及允许操作 |
| `GET /api/tasks/{id}/evidence` | 创建时取得的订单快照数组：id、source、businessVersion、payload、fetchedAt |
| `GET /api/tasks/{id}/audit?after=0&limit=50` | 基础审计数组：id、actor、eventType、correlationId、createdAt；after 为上次最后一项 ID，limit 为 1—100 |
| `GET /api/tasks/{id}/steps?after=0&limit=50` | 持久化步骤数组：id、seq、type、status、beforeState、afterState、output、startedAt、finishedAt；权限与详情相同 |

列表按创建时间和任务 ID 倒序排列。初始证据来源为 `SIMULATOR_ORDER`，仅为订单创建时快照；不是诊断结论或最终业务验证。执行前复核及最终验证另存订单/支付事实快照，尚未采集回调诊断证据。Worker 独立推进任务，刷新以上接口本身不触发推进、不写业务、不调用模型。

审计类型：`TASK_CREATED`、`TASK_REUSED`、`WORKER_CLAIMED`、`WORKER_ADVANCED`、`WORKER_RETRY`、`WORKER_FAILED`、`PLAN_CREATED`、`PLAN_APPROVED`、`PLAN_REJECTED`、`PLAN_EXPIRED`、`EXECUTION_INTENT`、`EXECUTION_PROGRESS`、`TASK_CANCELLED`、`TASK_TAKEN_OVER`、`MANUAL_RESOLVED`。同幂等键重放不会重复追加创建审计；新幂等键复用任务会追加复用事件。人工请求的 actor 来自认证上下文，Worker 事件使用服务端生成的 Worker ID。状态、步骤和对应审计同事务保存；旧 Worker 写回失败不会追加步骤。

T07 步骤类型为 `TEST_START`、`TEST_WAIT` 或 `TEST_STEP_ERROR`；状态为 `COMPLETED`、`RETRY`、`FAILED`。seq 使用认领 epoch，可能有间隙；分页用 id。T08 起，等待审批与冻结测试方案同事务保存，可以通过[审批接口](plans.md)批准或拒绝；T09 已接入[受控执行](execution.md)，配置执行凭证后批准任务自动继续运行。

| 配置 | 默认值 | 范围/含义 |
| --- | --- | --- |
| `WORKER_ENABLED` | true | 只有纯 demo/test 环境启用；false 不调度 |
| `WORKER_POLL_MS` | 500 | 50—60000ms，单次处理结束后的扫描间隔 |
| `WORKER_LEASE_MS` | 30000 | 1000—300000ms，认领租约；数据库时钟判定 |
| `WORKER_STEP_DELAY_MS` | 1000 | 0—60000ms，测试步骤间隔，不占线程等待 |
| `WORKER_RETRY_MS` | 1000 | 100—60000ms，步骤异常后的重试间隔 |
| `WORKER_MAX_FAILURES` | 3 | 1—10，连续步骤失败上限，达到后转人工并保留限制 |

已有 T06 的 QUEUED 任务升级后也会被启用的演示 Worker 扫描。配置 false 可先保持静态状态。租约不会替代下游幂等；T09 执行 Worker 使用持久化意图及下游幂等控制业务写操作。

错误结构 `code, message, correlationId`。主要错误：400 `INVALID_REQUEST`，401 `UNAUTHENTICATED`，403 `FORBIDDEN`（角色/订单范围不足或 CSRF 校验失败），404 `TASK_NOT_FOUND` / `ORDER_NOT_FOUND`，409 `IDEMPOTENCY_CONFLICT` / `TASK_CHANGED`，502 `INVALID_ORDER_EVIDENCE`，503 `SIMULATOR_UNAVAILABLE`，500 `INTERNAL_ERROR`。

任务 ID 不存在与不在账号范围内均返回 404，不泄露其他范围证据；指定越权订单号创建/筛选返回 403。业务响应不包含密码、凭证、提示词或堆栈。

## PowerShell 体验

先参考 `.env.example` 配置 T05、T06 变量并启动服务。已有 `.env` 需要自行补齐新键，不会自动覆盖。以下假设当前终端已有 `DEMO_OPERATOR_PASSWORD` 和 `SIMULATOR_ADMIN_TOKEN`；不要打印凭证。

```powershell
$base = 'http://localhost:8080'
$basic = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("operator:$env:DEMO_OPERATOR_PASSWORD"))
$headers = @{ Authorization = "Basic $basic" }
$csrf = Invoke-RestMethod "$base/api/auth/csrf" -Headers $headers -SessionVariable operatorSession
$headers[$csrf.headerName] = $csrf.token
$scenarios = Invoke-RestMethod 'http://localhost:8081/internal/demo/scenarios' -Headers @{ Authorization = "Bearer $env:SIMULATOR_ADMIN_TOKEN" }
$orderId = ($scenarios | Where-Object scenario -eq 'CALLBACK_FAILED').activeOrderId
$headers['Idempotency-Key'] = [guid]::NewGuid().ToString()
$body = @{ orderId = $orderId } | ConvertTo-Json
$result = Invoke-RestMethod "$base/api/tasks" -Method Post -Headers $headers -WebSession $operatorSession -ContentType 'application/json' -Body $body
$taskId = $result.task.id
Invoke-RestMethod "$base/api/tasks/$taskId" -Headers $headers -WebSession $operatorSession
Invoke-RestMethod "$base/api/tasks/$taskId/evidence" -Headers $headers -WebSession $operatorSession
Invoke-RestMethod "$base/api/tasks/$taskId/audit" -Headers $headers -WebSession $operatorSession
Invoke-RestMethod "$base/api/tasks/$taskId/steps" -Headers $headers -WebSession $operatorSession
Invoke-RestMethod "$base/api/tasks?state=QUEUED" -Headers $headers -WebSession $operatorSession
# 原键重试应复用同一任务
Invoke-RestMethod "$base/api/tasks" -Method Post -Headers $headers -WebSession $operatorSession -ContentType 'application/json' -Body $body
```

本任务没有前端任务页面；T12 才提供页面创建和查询交互。

T10 增加按角色和状态返回的 CANCEL、TAKEOVER、RESOLVE_MANUAL，以及 VIEW_HANDLING。取消、人工接管、核实结案和不可变处置记录见[处置接口](handling.md)。
