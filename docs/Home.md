# NeoLinkAPI 开发指南

NeoLinkAPI 同时提供 Java 和 Node.js 两套实现。这个 Wiki 的目标是让你先用正确的最小调用跑起来，再按需查配置、节点发现和事件回调。

## 入口

- [Java 开发指南](Java)
- [Java API 参考](Java-API-Reference)
- [Android 开发指南](Android)
- [Node.js 开发指南](Node.js)
- [Node.js API 参考](Node.js-API-Reference)

## 先理解这两点

- `NeoLinkCfg` 构造函数已经包含最小必填项，`localhost`、TCP 启用、UDP 启用都是默认值。
- Java 的 `start()` 是阻塞方法；Node.js 的 `api.start()` 返回的 Promise 也会等隧道运行结束。
- Java 和 Node.js 的对象结构保持一致：`NeoLinkCfg` 配置启动参数，`NeoLinkAPI` 管理运行期，`NodeFetcher` 拉取 NKM 节点，
  `NeoNode.toCfg(...)` 转换节点配置；差异主要是 Java 同步阻塞、Node.js 使用 Promise。

## 常用对象

- `NeoLinkCfg`：启动前配置对象。
- `NeoLinkAPI`：运行期控制对象。
- `NeoNode`：NKM 节点对象。
- `NodeFetcher`：NKM 节点拉取工具。

## 命令入口

下面的命令分成两类：

- 仓库根目录命令：在 `NeoLinkAPI` 根目录执行，适合日常开发和同时管理 Java / Node.js。
- 模块目录命令：进入具体模块目录后执行，适合只关注某一个实现。

### 仓库根目录命令

| 目标              | 命令                           | 作用                                        |
|-----------------|------------------------------|-------------------------------------------|
| 安装 Node.js 开发依赖 | `npm install`                | 安装根目录共享的 TypeScript 工具链与 Node.js 依赖       |
| 校验 shared 契约    | `npm run check:shared`       | 检查 `shared/` 下的 JSON 契约与 fixtures 是否格式正确  |
| 构建 Node.js 库    | `npm run build`              | 只编译 `packages/nodejs`，不会触发 Java           |
| 测试 Node.js 库    | `npm test`                   | 只运行 `packages/nodejs` 的构建和测试              |
| 构建 Java 库       | `npm run build:java`         | 从根目录调用 Java 模块的 Gradle Wrapper 执行 `build` |
| 测试 Java 库       | `npm run test:java`          | 从根目录调用 Java 模块的 Gradle Wrapper 执行 `test`  |
| 离线构建 Java 库     | `npm run build:java:offline` | 只使用本地 Gradle 缓存构建 Java                    |
| 离线测试 Java 库     | `npm run test:java:offline`  | 只使用本地 Gradle 缓存运行 Java 测试                 |
| 构建整个 Monorepo   | `npm run build:all`          | 先构建 Java，再构建 Node.js                      |
| 测试整个 Monorepo   | `npm run test:all`           | 先跑 Java 测试，再跑 Node.js 测试                  |
| 离线构建整个 Monorepo | `npm run build:all:offline`  | Java 部分使用离线 Gradle 缓存，然后构建 Node.js        |
| 离线测试整个 Monorepo | `npm run test:all:offline`   | Java 部分使用离线 Gradle 缓存，然后测试 Node.js        |

### 进入 `packages/java` 后的命令

| 目标            | 命令                                                         | 作用                       |
|---------------|------------------------------------------------------------|--------------------------|
| 构建 Java 库     | `.\gradlew.bat build`                                      | 直接在 Java 模块内部构建          |
| 测试 Java 库     | `.\gradlew.bat test`                                       | 直接在 Java 模块内部测试          |
| 离线构建 Java 库   | `.\gradlew.bat build --offline`                            | 只使用本地 Gradle 缓存构建        |
| 离线测试 Java 库   | `.\gradlew.bat test --offline`                             | 只使用本地 Gradle 缓存测试        |
| 清理构建目录        | `.\gradlew.bat clean`                                      | 删除 Java 模块的 `build/` 输出  |
| 生成透明性检查运行时类路径 | `.\gradlew.bat :desktop:printTransparencyRuntimeClasspath` | 给桌面透明性检查脚本输出完整 classpath |
