# NeoLinkAPI Java

NeoLinkAPI Java 维护 Desktop JVM 和 Android 两个 Java 产物，当前分支只保留 Java/Android 工程。

完整开发指南请看 GitHub Wiki：

- [NeoLinkAPI 开发指南](https://github.com/CeroxeAnivie/NeoLinkAPI/wiki)
- [Java 开发指南](https://github.com/CeroxeAnivie/NeoLinkAPI/wiki/Java)
- [Android 开发指南](https://github.com/CeroxeAnivie/NeoLinkAPI/wiki/Android)

## 项目结构

```text
packages/
  java/
    common/                      Java 17 共享隧道生命周期和代理实现
    shared/                      Java 17 共享协议模型、异常与纯工具逻辑
    desktop/                     Java 17 桌面 JVM 实现与测试，Java 21+ 运行时自动启用虚拟线程执行器
    android/neolinkapi-android/  独立 Android Library 工程，产出 AAR
shared/      Java 文档和测试使用的协议契约、fixtures、Mock 数据和版本元数据
```

Java 侧采用平台拆分结构：

- `packages/java/shared`
  Java 生态中对外发布的公共 API 模块，包含配置、节点模型、NKM 拉取、异常类型、协议文本工具和资源文件。
- `packages/java/common`
  不单独发布的内部源码复用层，包含 Desktop 与 Android 共用的隧道生命周期、控制连接、转发调度和代理实现。
- `packages/java/desktop`
  桌面 JVM 打包层，维持 Java 17 发布基线，并依赖 Java 17 字节码的 `top.ceroxe.api:ceroxe-core-shared:2.0.2`。
- `packages/java/android/neolinkapi-android`
  独立 Android Gradle Library，复用 `common` 隧道实现和 `shared` 公共 API 源码，依赖 `top.ceroxe.api:ceroxe-core-android`，发布真实 Android AAR。

`NodeFetcher`、`NeoNode`、`NeoLinkCfg`、异常类型和协议文本工具属于 Java 公共 API，位于 `packages/java/shared`，并随 `neolinkapi-shared` 发布。

## 安装

### Maven

```xml
<dependency>
    <groupId>top.ceroxe.api</groupId>
    <artifactId>neolinkapi-desktop</artifactId>
    <version>7.2.2</version>
</dependency>
```

共享模型与 NKM 节点工具：

```xml
<dependency>
    <groupId>top.ceroxe.api</groupId>
    <artifactId>neolinkapi-shared</artifactId>
    <version>7.2.2</version>
</dependency>
```

Android 产物：

```xml
<dependency>
    <groupId>top.ceroxe.api</groupId>
    <artifactId>neolinkapi-android</artifactId>
    <version>7.2.2</version>
</dependency>
```

## Java 快速开始

```java
import top.ceroxe.api.neolink.NeoLinkAPI;
import top.ceroxe.api.neolink.NeoLinkCfg;

public final class JavaExample {
    public static void main(String[] args) throws Exception {
        NeoLinkAPI api = new NeoLinkAPI(
                new NeoLinkCfg("nps.example.com", 44801, 44802, "your-access-key", 25565)
        );

        Runtime.getRuntime().addShutdownHook(new Thread(api::close));
        api.start();
    }
}
```

## Android 快速开始

Android 版是 Android Library，不是桌面 JVM 工具类。`start()` 是长运行阻塞方法，不能在主线程调用。

```java
import android.util.Log;
import top.ceroxe.api.neolink.NeoLinkAPI;
import top.ceroxe.api.neolink.NeoLinkCfg;

NeoLinkAPI api = new NeoLinkAPI(
        new NeoLinkCfg("nps.example.com", 44801, 44802, "your-access-key", 25565)
).bindAndroidLog("NeoLinkAPI");

Thread tunnelThread = new Thread(() -> {
    try {
        api.start();
    } catch (Exception e) {
        Log.e("NeoLinkAPI", "Tunnel stopped.", e);
    }
}, "neolink-android");
tunnelThread.start();
```

## 构建与测试

Java 工程使用仓库内已有的 Gradle Wrapper。以下命令均使用 CMD 写法。

```cmd
cd packages\java
.\gradlew.bat build
.\gradlew.bat test
```

Android Library：

```cmd
cd packages\java\android\neolinkapi-android
.\gradlew.bat build
.\gradlew.bat test
```

离线构建可在对应 Gradle Wrapper 后追加 `--offline`。

## Maven Central 发布

Java 桌面与 shared 是聚合发布，必须在 `packages\java` 执行聚合任务：

```cmd
cd packages\java
.\gradlew.bat publishAggregationToCentralPortal --no-configuration-cache
```

Android 是独立 Gradle 工程：

```cmd
cd packages\java\android\neolinkapi-android
.\gradlew.bat publishAllPublicationsToCentralPortal --no-configuration-cache
```

Maven Central Portal 凭据和 GPG 私钥只放在本机用户级配置或系统密钥环里，不写入仓库。
