# Java 开发指南

Java 版适合直接嵌入业务服务。接入时先明确一个事实：`NeoLinkAPI.start()` 是长运行阻塞方法，它会阻塞到隧道停止、服务端断开、运行期错误，或者其他线程调用 `close()`。

桌面 JVM 产物坐标是 `top.ceroxe.api:neolinkapi-desktop:7.2.0`。共享模型与 NKM 节点工具坐标是 `top.ceroxe.api:neolinkapi-shared:7.2.0`，其中包含 `NodeFetcher`、`NeoNode` 和 `NeoLinkCfg`。Android 产物使用独立坐标 `top.ceroxe.api:neolinkapi-android:7.2.0`，不要在 Android 项目中依赖桌面 JVM 产物。

源码层面，`packages/java/shared` 是会发布的 Java 公共 API 模块；`packages/java/common` 是 Desktop 与 Android 共用的内部运行时实现，不单独发布。根目录 `shared/` 只保存跨语言协议契约、fixtures 和版本元数据。

## 最小调用

`NeoLinkCfg` 构造函数已经包含最小必填项：远端地址、控制端口、转发端口、访问密钥、本地端口。

构造函数参数含义如下：

| 参数 | 示例 | 从哪里来 | 用途 |
| --- | --- | --- | --- |
| `remoteDomainName` | `"nps.example.com"` | NeoProxy/NeoLink 服务端地址，或 NKM 节点的 `address` 字段 | API 会连接这个主机的控制端口和转发端口 |
| `hookPort` | `44801` | NeoProxy/NeoLink 服务端的控制端口，或 NKM 节点的 `HOST_HOOK_PORT`/`hookPort` 字段 | 启动时建立控制连接、发送 key/版本/协议能力、接收服务端指令 |
| `hostConnectPort` | `44802` | NeoProxy/NeoLink 服务端的数据转发端口，或 NKM 节点的 `HOST_CONNECT_PORT`/`connectPort` 字段 | 每次 TCP/UDP 转发会连接这个端口创建数据通道 |
| `key` | `"your-access-key"` | 你在服务端创建隧道/端口时拿到的访问密钥 | 握手鉴权；为空会直接抛 `IllegalArgumentException` |
| `localPort` | `25565` | 你本机实际运行的下游服务端口，例如 Minecraft/HTTP/SSH | 收到公网连接后，API 会把流量转发到 `localDomainName:localPort` |

如果你不是从 NKM 获取节点，就必须从自己的 NeoProxy/NeoLink 服务端配置里填 `remoteDomainName`、`hookPort`、`hostConnectPort`。如果使用 NKM，节点对象会提供前三个值，仍然需要你自己提供 `key` 和 `localPort`。

这些值已有默认配置，不需要在最小调用里重复写：

- `localDomainName` 默认是 `localhost`
- TCP 默认启用
- UDP 默认启用
- 代理默认直连
- 心跳默认 `1000` 毫秒
- PPv2 默认关闭
- debug 默认关闭

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

## 启动后继续执行

如果你还要在启动后读取隧道地址、注册业务逻辑或继续跑主线程，把 `start()` 放到独立线程。`getTunAddr()` 本身也会阻塞，直到服务端下发地址。

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

## 需要时才改默认值

只在业务需要时调用 setter。

```java
NeoLinkCfg cfg = new NeoLinkCfg("nps.example.com", 44801, 44802, "your-access-key", 25565)
        .setLocalDomainName("127.0.0.1")
        .setUDPEnabled(false)
        .setPPV2Enabled(true)
        .setDebugMsg(true);
```

常用配置项：

| 方法 | 默认值 | 什么时候改 | 注意 |
| --- | --- | --- | --- |
| `setLocalDomainName(String)` | `localhost` | 本地服务只监听 `127.0.0.1`、`::1`、容器网关或固定内网地址时 | 这是本地服务地址，不是 NeoProxy/NeoLink 服务端地址 |
| `setTCPEnabled(boolean)` | `true` | 确定不需要 TCP 转发时设为 `false` | 最小调用不需要显式设为 `true` |
| `setUDPEnabled(boolean)` | `true` | 确定不需要 UDP 转发时设为 `false` | 最小调用不需要显式设为 `true` |
| `setProxyIPToNeoServer(String)` | `""`，直连 | 连接 NeoProxy/NeoLink 服务端必须走代理时 | 格式是 `socks->host:port` 或 `http->host:port`，带认证用 `socks->host:port@user;password` |
| `setProxyIPToLocalServer(String)` | `""`，直连 | 连接本地下游服务必须走代理时 | 普通本机服务不要设置 |
| `setHeartBeatPacketDelay(int)` | `1000` 毫秒 | 需要调整控制连接心跳检测间隔时 | 必须大于 `0` |
| `setPPV2Enabled(boolean)` / `setPPV2Enabled()` | `false` | 本地下游是 Nginx/HAProxy 等能解析 Proxy Protocol v2 的服务时 | Minecraft、SSH、RDP、普通 HTTP 应保持关闭 |
| `setLanguage(String)` | `NeoLinkCfg.ZH_CH` | 需要握手使用英文协议提示时 | 支持 `NeoLinkCfg.ZH_CH`、`NeoLinkCfg.EN_US` 及常见别名 |
| `setClientVersion(String)` | 当前包版本 | 测试兼容性或模拟客户端版本时 | 普通调用不要改 |
| `setDebugMsg(boolean)` / `setDebugMsg()` | `false` | 排查连接、握手、转发问题时 | 调试输出走 `setDebugSink(...)` |

## 从 NKM 节点启动

```java
import java.util.Map;
import top.ceroxe.api.neolink.NeoLinkAPI;
import top.ceroxe.api.neolink.NeoLinkCfg;
import top.ceroxe.api.neolink.NeoNode;
import top.ceroxe.api.neolink.NodeFetcher;

Map<String, NeoNode> nodes = NodeFetcher.getFromNKM("https://example.com/nkm.json");
NeoNode node = nodes.values().stream().findFirst().orElseThrow();
NeoLinkCfg cfg = node.toCfg("your-access-key", 25565);
NeoLinkAPI api = new NeoLinkAPI(cfg);
```

NKM 节点字段含义：

| 字段/方法 | 含义 |
| --- | --- |
| `realId` / `getRealId()` | NKM 节点稳定 ID，`NodeFetcher.getFromNKM(...)` 返回的 `Map` 使用它作为 key |
| `name` / `getName()` | 节点展示名称，只用于 UI/日志选择 |
| `address` / `getAddress()` | NeoProxy/NeoLink 服务端地址，会变成 `NeoLinkCfg.remoteDomainName` |
| `HOST_HOOK_PORT` 或 `hookPort` / `getHookPort()` | 控制端口，会变成 `NeoLinkCfg.hookPort`；缺省时使用 `44801` |
| `HOST_CONNECT_PORT` 或 `connectPort` / `getConnectPort()` | 数据转发端口，会变成 `NeoLinkCfg.hostConnectPort`；缺省时使用 `44802` |
| `icon` 或 `iconSvg` / `getIconSvg()` | 可选 SVG 图标，隧道启动不依赖它 |
| `toCfg(key, localPort)` | 把节点里的地址和端口，加上你的访问密钥和本地端口，转换成可启动配置 |

`NodeFetcher.getFromNKM(url)` 默认超时是 `1000` 毫秒，也可以传入自定义超时：

```java
Map<String, NeoNode> nodes = NodeFetcher.getFromNKM("https://example.com/nkm.json", 1500);
```

## 常用回调

```java
api.setOnStateChanged(state -> System.out.println("state = " + state));
api.setOnError((message, cause) -> {
    System.err.println(message);
    if (cause != null) {
        cause.printStackTrace();
    }
});
api.setOnConnect((protocol, source, target) ->
        System.out.println("connect " + protocol + " " + source + " -> " + target));
api.setOnDisconnect((protocol, source, target) ->
        System.out.println("disconnect " + protocol + " " + source + " -> " + target));
api.setOnTraffic((protocol, direction, bytes) ->
        System.out.println("traffic " + protocol + " " + direction + " +" + bytes));
```

回调参数含义：

| 回调 | 参数 | 触发时机 |
| --- | --- | --- |
| `setOnStateChanged(Consumer<NeoLinkState>)` | `state` 是 `STOPPED`、`STARTING`、`RUNNING`、`STOPPING`、`FAILED` 之一 | 生命周期状态变化时 |
| `setOnError(BiConsumer<String, Throwable>)` | `message` 是可读错误说明，`cause` 是原始异常 | 启动、握手、运行期或转发连接失败时 |
| `setOnServerMessage(Consumer<String>)` | `message` 是服务端原始文本消息 | 服务端发送非命令消息时，也包括可能携带公网访问地址的消息 |
| `setOnConnect(ConnectionEventHandler)` | `protocol` 是 `TCP`/`UDP`，`source` 是公网来源地址，`target` 是本地下游地址 | 单条 TCP/UDP 转发连接建立时 |
| `setOnDisconnect(ConnectionEventHandler)` | 参数同 `setOnConnect` | 单条 TCP/UDP 转发连接结束时 |
| `setOnTraffic(TrafficEventHandler)` | `protocol` 是 `TCP`/`UDP`，`direction` 是 `NEO_TO_LOCAL`/`LOCAL_TO_NEO`，`bytes` 是业务负载字节数 | 单条 TCP/UDP 转发链路成功写出业务数据后 |
| `setOnConnectNeoFailure(Runnable)` | 无参数 | 数据通道连接 NeoProxy/NeoLink 服务端失败时 |
| `setOnConnectLocalFailure(Runnable)` | 无参数 | TCP 转发连接本地下游服务失败时 |
| `setUnsupportedVersionDecision(Function<String, Boolean>)` | `response` 是服务端返回的不兼容版本提示；返回 `true` 会继续协商更新地址 | 服务端认为客户端版本不兼容时 |
| `setDebugSink(BiConsumer<String, Throwable>)` | `message` 是调试文本，`cause` 是可选异常 | `debugMsg` 启用后接收诊断细节 |

## 常用对象

`NeoLinkCfg` 负责启动前配置。构造函数参数不能省略，因为这五个值没有安全的通用默认值；setter 只用于覆盖默认值或运行前调整配置。

`NeoLinkAPI` 负责运行期控制。常用方法说明：

| 方法 | 作用 |
| --- | --- |
| `start()` | 阻塞运行隧道，直到隧道结束或 `close()` 被调用 |
| `start(int connectToNpsTimeoutMillis)` | 同 `start()`，但自定义连接 NeoProxy/NeoLink 服务端的超时时间，单位毫秒，必须大于 `0` |
| `getTunAddr()` | 等待并返回服务端下发的公网访问地址；未收到地址前会阻塞 |
| `getState()` | 返回当前生命周期状态 |
| `isActive()` | 返回当前实例是否仍处于运行状态 |
| `getUpdateURL()` | 版本不兼容且选择更新后，返回服务端协商出的更新地址；没有则为 `null` |
| `updateRuntimeProtocolFlags(boolean tcpEnabled, boolean udpEnabled)` | 运行中向服务端请求切换 TCP/UDP 能力；启动前不用它，直接用配置默认值或 setter |
| `isPPV2Enabled()` | 查询当前 PPv2 透传状态；运行中读取运行期配置，未运行时读取初始配置 |
| `setPPV2Enabled(boolean)` / `setPPV2Enabled()` | 运行中切换 PPv2 透传；不通知服务端，只影响之后新建的 TCP 连接 |
| `close()` | 请求停止隧道并关闭控制/转发连接，适合放进 shutdown hook |

`NeoNode` 表示 NKM 节点。常用方法是 `getName()`、`getRealId()`、`getAddress()`、`getHookPort()`、`getConnectPort()` 和 `toCfg(...)`。

`NeoLinkState` 的状态值是 `STOPPED`、`STARTING`、`RUNNING`、`STOPPING`、`FAILED`。

## 常见错误

- 不要把 `start()` 当成“启动后立刻返回”的方法。
- 不要在最小调用里重复写默认值，除非你想明确覆盖配置。
- `getTunAddr()` 会等待服务端下发地址。
- 运行中切换协议用 `updateRuntimeProtocolFlags(...)`，启动前默认 TCP/UDP 已启用。
- 运行中切换 PPv2 用 `api.setPPV2Enabled(...)`；已建立的 TCP 连接保持创建时的 PPv2 行为，后续新 TCP 连接使用新值。

## 构建与测试命令

Java 模块有两种常用入口：在仓库根目录通过 npm 脚本调用，或者直接进入 `packages/java` 用 Gradle Wrapper 调用。两种写法作用相同，区别只是你当前站在哪个目录。

### 在仓库根目录执行

| 目标 | 命令 | 作用 |
| --- | --- | --- |
| 构建 Java 库 | `npm run build:java` | 从根目录调用 Java 模块的 `gradlew.bat build` |
| 测试 Java 库 | `npm run test:java` | 从根目录调用 Java 模块的 `gradlew.bat test` |
| 离线构建 Java 库 | `npm run build:java:offline` | 使用本地 Gradle 缓存构建，不访问网络 |
| 离线测试 Java 库 | `npm run test:java:offline` | 使用本地 Gradle 缓存测试，不访问网络 |
| 构建整个 Monorepo | `npm run build:all` | 依次构建 Java、Android 和 Node.js |
| 测试整个 Monorepo | `npm run test:all` | 依次运行 Java、Android Debug 单测和 Node.js 测试 |
| 离线构建整个 Monorepo | `npm run build:all:offline` | Java 与 Android 离线构建后，再构建 Node.js |
| 离线测试整个 Monorepo | `npm run test:all:offline` | Java 与 Android 离线测试后，再测试 Node.js |

### 进入 `packages/java` 后执行

先进入目录：

```cmd
cd packages\java
```

然后根据目标执行：

| 目标 | 命令 | 作用 |
| --- | --- | --- |
| 构建 Java 库 | `.\gradlew.bat build` | 编译源码、处理资源、打包产物 |
| 测试 Java 库 | `.\gradlew.bat test` | 编译测试代码并运行测试 |
| 离线构建 Java 库 | `.\gradlew.bat build --offline` | 只使用本地 Gradle 缓存构建 |
| 离线测试 Java 库 | `.\gradlew.bat test --offline` | 只使用本地 Gradle 缓存测试 |
| 清理构建目录 | `.\gradlew.bat clean` | 删除 `build/` 输出目录 |
| 生成透明性检查 classpath | `.\gradlew.bat :desktop:printTransparencyRuntimeClasspath` | 输出桌面透明性检查脚本所需的完整运行时 classpath |
| 运行透明性检查 | `.\desktop\run-transparency-check.cmd` | 运行桌面透明性 server/client 联调检查 |

如果你的目的只是开发或发布 Java 模块，推荐直接用上面这组 Java 命令，不需要先构建 Node.js。
