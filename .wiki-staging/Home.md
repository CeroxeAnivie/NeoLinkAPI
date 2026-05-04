# NeoLinkAPI Wiki

NeoLinkAPI 是一套同时维护 Java 与 TypeScript/Node.js 两个实现的 NeoLink 协议栈 SDK。这个 Wiki 只记录对外可依赖的行为、配置边界和推荐用法，不重复源码里的内部实现细节。

当前文档结构已经移动到仓库根目录的 `.wiki-staging`，便于后续统一发布与维护。

## 文档导航

- [Java](Java)
- [TypeScript](TypeScript)

## 阅读顺序

1. 先看 `Java` 或 `TypeScript`，根据你实际使用的语言选择对应文档。
2. 再看配置与生命周期部分，确认启动参数、回调和关闭策略。
3. 如果你要从 NKM 节点列表启动，再看节点发现部分。
4. 最后看错误映射，保证服务端返回值和本地异常能对齐。

## 统一约定

- `NeoLinkCfg` 是启动前配置对象，`NeoLinkAPI` 是运行期控制对象。
- `NodeFetcher` 负责从 NKM 拉取节点定义，`NeoNode` 负责承载节点元数据。
- `NeoLinkState` 只表示运行状态，不表示底层网络的全部健康度。
- 所有端口都遵守 `1..65535`。
