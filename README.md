# NeoLinkAPI Java

NeoLinkAPI Java 是 NeoLink/NeoProxy 的 Java 17 TCP/UDP 隧道客户端，当前仓库只维护 Desktop JVM 与 Android 两个 Java 产物。

## 目录结构

```text
common/                     Desktop 与 Android 共用的隧道运行时实现
shared/                     会发布到 Maven 的 Java 公共 API、模型、异常和资源
desktop/                    Desktop JVM 封装、透明性检查和测试
android/neolinkapi-android/ Android Library 工程
protocol/                   协议契约、fixtures 和版本元数据
```

## Maven 坐标

```xml
<dependency>
    <groupId>top.ceroxe.api</groupId>
    <artifactId>neolinkapi-desktop</artifactId>
    <version>7.2.2</version>
</dependency>
```

```xml
<dependency>
    <groupId>top.ceroxe.api</groupId>
    <artifactId>neolinkapi-shared</artifactId>
    <version>7.2.2</version>
</dependency>
```

```xml
<dependency>
    <groupId>top.ceroxe.api</groupId>
    <artifactId>neolinkapi-android</artifactId>
    <version>7.2.2</version>
</dependency>
```

## 快速开始

```java
import top.ceroxe.api.neolink.NeoLinkAPI;
import top.ceroxe.api.neolink.NeoLinkCfg;

public final class Main {
    public static void main(String[] args) throws Exception {
        NeoLinkAPI api = new NeoLinkAPI(
                new NeoLinkCfg("nps.example.com", 44801, 44802, "your-access-key", 25565)
        );

        Runtime.getRuntime().addShutdownHook(new Thread(api::close));
        api.start();
    }
}
```

## 构建与测试

```cmd
.\gradlew.bat test
```

```cmd
cd android\neolinkapi-android
.\gradlew.bat testDebugUnitTest
```

## 发布

Java Desktop/shared 聚合发布：

```cmd
.\gradlew.bat publishAggregationToCentralPortal --no-configuration-cache
```

Android 发布：

```cmd
cd android\neolinkapi-android
.\gradlew.bat publishAllPublicationsToCentralPortal --no-configuration-cache
```
