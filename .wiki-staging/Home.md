# NeoLinkAPI 开发指南

NeoLinkAPI 同时提供 Java 和 Node.js 两套实现。这个 Wiki 的目标很简单：让你先照着最少步骤跑起来，再去查更细的 API。

## 先看这个

- 你只想快速接入：先看对应语言页的“最小可用示例”。
- 你已经知道要怎么接：直接跳到“常见场景”。
- 你在排障：看“关闭与错误处理”和“节点发现”。

## 页面导航

- [Java](Java)
- [Node.js](Node.js)

## 统一理解

- `NeoLinkCfg` 是启动前配置对象。
- `NeoLinkAPI` 是运行期控制对象。
- `NeoNode` 表示一个 NKM 节点。
- `NodeFetcher` 负责从 NKM 拉取节点列表。
- 所有端口都必须在 `1..65535`。
