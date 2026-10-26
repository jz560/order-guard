# T05 模拟业务 HTTP 接口

默认地址 `http://localhost:8081`。请求头 `Authorization: Bearer <token>`，JSON 请求使用 `Content-Type: application/json`。所有响应提供 `X-Correlation-ID`。凭证由环境变量注入，至少 16 个字符且不能重复；留空则该角色无可用凭证。

| 变量 | 权限 |
| --- | --- |
| `SIMULATOR_READ_TOKEN` | 只读订单、支付、回调、操作结果 |
| `SIMULATOR_EXECUTE_TOKEN` | 上述读取以及提交修复；只交给可信执行服务 |
| `SIMULATOR_ADMIN_TOKEN` | 场景列表、重置及故障；不含业务读写权限 |

这些是服务凭证，不是 T06 的操作员/审批员账号。无凭证、错误凭证、权限不足均返回 403。仅健康检查匿名可读。尚未实现上游审批，手动执行示例只用于本地演示。

## 业务接口

| 方法及路径 | 响应 |
| --- | --- |
| `GET /internal/orders/{id}` | `id, scenario, status, amountMinor, currency, paymentId, version` |
| `GET /internal/payments/{id}` | `id, orderId, status, amountMinor, currency, paidAt` |
| `GET /internal/payments/{id}/callbacks` | 数组：`id, paymentId, receivedAt, result, errorCode`；支付缺失返回 404 |
| `PUT /internal/operations/{operationId}` | 200 成功或 422 明确业务拒绝；均保存操作结果 |
| `GET /internal/operations/{operationId}` | 200 已持久化结果；404 只表示未查到，不能据此判断未执行 |

提交修复：

```json
{
  "orderId": "从场景列表取得当前订单ID",
  "paymentId": "从订单查询取得支付ID",
  "amountMinor": 10000,
  "currency": "CNY",
  "expectedVersion": 1,
  "action": "VERIFY_PAYMENT_AND_ADVANCE_ORDER"
}
```

订单/支付/操作 ID 接受 1—100 位英文字母、数字、`_` 或 `-`；示例的中文占位文本须替换。所有请求字段必填；未知字段、未知动作、非正整数金额/版本及非 CNY 请求返回 400。

操作响应包含 `operationId`、全部固定请求参数、`status`、`code`、中文 `message`、`beforeVersion`、`afterVersion` 和 `completedAt`；关联 ID 见响应头。时间为带 UTC 偏移的绝对时间；金额为整数分。

| code | 含义 |
| --- | --- |
| `VERIFIED_AND_ADVANCED` | 核验成功，订单已推进 |
| `ORDER_NOT_FOUND` | 未找到订单 |
| `SCENARIO_REPLACED` | 订单已不属于当前演示轮次 |
| `ORDER_NOT_PENDING` | 已支付或已关闭，不可推进 |
| `ORDER_VERSION_CHANGED` | 版本已变化 |
| `PAYMENT_EVIDENCE_MISSING` | 缺少支付事实 |
| `PAYMENT_LINK_MISMATCH` | 请求/订单/支付关联关系不一致 |
| `PAYMENT_NOT_SUCCESSFUL` | 支付未成功 |
| `AMOUNT_MISMATCH` / `CURRENCY_MISMATCH` | 金额或币种不一致 |

同操作 ID 同参数返回原结果（包括原时间与拒绝原因）；同 ID 不同参数返回 409 `IDEMPOTENCY_CONFLICT`。超时、500、503 均不能视为业务未执行，应查询原 ID 并核验订单。返回成功后的重放不再次修改订单。此版本不对外暴露未提交的 `IN_PROGRESS` 行；查询也可能暂时 404。

错误响应结构为 `code, message, correlationId`，不返回堆栈或数据库错误。常见错误：400 `INVALID_REQUEST`、403 `FORBIDDEN`、404 `NOT_FOUND`、409 `IDEMPOTENCY_CONFLICT`、503 `RESULT_UNAVAILABLE`、500 `INTERNAL_ERROR`。

## 演示管理

仅 demo/test 环境且持有管理凭证开放。`SIMULATOR_PROFILE` 是 Compose 配置入口；直接启动 Java 时用 `SPRING_PROFILES_ACTIVE`。首次初始化，后续启动保留状态。非演示环境管理路径返回 403，不初始化场景且不执行故障。

| 方法及路径 | 输入 / 作用 |
| --- | --- |
| `GET /internal/demo/scenarios` | 返回 `scenario, activeOrderId, fault` 数组 |
| `POST /internal/demo/scenarios/{scenario}/reset` | 无请求体，创建新一轮订单、切换指针、清除故障，返回新场景状态 |
| `PUT /internal/demo/scenarios/{scenario}/fault` | `{"fault":"NONE"}` 等，返回场景状态 |

场景名：`CALLBACK_FAILED`、`NORMAL_DELAY`、`AMOUNT_CONFLICT`、`MISSING_EVIDENCE`、`ALREADY_REPAIRED`、`ORDER_CLOSED`。

故障值：

- `NONE`：关闭故障。
- `DELAY_RESPONSE_ONCE`：下一次新操作提交后延迟 HTTP 响应 2 秒；已完成操作的重放不消耗故障。客户端可设置小于 2 秒的超时；随后查询原操作 ID。不是业务回滚或下游失败。
- `QUERY_UNAVAILABLE`：当前轮次的已存在操作结果查询返回 503；清除故障后可继续确认。订单查询仍可用。

重置不删除旧订单或操作，不撤销已发生业务效果。旧操作仍可查询和幂等重放；针对旧订单的新操作返回 `SCENARIO_REPLACED`。每次重置后需重新读取新订单，不能沿用上一轮操作 ID。

## PowerShell 演示

先启动 Compose，并将三个凭证从本机配置注入当前终端环境（不要把真实凭证写入脚本或日志）。已有 `.env` 需补充 `.env.example` 中 T05 变量；Compose 不会自动给已有文件增加键。

```powershell
$base = 'http://localhost:8081'
$admin = @{ Authorization = "Bearer $env:SIMULATOR_ADMIN_TOKEN" }
$reader = @{ Authorization = "Bearer $env:SIMULATOR_READ_TOKEN" }
$writer = @{ Authorization = "Bearer $env:SIMULATOR_EXECUTE_TOKEN" }
$scenario = Invoke-RestMethod "$base/internal/demo/scenarios/CALLBACK_FAILED/reset" -Method Post -Headers $admin
$order = Invoke-RestMethod "$base/internal/orders/$($scenario.activeOrderId)" -Headers $reader
Invoke-RestMethod "$base/internal/payments/$($order.paymentId)" -Headers $reader
Invoke-RestMethod "$base/internal/payments/$($order.paymentId)/callbacks" -Headers $reader
$operationId = [guid]::NewGuid().ToString()
$body = @{ orderId=$order.id; paymentId=$order.paymentId; amountMinor=$order.amountMinor; currency=$order.currency; expectedVersion=$order.version; action='VERIFY_PAYMENT_AND_ADVANCE_ORDER' } | ConvertTo-Json
Invoke-RestMethod "$base/internal/operations/$operationId" -Method Put -Headers $writer -ContentType 'application/json' -Body $body
Invoke-RestMethod "$base/internal/operations/$operationId" -Headers $reader
Invoke-RestMethod "$base/internal/orders/$($order.id)" -Headers $reader
```

正常延迟不启动自动回调调度，需查看支付时间和回调待送达证据。下游修复仍可通过业务校验，后续诊断策略负责决定是否应等待。此处不包含任务、审批、Worker 或真实模型调用。
