# T07：数据库驱动的 Worker 与租约隔离

## 问题与范围

T06 已持久化 QUEUED 任务。T07 要求任务在请求返回后仍能推进，多个 Worker 不覆盖结果，租约过期后可接管，等待时不占用执行线程。此阶段只用测试步骤验证调度机制，不接入真实模型、审批方案或业务修复。

## 选择

- Flyway V3 为 task 增加 lease_owner、lease_until、lease_epoch、next_run_at、worker_failures，新增 task_step。保持 V1/V2 不变。已有 QUEUED/DIAGNOSING 任务迁移后可调度，其他状态 next_run_at 清空。
- 每个进程生成独立 Worker UUID，每轮只认领一个到期任务，采用短事务内 `FOR UPDATE SKIP LOCKED` 与 `UPDATE RETURNING`。认领递增 epoch 和任务版本，租约与下次执行时间以 PostgreSQL 时钟为准。
- 扫描只处理 QUEUED/DIAGNOSING，不扫描 WAITING_APPROVAL、APPROVED、EXECUTING、UNKNOWN 或终态。索引按 next_run_at/id 排序并限定可扫描状态，不能将动态当前时间写入部分索引条件。
- 认领及认领审计原子提交，之后在事务外运行步骤。写回必须同时匹配 taskId、owner、epoch、认领时状态、版本，且租约尚未过期。被拒绝的旧写回不追加步骤或业务审计。
- 状态转换、释放租约、安排下次运行、步骤和推进审计在同一事务完成。步骤/审计落库失败全部回滚，保留数据库认领状态，待租约过期后重做同一持久化步骤。
- 每次成功写回释放租约；DIAGNOSING 设置未来 next_run_at，WAITING_APPROVAL 清空 next_run_at。不用 sleep 等待下一次调度或审批，后续扫描处理其他任务。
- 当前测试步骤为 QUEUED → DIAGNOSING → WAITING_APPROVAL，步骤输出明确注明没有真实诊断、没有可审批方案。此替身仅在纯 demo/test 环境自动运行，其他或混合 production 环境不调度。
- 步骤异常增加持久化失败次数，安排有限次重试；达到上限转 MANUAL/TEST_STEP_FAILED，保留订单限制。数据库写回异常不能误算为步骤异常，不另行释放租约；扫描恢复仍受 epoch 约束。
- task_step.seq 使用认领 epoch，允许因过期/崩溃接管出现间隙，唯一约束为 `(task_id,seq)`。对外用 id 游标查询，访问范围与任务详情一致。
- 步骤当前有界且立即返回，不需要续租线程。超时完成被拒绝，租约过期后由新认领继续。未来 T11 的模型调用必须设置调用超时并重新评估续租/预算策略；不能直接把当前单线程测试步骤视作长任务执行框架。

## 未采用方案

- 请求线程推进任务：会依赖浏览器连接，无法在重启后继续。
- JVM 锁/本地任务队列：不能阻止多个进程同时修改持久化状态。
- 只检查 owner：同一 owner 再认领也可能出现旧请求，必须有 epoch 和版本隔离。
- 在模型/HTTP 调用期间持有数据库锁：违反短事务约束，阻碍其他任务认领。
- 等待审批时保留线程和租约：无工作时占资源，也阻碍恢复。

## 边界

租约不保证请求恰好发送一次，也不能替代 T04 的下游幂等。T07 完全没有下游写调用。已有 T06 验收测试显式设置 WORKER_ENABLED=false，保留对静态创建事务的完整断言；T07 独立测试覆盖开启时的推进行为。

验收包含真实 PostgreSQL 的过期/旧 epoch 检查以及独立 JVM 的双 Worker 和重启续跑；不将数据库夹具设置过期租约描述为实际 SIGKILL 故障窗口实验。后者仍由 T04/T14 范围负责。结果见 [T07](../tasks/T07.md)。
