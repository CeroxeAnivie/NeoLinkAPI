# NeoLinkAPI Java 开发指南

NeoLinkAPI Java 分支维护 Desktop JVM 和 Android 两个 Java 产物，当前分支只保留 Java/Android 工程。

## 入口

- [Java 开发指南](Java)
- [Java API 参考](Java-API-Reference)
- [Android 开发指南](Android)

## 先理解这两点

- `NeoLinkCfg` 构造函数已经包含最小必填项，`localhost`、TCP 启用、UDP 启用都是默认值。
- Java 的 `start()` 是阻塞方法，会一直运行到隧道停止、服务端断开、运行期错误或其他线程调用 `close()`。
- `packages/java/shared` 是会发布到 Maven 的 Java 公共 API 模块；`packages/java/common` 是 Desktop 与 Android 共用的内部源码复用层。

## 常用对象

- `NeoLinkCfg`：启动前配置对象。
- `NeoLinkAPI`：运行期控制对象。
- `NeoNode`：NKM 节点对象。
- `NodeFetcher`：NKM 节点拉取工具。

## 构建与测试

### Desktop JVM 和 shared

```cmd
cd packages\java
.\gradlew.bat build
.\gradlew.bat test
```

### Android Library

```cmd
cd packages\java\android\neolinkapi-android
.\gradlew.bat build
.\gradlew.bat test
```

## Maven Central 发布

Java 桌面与 shared 是聚合发布，进入 `packages\java` 后执行：

```cmd
.\gradlew.bat publishAggregationToCentralPortal --no-configuration-cache
```

Android 是独立 Gradle 工程，进入 `packages\java\android\neolinkapi-android` 后执行：

```cmd
.\gradlew.bat publishAllPublicationsToCentralPortal --no-configuration-cache
```
