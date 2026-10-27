# T10 取消与人工接管

沿用任务 API 的认证会话、CSRF 和订单范围。操作员负责取消；审批员负责人工接管、核实结案。所有写请求重新校验权限、状态和任务版本，actor 来自认证上下文。前端入口仍待 T13 实现。

| 接口 | 角色 | 请求 |
| --- | --- | --- |
| `POST /api/tasks/{id}/cancel` | OPERATOR | `version`，可选 `reason`（缺省“用户取消任务”） |
| `POST /api/tasks/{id}/takeover` | APPROVER | `version, reason` |
| `POST /api/tasks/{id}/resolve` | APPROVER | `version, reason, resolution` |
| `GET /api/tasks/{id}/handling` | OPERATOR / APPROVER | 范围内任务的只读处置记录，按时间排序 |

version 为最新任务版本，正整数。原因去首尾空白，不能纯空白，最长 1000 字符。额外身份、任意结果或参数字段均被拒绝。resolution 仅接受 `NO_EXECUTION`、`CONFIRM_OPERATION`。

成功返回 200 和不可变处置记录：`id, taskId, action, expectedVersion, actor, reason, resolution, outcome, operationId, evidenceId, createdAt`。取消/接管的 resolution 为 null；没有执行意图时 operationId 为 null。接口不接受用户提交“修复成功”作为业务证明。

同任务、动作、原预期版本、账号、规范化原因和结案方式的重试返回同一记录，不重复审计或释放限制；不同请求复用同一动作/版本返回 `409 HANDLING_CONFLICT`。成功后用原请求重试可跨服务重启；不要修改版本后期待同一请求重放。

## 取消边界

QUEUED、DIAGNOSING、WAITING_APPROVAL、APPROVED 且没有执行意图时可取消。取消锁任务，与执行意图创建竞争：取消先提交则 FINISHED/CANCELLED，释放订单限制、清空租约和调度时间，增加 version/epoch，旧 Worker 不能继续写回或开始执行。

如执行事务先提交，则返回 `409 EXECUTION_ALREADY_STARTED`，不改变任务、租约或执行记录，系统继续确认原操作。EXECUTING 表示写操作已获准开始，即使尚不能证明 HTTP 已发出，也不能再承诺取消。不能以取消撤销下游业务效果。

Worker 仅认领但尚未创建意图时，会增加任务版本；过时页面可能先收到 `409 TASK_CHANGED`，刷新后仍可能取消，最终以锁内检查为准。MANUAL 不支持取消，应走结案流程。

## 人工接管与结案

接管可作用于未结束且尚未 MANUAL 的任务，切换为 MANUAL，并记录账号与原因。增加 version/epoch 并清除租约/调度时间，停止自动推进；已有外部请求不能因此撤回，其迟到结果不得覆盖人工状态。所有接管均保留订单限制。

自动转人工的任务也可直接结案，不必重复接管。结案方式：

- `NO_EXECUTION`：只有数据库确认没有 execution 意图时有效。记录 `MANUAL_CLOSED_WITHOUT_EXECUTION`，FINISHED 并释放限制。它表示没有开始本系统业务操作，不表示修复成功。
- `CONFIRM_OPERATION`：必须已有执行意图。服务端在事务外只读查询原 operationId；结果必须与冻结动作及全部参数绑定。明确拒绝记录 `MANUAL_EXECUTION_REJECTED`；成功还须重新核验订单 PAID、精确版本、金额、币种、支付成功及关联关系，才记录 `MANUAL_REPAIR_VERIFIED`。提交时重新锁任务并核对版本，原子保存核实证据、执行状态、处置记录和审计，然后释放限制。

404、不可访问、处理中、参数不匹配或业务验证不符，都不能结案或释放限制。请求返回 409，任务仍为 MANUAL；可以稍后用同一版本重新核实。人工查询不重发修复、不生成新操作 ID。仅凭备注、仅看到订单已支付或仅看到操作成功，都不能绕过完整核验。

`CONFIRM_OPERATION` 使用后端配置的模拟服务执行查询凭证及只读事实凭证；缺凭证时返回结果未确认。该功能不要求 Worker 开启，因此可在停止自动调度时由审批员核实。

未知任务被接管后，新幂等键创建同订单任务仍返回原受限任务；只有明确结案释放 guard 后，新键才能创建新任务。原创建幂等键始终返回旧任务。没有“强制解除限制”入口。

## 展示与错误

任务 allowedActions 增加 VIEW_HANDLING，并按角色/状态提供 CANCEL、TAKEOVER 或 RESOLVE_MANUAL。接口提交仍是最终权威；按钮可见不保证并发期间操作成功。

审计新增 TASK_CANCELLED、TASK_TAKEN_OVER、MANUAL_RESOLVED。核实结案证据来源为 MANUAL_RESOLUTION，payload 含原操作及成功时的订单/支付事实，记录的 evidenceId 可直接追溯。处置记录与审计只读查询不会触发业务操作。

除通用 400/401/403/404 外，主要冲突：TASK_CHANGED、TASK_STATE_CONFLICT、EXECUTION_ALREADY_STARTED、EXECUTION_EXISTS、EXECUTION_NOT_FOUND、RESULT_UNCONFIRMED、VERIFICATION_MISMATCH、HANDLING_CONFLICT。

## PowerShell 示例

沿用 tasks.md 和 plans.md 中的 `$base`、`$taskId`、操作员及审批员会话：

```powershell
$task = Invoke-RestMethod "$base/api/tasks/$taskId" -Headers $headers -WebSession $operatorSession
$cancelBody = @{ version = $task.version } | ConvertTo-Json
Invoke-RestMethod "$base/api/tasks/$taskId/cancel" -Method Post -Headers $headers -WebSession $operatorSession -ContentType 'application/json' -Body $cancelBody

# 以下是独立示例：先将 $taskId 改为另一个需要人工核实的未结束任务 ID
$task = Invoke-RestMethod "$base/api/tasks/$taskId" -Headers $approverHeaders -WebSession $approverSession
$takeoverBody = @{ version = $task.version; reason = '转人工核实原操作结果' } | ConvertTo-Json
Invoke-RestMethod "$base/api/tasks/$taskId/takeover" -Method Post -Headers $approverHeaders -WebSession $approverSession -ContentType 'application/json' -Body $takeoverBody
$task = Invoke-RestMethod "$base/api/tasks/$taskId" -Headers $approverHeaders -WebSession $approverSession
$resolveBody = @{ version = $task.version; reason = '重新查询并核实原操作'; resolution = 'CONFIRM_OPERATION' } | ConvertTo-Json
Invoke-RestMethod "$base/api/tasks/$taskId/resolve" -Method Post -Headers $approverHeaders -WebSession $approverSession -ContentType 'application/json' -Body $resolveBody
Invoke-RestMethod "$base/api/tasks/$taskId/handling" -Headers $approverHeaders -WebSession $approverSession
```
