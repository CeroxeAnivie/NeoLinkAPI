# NeoLinkAPI

NeoLinkAPI 是同一套 NeoLink 协议栈的 Monorepo，维护 Java 和 Node.js 两个实现。

完整开发指南请看 GitHub Wiki：

- [NeoLinkAPI 开发指南](https://github.com/CeroxeAnivie/NeoLinkAPI/wiki)
- [Java 开发指南](https://github.com/CeroxeAnivie/NeoLinkAPI/wiki/Java)
- [Node.js 开发指南](https://github.com/CeroxeAnivie/NeoLinkAPI/wiki/Node.js)

## 项目结构

```text
packages/
  java/      Java 库，使用 Gradle Wrapper 构建与发布
  nodejs/    Node.js 包，源码使用 TypeScript 编写，使用 npm 构建与发布
shared/      两种实现共享的协议契约、fixtures 和 Mock 数据
```

`shared` 只存放跨语言共享事实，不放运行时代码。协议、默认值、握手样例和 NKM fixtures 应先落在这里，再同步到两种实现。

## 安装

### Node.js

```cmd
npm install neolinkapi
```

### Maven

```xml
<dependency>
    <groupId>top.ceroxe.api</groupId>
    <artifactId>neolinkapi</artifactId>
    <version>7.1.7</version>
</dependency>
```

### Gradle Kotlin DSL

```kotlin
dependencies {
    implementation("top.ceroxe.api:neolinkapi:7.1.7")
}
```

### Gradle Groovy DSL

```groovy
dependencies {
    implementation 'top.ceroxe.api:neolinkapi:7.1.7'
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

## Node.js 快速开始

Node.js 版同样有默认值：`localDomainName` 默认是 `localhost`，TCP 和 UDP 默认都是启用状态。

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
| `packages\java` | 生成透明性检查运行时类路径 | `.\gradlew.bat printTransparencyRuntimeClasspath` | 给透明性检查脚本输出完整测试运行时 classpath |
