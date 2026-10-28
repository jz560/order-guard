# T08 方案审批接口

沿用 [任务接口](tasks.md) 的会话认证、CSRF 和订单范围。operator 可以查看，approver 可以查看和审批；审批人只取认证上下文。写请求必须携带当前会话的 CSRF 令牌。

| 接口 | 行为 |
| --- | --- |
| `GET /api/tasks/{id}/plan` | 查询任务最新方案；尚无方案返回 404 |
| `GET /api/plans/{id}` | 查询指定冻结方案与当前审批、任务状态 |
| `POST /api/plans/{id}/decision` | 批准或拒绝；成功及同一决定重放返回 200 |

方案响应含 `id, taskId, version, action, params, paramsHash, evidenceId, source, createdAt, expiresAt, serverTime, expired, taskState, approval, allowedActions`。

- `params` 固定为 action、amountMinor（整数分）、currency（CNY）、expectedVersion、orderId、paymentId。摘要为服务端固定字段顺序 JSON 的 UTF-8 SHA-256，不要求客户端重算；提交查询响应中的摘要即可。
- `version` 是方案版本，不是任务版本或订单版本。当前每个任务最多生成一个 version=1 方案，不提供改参数或重新生成接口。
- `source=TEST_STUB` 明确标记固定测试方案，只引用创建任务时的订单快照，不代表真实诊断或执行前事实复核。T11 默认生成 source=AGENT_VALIDATED 的方案，引用诊断取得的订单快照，诊断日志还关联支付、回调与历史证据；实际是否使用真实模型须查看模型记录。见[诊断 API](diagnosis.md)。
- 从冻结时开始有效 15 分钟，以 PostgreSQL 时间为准，等于到期时间即无效；`serverTime` 与 `expired` 供展示。时间为带时区绝对时间，界面应按 Asia/Hong_Kong 展示。
- `approval` 未决定时为 null，否则包含 decision、approverId、reason、decidedAt。
- 仅审批员、最新方案、未过期、未决定且任务为 WAITING_APPROVAL 时返回 `allowedActions=["APPROVE","REJECT"]`，其他情况为空数组。操作入口提示不是授权凭证，提交时重新检查。

请求示例（摘要使用实际查询值）：

```json
{
  "version": 1,
  "paramsHash": "查询响应中的64位小写十六进制摘要",
  "decision": "REJECT",
  "reason": "支付证据仍需补充"
}
```

decision 仅接受 APPROVE/REJECT。拒绝原因必填且不能纯空白，原因最多 1000 字符；原因去除首尾空白后存储。未知字段（包括请求体中的 approverId、params）返回 400。

批准将任务从 WAITING_APPROVAL 原子转换为 APPROVED，并写入唯一审批记录与审计；保留订单限制。拒绝转换为 FINISHED/PLAN_REJECTED 并释放订单限制。已批准方案也会在未开始执行而过期时转换为 FINISHED/PLAN_EXPIRED。后台每轮扫描最多处理一个过期任务，关闭 Worker 时不会自动落终态，但 API 仍立即拒绝新过期审批。

同一方案、版本、摘要、审批人、决定及规范化原因的重复提交返回原审批和当前任务状态，不刷新有效期、不重复审计、不再次推进任务。即使原决定已过期也可只读式重放，响应 `expired=true`，不会恢复授权。冲突决定或改写原因返回 409。被拒绝/过期且尚未开始执行的任务可以用**新的创建幂等键**创建新任务；旧创建键始终返回旧任务。

方案和最终审批由数据库触发器禁止原地修改及删除。未来如追加新版本，旧版本的批准不适用于新方案；当前接口拒绝对非最新方案提交决定。执行模块必须重新验证最新方案、摘要、批准决定、有效期和业务事实，再原子创建唯一执行意图；T09 已接入执行模块，详见 [受控执行](execution.md)。

| HTTP | 主要错误码 |
| --- | --- |
| 400 | INVALID_REQUEST / INVALID_DECISION：字段、枚举、原因不合法 |
| 401 / 403 | UNAUTHENTICATED / FORBIDDEN：未认证、非审批员、CSRF 不合法 |
| 404 | PLAN_NOT_FOUND / TASK_NOT_FOUND：不存在或不在订单权限范围内 |
| 409 | PLAN_CHANGED：方案版本、摘要不一致或非最新方案 |
| 409 | PLAN_EXPIRED：尚未决定的方案已过期 |
| 409 | DECISION_CONFLICT：已有不同最终决定 |
| 409 | TASK_STATE_CONFLICT：任务不再等待审批 |

## PowerShell 体验

先按 tasks.md 创建任务，等待 Worker 生成方案。以下沿用 `$base`、`$taskId`；当前终端提供 `DEMO_APPROVER_PASSWORD`。审批账号使用自己的会话。

```powershell
$approverBasic = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("approver:$env:DEMO_APPROVER_PASSWORD"))
$approverHeaders = @{ Authorization = "Basic $approverBasic" }
$approvalCsrf = Invoke-RestMethod "$base/api/auth/csrf" -Headers $approverHeaders -SessionVariable approverSession
$approverHeaders[$approvalCsrf.headerName] = $approvalCsrf.token
$plan = Invoke-RestMethod "$base/api/tasks/$taskId/plan" -Headers $approverHeaders -WebSession $approverSession
$plan | Select-Object version, action, params, expiresAt, source, allowedActions
$decisionBody = @{ version = $plan.version; paramsHash = $plan.paramsHash; decision = 'APPROVE' } | ConvertTo-Json
Invoke-RestMethod "$base/api/plans/$($plan.id)/decision" -Method Post -Headers $approverHeaders -WebSession $approverSession -ContentType 'application/json' -Body $decisionBody
# 相同请求重试只返回既有审批，不创建新执行
Invoke-RestMethod "$base/api/plans/$($plan.id)/decision" -Method Post -Headers $approverHeaders -WebSession $approverSession -ContentType 'application/json' -Body $decisionBody
```

T09 起，配置完整执行凭证的 Worker 自动接续 APPROVED 完成受控执行与结果确认；T13 提供审批页面。升级 V4 会将 T07 留下的无方案 WAITING_APPROVAL 测试任务恢复到 DIAGNOSING，由启用的 Worker 生成方案；已有步骤和审计保留。
