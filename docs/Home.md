# NeoLinkAPI Java 开发指南

当前仓库只维护 Desktop JVM 与 Android 两个 Java 产物。根目录就是 Java Gradle 工程，Android Library 位于 `android/neolinkapi-android`。

## 入口

- [Java 开发指南](Java)
- [Java API 参考](Java-API-Reference)
- [Android 开发指南](Android)

## 模块边界

- `shared/`：会发布到 Maven 的 Java 公共 API、模型、异常和资源。
- `common/`：Desktop 与 Android 共用的内部隧道运行时。
- `desktop/`：Desktop JVM 封装、透明性检查和测试。
- `android/neolinkapi-android/`：独立 Android Library 工程。
- `protocol/`：协议契约、fixtures 和版本元数据。

## 构建与测试

```cmd
.\gradlew.bat test
```

```cmd
cd android\neolinkapi-android
.\gradlew.bat testDebugUnitTest
```

## 发布

```cmd
.\gradlew.bat publishAggregationToCentralPortal --no-configuration-cache
```

```cmd
cd android\neolinkapi-android
.\gradlew.bat publishAllPublicationsToCentralPortal --no-configuration-cache
```
