# NeoLinkAPI

NeoLinkAPI 是同一套 NeoLink 协议栈的 Monorepo，维护 Java、Node.js 和 Android 三个实现。

完整开发指南请看 GitHub Wiki：

- [NeoLinkAPI 开发指南](https://github.com/CeroxeAnivie/NeoLinkAPI/wiki)
- [Java 开发指南](https://github.com/CeroxeAnivie/NeoLinkAPI/wiki/Java)
- [Android 开发指南](https://github.com/CeroxeAnivie/NeoLinkAPI/wiki/Android)
- [Node.js 开发指南](https://github.com/CeroxeAnivie/NeoLinkAPI/wiki/Node.js)

## 项目结构

```text
packages/
  java/
    shared/                      Java 17 共享协议模型、异常与纯工具逻辑
    desktop/                     Java 21 桌面 JVM 实现与测试
    android/neolinkapi-android/  独立 Android Library 工程，产出 AAR
  nodejs/    Node.js 包，源码使用 TypeScript 编写，使用 npm 构建与发布
shared/      两种实现共享的协议契约、fixtures 和 Mock 数据
```

`shared` 只存放跨语言共享事实，不放运行时代码。协议、默认值、握手样例和 NKM fixtures 应先落在这里，再同步到两种实现。

Java 侧采用平台拆分结构：

- `packages/java/shared`
  共享 Java 17 源码与资源，只允许放跨平台逻辑，不能依赖桌面专属或 Android 专属运行时 API。
- `packages/java/desktop`
  桌面 JVM 打包层，继续保持 Java 21，并依赖 `top.ceroxe.api:ceroxe-core` 与 `top.ceroxe.api:ceroxe-detector`。
- `packages/java/android/neolinkapi-android`
  独立 Android Gradle Library，复用 `shared` 源码，依赖 `top.ceroxe.api:ceroxe-core-android`，发布真实 Android AAR。
  该工程的源码来源分为三部分：
  - `../../shared/src/main/java`
    Java 与 Android 共用的协议模型、异常和纯工具源码。
  - `../../shared/src/main/resources`
    Java 与 Android 共用的资源文件，例如 `api.properties`。
  - `src/main/java`
    只属于 Android 运行时的实现，例如 Android 版 `NeoLinkAPI`、`NodeFetcher` 和网络线程实现。

这个边界是刻意设计的：

- `shared` 与 `android` 维持 Java 17，保证 Android 可复用。
- `desktop` 保持 Java 21，只承载桌面增强能力，例如虚拟线程和桌面专属实现。
- Android 打包禁止回退到旧的桌面依赖坐标，也不依赖本地陈旧 Maven 缓存。

桌面 JVM 产物的 Maven 坐标是：

```xml
<dependency>
    <groupId>top.ceroxe.api</groupId>
    <artifactId>neolinkapi-desktop</artifactId>
    <version>7.1.9</version>
</dependency>
```

Android 产物的 Maven 坐标是：

```xml
<dependency>
    <groupId>top.ceroxe.api</groupId>
    <artifactId>neolinkapi-android</artifactId>
    <version>7.1.9</version>
</dependency>
```

Android 工程是真正的 Android Library 构建，因此本地开发环境还需要 Android SDK。可任选以下方式提供：

- 设置 `ANDROID_HOME`
- 设置 `ANDROID_SDK_ROOT`
- 在 `packages/java/android/neolinkapi-android` 下创建 `local.properties`，写入 `sdk.dir=<Android SDK 绝对路径>`

## 安装

### Node.js

```cmd
npm install neolinkapi
```

### Maven

```xml
<dependency>
    <groupId>top.ceroxe.api</groupId>
    <artifactId>neolinkapi-desktop</artifactId>
    <version>7.1.9</version>
</dependency>
```

### Gradle Kotlin DSL

```kotlin
dependencies {
    implementation("top.ceroxe.api:neolinkapi-desktop:7.1.9")
}
```

### Gradle Groovy DSL

```groovy
dependencies {
    implementation 'top.ceroxe.api:neolinkapi-desktop:7.1.9'
}
```

## Java 快速开始

`NeoLinkCfg` 构造函数已经包含最小必填项。`localDomainName` 默认是 `localhost`，TCP 和 UDP 默认都是启用状态，所以最小配置不需要重复设置这些默认项。

构造参数从左到右是：`remoteDomainName`、`hookPort`、`hostConnectPort`、`key`、`localPort`。前三个来自 NeoProxy/NeoLink 服务端或 NKM 节点，`key` 是服务端访问密钥，`localPort` 是本机下游服务端口。

`NeoLinkAPI.start()` 是长运行阻塞方法：它会阻塞到隧道停止、服务端断开、运行期错误或其他线程调用 `close()`。

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

如果启动后还要继续执行业务逻辑，把 `start()` 放到独立线程：

```java
NeoLinkAPI api = new NeoLinkAPI(
        new NeoLinkCfg("nps.example.com", 44801, 44802, "your-access-key", 25565)
);

Thread tunnelThread = Thread.ofVirtual().start(() -> {
    try {
        api.start();
    } catch (Exception e) {
        e.printStackTrace();
    }
});

System.out.println("tunAddr = " + api.getTunAddr());

Runtime.getRuntime().addShutdownHook(new Thread(() -> {
    api.close();
    try {
        tunnelThread.join();
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
    }
}));
```

## Android 快速开始

Android 版同样使用 `NeoLinkCfg` 和 `NeoLinkAPI` 两个核心对象，但它是 Android Library，不是桌面 JVM 工具类。最重要的约束只有一条：

- `start()` 不能在主线程调用

推荐的承载方式：

- 普通后台线程
- Kotlin 协程里的 `Dispatchers.IO`
- 长连接或常驻场景使用前台服务

最小调用示例：

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

如果你的项目使用 Kotlin 协程，推荐这样接：

```kotlin
val api = NeoLinkAPI(
    NeoLinkCfg("nps.example.com", 44801, 44802, "your-access-key", 25565)
).bindAndroidLog("NeoLinkAPI")

scope.launch(Dispatchers.IO) {
    api.start()
}
```

`bindAndroidLog(...)` 会把生命周期、服务端消息、运行时错误和调试事件写进 `android.util.Log`。如果你有自己的埋点、崩溃上报或结构化日志系统，仍然可以继续调用 `setOnError(...)`、`setOnStateChanged(...)`、`setOnServerMessage(...)` 和 `setDebugSink(...)` 覆盖默认行为。

## Node.js 快速开始

Node.js 版同样有默认值：`localDomainName` 默认是 `localhost`，TCP 和 UDP 默认都是启用状态。当前 Node.js 实现的 UDP 转发只监听 IPv4，本地下游 UDP 服务请使用 IPv4 地址，例如 `127.0.0.1` 或 IPv4 局域网地址；TCP 不受这个限制。

`NeoLinkCfg` 构造参数和 Java 一致：`remoteDomainName`、`hookPort`、`hostConnectPort`、`key`、`localPort`。前三个来自 NeoProxy/NeoLink 服务端或 NKM 节点，`key` 是服务端访问密钥，`localPort` 是本机下游服务端口。

`await api.start()` 会等待隧道运行结束，不是“启动后立刻返回”。如果你只想把隧道作为当前进程的主任务，直接 `await` 即可。

```ts
import { NeoLinkAPI, NeoLinkCfg } from 'neolinkapi';

async function main(): Promise<void> {
  const api = new NeoLinkAPI(
    new NeoLinkCfg('nps.example.com', 44801, 44802, 'your-access-key', 25565)
  );

  process.once('SIGINT', () => api.close());
  await api.start();
}

void main();
```

如果启动后还要继续执行其他逻辑，把 `start()` 的 Promise 留在后台，并用 `getTunAddr()` 等待地址就绪：

```ts
import { NeoLinkAPI, NeoLinkCfg } from 'neolinkapi';

async function main(): Promise<void> {
  const api = new NeoLinkAPI(
    new NeoLinkCfg('nps.example.com', 44801, 44802, 'your-access-key', 25565)
  );

  process.once('SIGINT', () => api.close());

  const running = api.start().catch((error) => {
    console.error(error);
    throw error;
  });

  console.log('tunAddr =', await api.getTunAddr());
  await running;
}

void main();
```

## 构建与测试

下面所有命令都在仓库根目录执行，也就是 `NeoLinkAPI` 根目录。

| 目标 | 命令 |
| --- | --- |
| 安装 Node.js 开发依赖 | `npm install` |
| 校验 shared JSON 契约 | `npm run check:shared` |
| 构建 Node.js 库 | `npm run build` 或 `npm run build:nodejs` |
| 测试 Node.js 库 | `npm test` 或 `npm run test:nodejs` |
| 构建 Java 库 | `npm run build:java` |
| 测试 Java 库 | `npm run test:java` |
| 构建 Android Java 库 | `npm run build:java:android` |
| 测试 Android Java 库 | `npm run test:java:android` |
| 快速测试 Android Debug 单测 | `npm run test:java:android:fast` |
| 离线构建 Java 库 | `npm run build:java:offline` |
| 离线测试 Java 库 | `npm run test:java:offline` |
| 构建 Java + Node.js | `npm run build:all` |
| 测试 Java + Node.js | `npm run test:all` |
| 离线构建 Java + Node.js | `npm run build:all:offline` |
| 离线测试 Java + Node.js | `npm run test:all:offline` |

Java 命令会从根目录直接调用 `packages\java\gradlew.bat`，不需要手动 `cd packages\java`。

如果你更习惯直接进入 Java 模块目录，下面这些命令与根目录脚本是等价的：

| 执行位置 | 目标 | 命令 | 作用 |
| --- | --- | --- | --- |
| 仓库根目录 | 构建 Java 库 | `npm run build:java` | 调用 `packages/java/gradlew.bat build`，生成 Java 库产物 |
| 仓库根目录 | 测试 Java 库 | `npm run test:java` | 运行 Java 单元测试与集成测试 |
| 仓库根目录 | 离线构建 Java 库 | `npm run build:java:offline` | 只使用本地 Gradle 缓存构建 Java 库 |
| 仓库根目录 | 离线测试 Java 库 | `npm run test:java:offline` | 只使用本地 Gradle 缓存运行 Java 测试 |
| `packages\java` | 构建 Java 库 | `.\gradlew.bat build` | 直接在 Java 模块内部构建 |
| `packages\java` | 测试 Java 库 | `.\gradlew.bat test` | 直接在 Java 模块内部测试 |
| `packages\java` | 离线构建 Java 库 | `.\gradlew.bat build --offline` | 不访问网络，直接使用本地 Gradle 缓存构建 |
| `packages\java` | 离线测试 Java 库 | `.\gradlew.bat test --offline` | 不访问网络，直接使用本地 Gradle 缓存测试 |
| `packages\java` | 清理构建产物 | `.\gradlew.bat clean` | 删除 Java 模块的 `build/` 输出目录 |
| `packages\java` | 生成透明性检查运行时类路径 | `.\gradlew.bat :desktop:printTransparencyRuntimeClasspath` | 给桌面透明性检查脚本输出完整测试运行时 classpath |
| `packages\java\android\neolinkapi-android` | 构建 Android AAR | `.\gradlew.bat build` | 直接构建 Android Library 与发布产物 |
| `packages\java\android\neolinkapi-android` | 测试 Android 单元测试 | `.\gradlew.bat test` | 运行 Android Library 的全部单元测试 |
| `packages\java\android\neolinkapi-android` | 快速测试 Android Debug 单测 | `.\gradlew.bat testDebugUnitTest` | 只跑 Debug 变体，适合日常快速回归 |
| `packages\java\desktop` | 运行透明性检查 | `.\run-transparency-check.cmd` | 启动桌面透明性 server/client 联调检查 |
