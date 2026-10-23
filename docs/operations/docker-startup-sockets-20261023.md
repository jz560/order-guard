# 2026-10-23 Docker Desktop 启动故障处理

## 现象与判断

启动连续报错：先是 `Docker/run/dockerInference`，随后是 `docker-secrets-engine/engine.sock`，均提示文件无法访问及监听路径语法错误。检查发现报错叶子是不可正常访问的本地通信对象，而 Docker 数据目录仍指向 D 盘。

两次弹窗指向不同组件，说明只处理第一个目录不足以恢复此次启动。与已公开的 Docker Desktop 残留 socket 启动问题相符：[问题 448](https://github.com/docker/desktop-feedback/issues/448)、[问题 460](https://github.com/docker/desktop-feedback/issues/460)。这些报告仅作为排查参考；本次没有证明残留文件产生的底层原因，也没有据此断言 D 盘数据损坏。

## 本次实际操作

停止 Docker Desktop 并确认其后台进程已退出后，将本次异常通信目录改名保留，再建立空目录并重新启动。没有恢复出厂设置、删除镜像、删除数据卷或重置 WSL。

保留的目录：

- `C:\Users\A\AppData\Local\Docker\run.before-t04-20261023`
- `C:\Users\A\AppData\Local\Docker\run.before-t04-second-20261023`
- `C:\Users\A\AppData\Local\docker-secrets-engine.before-t04-20261023`

第二次处理后 Docker Engine 29.6.2 可响应查询，原有 T01 三个容器与项目镜像均存在。T01 容器此前已停止，本次没有把它们启动为日常服务。Docker 设置 `CustomWslDistroDir` 仍为 `D:\Docker\DockerDesktopWSL`。

之后 T04 的临时 PostgreSQL、Java 容器及真实 SIGKILL 恢复实验成功运行，专用测试容器正常回收。这证明本次引擎已恢复工作；尚未验证未来 Windows/Docker 重启后是否仍会复现。

## 再次发生时

先检查新弹窗的具体路径和 Docker 日志，确认是不是相同残留通信对象问题。保留 D 盘数据与目录备份，避免直接恢复出厂设置。只有在 Docker 退出后才处理相关运行目录；不能把这次局部处理套用到 Docker 数据磁盘或 WSL 发行版。
