# NeoLinkAPI Wiki

NeoLinkAPI 是一个嵌入式 Java 隧道 API，用于对接 NeoProxy/NeoLink 服务器，建立 TCP/UDP 转发并在运行时管理连接状态。

这份 Wiki 只记录逐方法语义、约束和推荐用法。README 只保留最小引入示例。

## 页面索引

- [API Reference](API-Reference)
- [Configuration](Configuration)
- [Node Discovery](Node-Discovery)
- [Lifecycle](Lifecycle)
- [Error Mapping](Errors)

## 阅读顺序

1. 先看 `Configuration`，理解 `NeoLinkCfg` 和 `NeoNode` 的输入边界。
2. 再看 `Node Discovery`，理解 NKM 节点列表如何转换成配置。
3. 然后看 `Lifecycle`，理解 `NeoLinkAPI` 的启动、回调和运行时切换。
4. 最后看 `Error Mapping`，把服务端返回值和异常类型对齐。
