# ADR 001：T01 工程基础

日期：2026-10-21

## 约束与选择

按原始设计建立 Maven 聚合工程、模块化业务服务和独立模拟服务。两个模块不相互依赖，不共享业务数据库。API、Worker、审批和执行后续在 backend 内按能力组织；T01 不预建业务表和通用工作流。

采用 Java 21、Spring Boot 4.1.1、Spring AI BOM 2.0.0、MyBatis Starter 4.0.0。Spring AI 目前仅使用模型类型定义，不加载任何供应商自动配置；通过 `ModelGateway` 隔离一轮模型调用。默认替身始终标明 stub、证据为空并建议人工处理，不能用于证明模型效果。显式配置未知模式会启动失败，防止把替身误认为真实模型。

版本依据：[Spring AI 2.0 发布说明](https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/)、[MyBatis Starter](https://github.com/mybatis/spring-boot-starter)。兼容性最终以本项目构建及集成测试为准，结果记录在 T01 任务记录中。

## 数据隔离与启动检查

本地使用一个 PostgreSQL 实例，建立独立数据库和独立非超级用户账号。撤销 PUBLIC 的数据库权限和 schema 权限，只允许所属账号连接和迁移。两个 Flyway V1 分别保存不可混淆的服务归属标记，启动时通过 MyBatis 检查；连接错库会失败。

不采用 H2 或共享 schema，因为它们不能证明要求中的 PostgreSQL 数据库账号隔离。测试复用 Compose 的初始化脚本，避免测试与交付配置分叉。

## 安全与交付边界

T01 仅允许公开 `/actuator/health`，保持 CSRF 默认保护，不创建临时万能账号。登录与操作员、审批员权限属于 T06。数据库密码必须通过环境变量注入；示例配置只有本地无害示例值。

前端仅提供环境状态页；开发服务器代理健康检查，不引入后端 CORS 放开配置。真实任务页属于 T12—T13。

容器采用固定版本基础镜像、非 root 运行用户及 Java HTTP 健康探针，无需额外安装 curl。镜像打包与测试分开，测试必须通过 Maven verify 独立执行。T01 尚不设置 CI，完整交付属于 T16。

Testcontainers 使用 Docker API 1.44，以兼容本机 Docker 29 的最低 API 要求；此设置仅在测试资源内生效。
