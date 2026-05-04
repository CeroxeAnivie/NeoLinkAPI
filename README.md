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

## API 结构一致性

Java 和 Node.js 两个库的核心结构保持一致，只是语言运行模型不同：

| 概念 | Java | Node.js | 说明 |
| --- | --- | --- | --- |
| 启动前配置 | `NeoLinkCfg` | `NeoLinkCfg` | 构造参数、默认值和 setter 语义保持一致 |
| 运行期对象 | `NeoLinkAPI` | `NeoLinkAPI` | 负责启动、停止、状态、回调和运行期协议切换 |
| NKM 节点 | `NeoNode` | `NeoNode` | 表示从 NKM 拉取到的公共节点元数据 |
| NKM 拉取 | `NodeFetcher.getFromNKM(...)` | `NodeFetcher.getFromNKM(...)` | Java 同步返回 `Map`，Node.js 返回 `Promise<Map>` |
| 启动方法 | `api.start()` | `await api.start()` | 都会等待隧道运行结束；Java 阻塞当前线程，Node.js 返回长运行 Promise |
| 隧道地址 | `api.getTunAddr()` | `await api.getTunAddr()` | 都会等服务端下发公网访问地址 |
| 停止方法 | `api.close()` | `api.close()` | 都用于停止隧道并释放连接 |

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
    <version>7.1.6</version>
</dependency>
```

### Gradle Kotlin DSL

```kotlin
dependencies {
    implementation("top.ceroxe.api:neolinkapi:7.1.6")
}
```

### Gradle Groovy DSL

```groovy
dependencies {
    implementation 'top.ceroxe.api:neolinkapi:7.1.6'
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

根目录的 `build` 和 `test` 面向 npm 包开发，默认只处理 Node.js 库：

```cmd
npm install
npm run build
npm test
```

需要一次性验证整个 Monorepo 时使用：

```cmd
npm run build:all
npm run test:all
```

单独构建 Java：

```cmd
cd packages\java
.\gradlew.bat build
```

单独构建 Node.js：

```cmd
npm run build -w neolinkapi
npm run test -w neolinkapi
```
