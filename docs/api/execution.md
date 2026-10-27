# T09 受控执行与结果确认

按 [方案审批](plans.md) 批准后，配置完整的演示 Worker 自动推进，无需页面连接或第二个执行请求。Worker 要求 `WORKER_ENABLED=true`、纯 demo/test 环境、后端同时配置 `SIMULATOR_READ_TOKEN` 和 `SIMULATOR_EXECUTE_TOKEN`。任一凭证为空时不认领执行任务；计划审批仍可使用，未开始执行的方案照常过期。

## 流程与结果

1. 认领 APPROVED 任务，重新查询冻结方案对应的订单和支付。订单必须仍待支付、版本/金额/币种/支付关联与方案一致，支付必须成功。查询在数据库事务外进行；查询不可用按 WORKER_RETRY_MS、WORKER_MAX_FAILURES 有限重试。
2. 短事务锁任务，重查最新方案、最终批准决定、参数摘要、有效期和租约条件。原子创建唯一执行意图和 operationId，切换为 EXECUTING，保存复核证据和审计。这个原子转换是操作获准开始的边界；T10 取消使用同一任务锁参与竞争。
3. 提交后使用执行凭证调用 `PUT /internal/operations/{operationId}`，参数来自冻结方案。下游在自己的事务中再次核验事实并保存幂等结果，防止步骤 1 到步骤 3 之间的业务变化。
4. 成功响应先进入 VERIFYING，再通过只读凭证重新查询订单、支付，验证 PAID、精确版本、金额、币种、支付成功和关联关系；保存验证证据后才能 FINISHED/REPAIR_VERIFIED。
5. 超时、异常响应、未查到、查询不可用或处理中进入 UNKNOWN，使用原 ID 查询；不会自动重新发起修复。首次意图提交后进程退出，恢复也只查询，即使不确定是否真正发送过。已开始的操作在审批过期后仍继续确认结果。

| 情况 | 任务状态 / outcome | 订单限制 |
| --- | --- | --- |
| 执行前事实不匹配 | MANUAL / BUSINESS_FACTS_CHANGED | 保留，等待后续人工处理 |
| 执行前查询持续不可用 | MANUAL / PRECHECK_UNAVAILABLE | 保留 |
| 确认下游明确拒绝 | FINISHED / EXECUTION_REJECTED | 释放；具体拒绝码在 result.code |
| 写响应或查询不确定 | UNKNOWN，延后查询 | 保留 |
| 下游成功但业务事实验证不匹配 | MANUAL / VERIFICATION_MISMATCH | 保留，不能标成功 |
| 业务验证完成 | FINISHED / REPAIR_VERIFIED | 释放 |
| 不确定/验证查询超过自动确认期限 | MANUAL / CONFIRMATION_DEADLINE_EXCEEDED | 保留，不等于业务失败 |

自动确认期限从意图创建起 5 分钟；查询间隔依持久化 checks 为 2、5、10、30 秒，此后 30 秒。非确定结果在到期后的下一次处理转人工。等待时释放租约与线程。不提供重发接口；T10 已提供[取消、接管和核实结案接口](handling.md)，仅在明确结果满足条件时释放限制。不能靠重新创建任务绕过 UNKNOWN/MANUAL 的限制。

## 查询接口

`GET /api/tasks/{id}/execution`，operator 和 approver 都需要在任务订单范围内。尚无意图返回 404 `EXECUTION_NOT_FOUND`；无权限与任务不存在沿用任务查询语义。返回：

- operationId、planId：不可更改的执行身份。
- status：EXECUTING / UNKNOWN / VERIFYING / SUCCEEDED / REJECTED / MANUAL。
- checks：持久化结果处理次数，包括首次写响应处理及后续查询/验证；不是实际网络请求总数。
- result：已确认且与原 ID/全部参数匹配的下游结果，含 status、code、beforeVersion、afterVersion 等；暂无确认结果为 null。
- lastError：最近处理标记（也可为 VERIFY_BUSINESS_RESULT、REPAIR_VERIFIED 等进度码），不包含原始异常或凭证。
- createdAt、deadlineAt、updatedAt：带时区时间，界面按 Asia/Hong_Kong 显示。

任务 allowedActions 增加 VIEW_EXECUTION。`GET /api/tasks/{id}/evidence` 增加 EXECUTION_PRECHECK、EXECUTION_VERIFICATION，payload 含 order、payment、fetchedAt；这两类才是执行复核与最终验证证据。固定测试方案仍标记 TEST_STUB，不代表 Agent 诊断。

步骤类型 CONTROLLED_EXECUTION，output 为受控进度码。审计新增 EXECUTION_INTENT 和 EXECUTION_PROGRESS；意图审计与唯一 operationId 可通过 taskId 对应执行记录。查询以上接口不会触发修复或重放历史写请求。

## 配置与体验

后端增加执行凭证映射；`.env.example` 中已有 SIMULATOR_EXECUTE_TOKEN，Compose 现在也传给 backend。读凭证仍用于事实查询，模型只读工具不会获得执行客户端。`EXECUTION_HTTP_TIMEOUT_MS` 默认 3000，范围 100—5000；不自动重试 HTTP 写调用。

按 tasks.md 创建回调失败场景的任务，按 plans.md 批准，然后查询：

```powershell
Invoke-RestMethod "$base/api/tasks/$taskId" -Headers $headers -WebSession $operatorSession
Invoke-RestMethod "$base/api/tasks/$taskId/execution" -Headers $headers -WebSession $operatorSession
Invoke-RestMethod "$base/api/tasks/$taskId/evidence" -Headers $headers -WebSession $operatorSession
Invoke-RestMethod "$base/api/tasks/$taskId/steps" -Headers $headers -WebSession $operatorSession
```

响应丢失演示：将后端 EXECUTION_HTTP_TIMEOUT_MS 设为 500，再用模拟服务的管理凭证设置 CALLBACK_FAILED 的 DELAY_RESPONSE_ONCE 故障后批准。模拟服务提交后延迟 2 秒响应，调用方超时进入 UNKNOWN，后续查询原操作并验证成功。默认 3 秒超时大于该演示延迟，不会必然出现 UNKNOWN。

T09 后端升级应用 V5 迁移；既有有效 APPROVED 任务在执行凭证配置完成后也会被认领。现有审批页面仍未开发，前端交互属于 T13。
