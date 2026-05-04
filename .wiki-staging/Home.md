# NeoLinkAPI 开发指南

NeoLinkAPI 同时提供 Java 和 Node.js 两套实现。这个 Wiki 的目标是让你先用正确的最小调用跑起来，再按需查配置、节点发现和事件回调。

## 入口

- [Java 开发指南](Java)
- [Node.js 开发指南](Node.js)

## 先理解这两点

- `NeoLinkCfg` 构造函数已经包含最小必填项，`localhost`、TCP 启用、UDP 启用都是默认值。
- Java 的 `start()` 是阻塞方法；Node.js 的 `api.start()` 返回的 Promise 也会等隧道运行结束。
- Java 和 Node.js 的对象结构保持一致：`NeoLinkCfg` 配置启动参数，`NeoLinkAPI` 管理运行期，`NodeFetcher` 拉取 NKM 节点，`NeoNode.toCfg(...)` 转换节点配置；差异主要是 Java 同步阻塞、Node.js 使用 Promise。

## 常用对象

- `NeoLinkCfg`：启动前配置对象。
- `NeoLinkAPI`：运行期控制对象。
- `NeoNode`：NKM 节点对象。
- `NodeFetcher`：NKM 节点拉取工具。
