# T11 持久化 Agent 诊断

创建任务后由 Worker 后台执行，默认 `DIAGNOSIS_MODE=agent`、`MODEL_MODE=stub`。默认模式对真实独立模拟服务执行只读 HTTP，模型采用确定性工程替身，没有模型费用。`MODEL_MODE=deepseek` 使用既有 Spring AI 网关和 `DEEPSEEK_API_KEY`，会产生费用；本次未调用线上模型验证 T11 效果。

## 状态和结论

| 结论 | 状态和处理 |
| --- | --- |
| NO_ACTION | FINISHED，记录无需处理并释放订单限制 |
| WAIT | 保持 DIAGNOSING，outcome=WAIT_RECHECK，保存下次复查时间并释放线程/租约；重新获取新一批证据 |
| MANUAL | MANUAL，保留限制，由 T10 规则核实结案 |
| RECOMMEND_RECHECK | WAITING_APPROVAL，冻结服务端从订单证据提取的参数；仍须审批，模型不能执行 |

WAIT 默认最多复查一次，若仍需等待，转 MANUAL/WAIT_REVIEW_LIMIT。复查不重置总轮数、工具次数或诊断总时限。总预算耗尽、无效输出、越权工具、反复查询、超时和模型错误均转人工并保留限制。

规则优先核对订单状态、支付关联、整数金额与币种。订单 PAID 无需处理，CLOSED 转人工；支付缺失或冲突转人工。成功支付在 60 秒内且回调 PENDING 可等待。只有成功支付、金额关联一致、回调 FAILED、没有本系统历史执行记录、有效订单版本，才允许建议修复。已有任何历史执行记录时保守转人工。当前模拟数据没有 PROCESSING 的可靠起始时间，无法确认等待窗口的支付状态转人工。

所有非 MANUAL 结论必须引用当前复查批次的四类证据 UUID，且与确定性规则一致。工具内容不能扩大权限；模型自由文本只作为公开输出记录，面向用户的最终说明由规则生成。结构合法不等于业务结论正确。

## 工具与恢复

- query_order：HTTP 查询绑定任务订单。
- query_payment：从已保存绑定订单证据提取 paymentId，HTTP 查询支付。
- query_callbacks：使用同一 paymentId，HTTP 查询回调。
- query_history：查询本应用数据库内该订单的历史执行状态；不是模拟服务全局历史，也不宣称覆盖系统外操作。

模型每个工具请求只可提供绑定的 orderId，不能提供 paymentId 或额外字段。未知工具、重复 JSON 字段、尾随 JSON、其他订单均被拒绝。工具对象只持有只读凭证，无写方法和执行凭证。

模型调用前短事务保存 MODEL_INTENT 并累计轮数；模型响应保存 MODEL_RESPONSE（公开文本、工具请求、模型、提示词版本、用量及耗时）。每个只读查询先保存 TOOL_INTENT 并累计次数，再发 HTTP，再保存 evidence 和 TOOL_RESULT。每一轮结束释放租约，重新由数据库调度。远程调用均在事务外。

重启后根据持久化预算和当前复查批次证据重新构建提示，不重放业务写操作，也不重置已用轮数。响应未保存或尚未完成的工具查询可以被新一轮再次请求，仍消耗预算；不承诺答案相同或模型恰好调用一次。仅有 MODEL_INTENT 而无对应响应的调用，其费用和用量未知。取消/接管后的旧 epoch 无法再保存响应、证据或方案。

## 查询接口

`GET /api/tasks/{id}/diagnosis`：OPERATOR、APPROVER 可读取范围内任务；越界 404，未认证 401。返回 `{run, events}`；尚未开始时 run 为 null。run 含累计 rounds/tools/reviews、startedAt、deadlineAt 和 result；events 按 id 排序，含 epoch、type、payload、createdAt。result/payload 为 JSON 字符串，证据 ID 可关联已有 `/evidence` 接口。该只读接口不会继续诊断或触发模型。

任务 allowedActions 增加 VIEW_DIAGNOSIS。页面属于 T12/T13；本任务仅提供后端接口。

## 配置

| 变量 | 默认值 | 范围/语义 |
| --- | --- | --- |
| DIAGNOSIS_MODE | agent | agent 或 legacy-test；后者仅保留旧工程实验步骤，旧回归显式选择它 |
| DIAGNOSIS_ROUNDS | 6 | 1—12，包含恢复重试与复查 |
| DIAGNOSIS_TOOLS | 12 | 1—24，调用前占用次数 |
| DIAGNOSIS_REPEATS | 2 | 1—3，每批证据中同工具最多调用次数；保守阻断循环 |
| DIAGNOSIS_REVIEWS | 1 | 0—2，最多复查次数 |
| DIAGNOSIS_TIMEOUT_MS | 30000 | 100—30000；实际还受剩余租约和总时限约束 |
| DIAGNOSIS_TOTAL_MS | 120000 | 1000—300000，持久化绝对截止时间，停机/等待也计时 |
| DIAGNOSIS_REVIEW_MS | 10000 | 100—60000，复查等待时间 |

每次模型输出最多 1000 token，公开文本最多 16000 字符，每轮最多 12 个工具请求，参数最多 2048 字符，单次 HTTP 最多等待 3 秒，返回正文最大 65536 字符。费用估算当前记录 unavailable，供应商用量缺失或请求超时不按零费用计算；这些限制不构成精确金额上限。

数据库升级应用 V7，新增 diagnosis_run、不可变 diagnosis_event，并允许 AGENT_VALIDATED 方案来源。AGENT_VALIDATED 表示通过诊断规则验证，不表示使用了真实模型；实际模型见每轮日志。已冻结方案不变。升级后 QUEUED/DIAGNOSING 任务会走当前诊断模式，未配置只读凭证时不能生成有效修复建议。Worker 仍仅在 demo/test 启用。
