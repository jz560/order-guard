# 002：T02 使用应用控制的 DeepSeek 只读实验

## 问题与边界

T02 要验证 Spring AI 与真实模型的兼容性，但完整业务模拟服务、任务权限和诊断持久化分别在 T05、T06、T11 实现。不能为了实验提前开放无权限的业务 API，也不能将固定夹具冒充真实业务。

## 选择

- 用户选择 `deepseek-flash`。使用固定版本 `spring-ai-openai:2.0.0` 适配 DeepSeek 官方兼容协议，沿用 Boot 4.1.1、Java 21，不引入自动配置 starter。
- 直接调用 `OpenAiChatModel` 单轮接口，应用负责每轮预算、记录、工具验证和执行。未使用自动执行工具的 ChatClient advisor。
- 2.0.0 的 `OpenAiChatModel.createRequest` 对通用 `ToolCallingChatOptions` 存在类型转换限制；在网关内转换为供应商选项，保留应用层的通用模型接口。由本地 HTTP 协议测试覆盖。
- 使用非思考模式，SDK `maxRetries=0`，输出最多 1000 token；一次实验默认最多 4 轮、12 次只读工具调用。任何失败终止，不自动纠错增加请求。
- 四个工具读取固定模拟证据；订单绑定由程序建立，模型必须传入同一订单，拒绝额外字段、重复 JSON 键、尾随 JSON 和未知工具。没有业务写工具或写凭证。
- CLI 是唯一实验入口。默认 Spring Bean 仍为 stub。真实实验需 `T02_ALLOW_LIVE=true` 和本机密钥，两者不在源代码中保存。
- 每次运行单独 JSONL，调用前后追加并关闭文件；文件写入失败即停止。这不是数据库任务日志，也不承诺断电持久性或恢复后恰好一次模型调用。
- 最终输出采用提示词 JSON 契约与本地严格校验。未依赖供应商 Beta strict 模式；不将结构正确等同于结论正确。

## 放弃方案

- 自动工具循环：难以在每次调用前明确控制预算及审计失败边界。
- 提前实现业务表和完整内部 API：超出当前任务依赖，应留在 T05/T06。
- 免费测试全部连接真实模型：会产生费用和随机结果；普通测试使用脚本替身及真实适配器连接本地 HTTP 模拟服务。

## 验证与来源

具体执行结果见 [T02](../tasks/T02.md)。本地协议测试并不证明 DeepSeek 线上兼容性，真实调用单独记录。

- [Spring AI 工具调用说明](https://docs.spring.io/spring-ai/reference/api/tools.html)
- [DeepSeek 工具调用与非思考模式](https://api-docs.deepseek.com/guides/tool_calls/)
- [DeepSeek 模型和价格](https://api-docs.deepseek.com/zh-cn/quick_start/pricing/)
