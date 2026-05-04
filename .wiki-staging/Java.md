# Java

NeoLinkAPI 的 Java 版面向嵌入式接入场景。它把配置、生命周期、节点发现、错误映射统一封装成一个可关闭实例，适合在业务服务里直接持有并管理。

## 环境与依赖

- Java 21
- Maven 坐标：`top.ceroxe.api:neolinkapi:7.1.6`
- Gradle：

```kotlin
dependencies {
    implementation("top.ceroxe.api:neolinkapi:7.1.6")
}
```

## 最小示例

```java
import top.ceroxe.api.neolink.NeoLinkAPI;
import top.ceroxe.api.neolink.NeoLinkCfg;

public final class Main {
    public static void main(String[] args) throws Exception {
        NeoLinkCfg cfg = new NeoLinkCfg("nps.example.com", 44801, 44802, "your-access-key", 25565)
                .setLocalDomainName("localhost")
                .setTCPEnabled(true)
                .setUDPEnabled(true);

        NeoLinkAPI api = new NeoLinkAPI(cfg);
        Runtime.getRuntime().addShutdownHook(new Thread(api::close));

        try {
            api.start();
            System.out.println(api.getState());
            System.out.println(api.getTunAddr());
        } finally {
            api.close();
        }
    }
}
```

## 核心对象

### `NeoLinkCfg`

这是启动前的配置对象，构造函数强制要求最小必填项：

```java
new NeoLinkCfg(remoteDomainName, hookPort, hostConnectPort, key, localPort)
```

常用字段：

- `remoteDomainName`：NeoProxy 服务端域名或 IP
- `hookPort`：控制连接端口
- `hostConnectPort`：数据转发连接端口
- `localDomainName`：本地服务地址，默认 `localhost`
- `localPort`：本地服务端口
- `key`：访问密钥
- `proxyIPToLocalServer` / `proxyIPToNeoServer`：代理链路，空字符串表示直连
- `heartBeatPacketDelay`：心跳间隔，默认 `1000`
- `tcpEnabled` / `udpEnabled`：是否允许 TCP / UDP 转发
- `ppv2Enabled`：TCP 链路是否启用 Proxy Protocol v2
- `language`：协议握手语言，支持 `NeoLinkCfg.EN_US` 与 `NeoLinkCfg.ZH_CH`
- `clientVersion`：发送给服务端的客户端版本号
- `debugMsg`：是否输出调试信息

这个类是可变的，但所有 setter 都会做输入校验，避免把非法配置推迟到启动阶段才暴露。

### `NeoLinkAPI`

这是唯一的运行期控制入口。

主要方法：

- `NeoLinkAPI(NeoLinkCfg cfg)`：绑定启动配置
- `static String version()`：返回库版本
- `start()` / `start(int connectToNpsTimeoutMillis)`：启动隧道
- `isActive()`：判断实例是否正在运行
- `getTunAddr()`：获取服务端下发的隧道地址
- `getHookSocket()`：获取控制 socket
- `getUpdateURL()`：获取版本不兼容时的更新地址
- `getState()`：获取生命周期状态
- `updateRuntimeProtocolFlags(boolean tcpEnabled, boolean udpEnabled)`：运行中切换协议转发开关
- `close()`：关闭并释放资源

回调设置：

- `setOnStateChanged(...)`
- `setOnError(...)`
- `setOnServerMessage(...)`
- `setUnsupportedVersionDecision(...)`
- `setDebugSink(...)`
- `setOnConnect(...)`
- `setOnDisconnect(...)`
- `setOnConnectNeoFailure(...)`
- `setOnConnectLocalFailure(...)`

行为边界：

- `start()` 会先复制一份 `NeoLinkCfg` 作为运行期配置，避免启动后修改原对象造成半生效状态。
- `updateRuntimeProtocolFlags(...)` 只应该在 active 状态下调用。
- `getTunAddr()` 在地址尚未就绪时会等待，不是纯同步快照读取。
- `setUnsupportedVersionDecision(...)` 的回调只负责决定是否继续请求更新地址，不应该在里面做阻塞型业务逻辑。

### `NeoNode`

`NeoNode` 表示 NKM 下发的公共节点元数据。

```java
NeoNode node = new NeoNode(name, realId, address, iconSvg, hookPort, connectPort);
NeoLinkCfg cfg = node.toCfg("your-access-key", 25565);
```

常用方法：

- `getName()`
- `getRealId()`
- `getAddress()`
- `getIconSvg()`
- `getHookPort()`
- `getConnectPort()`
- `toCfg(String key, int localPort)`

### `NodeFetcher`

`NodeFetcher` 负责从 NKM 节点列表拉取并解析节点。

```java
Map<String, NeoNode> nodes = NodeFetcher.getFromNKM("https://example.com/nkm.json");
Map<String, NeoNode> nodesWithTimeout = NodeFetcher.getFromNKM("https://example.com/nkm.json", 1500);
```

约定：

- 只接受 `http` / `https`
- 根 JSON 必须是数组
- 返回值按 `realId` 作为 key
- 默认端口常量分别是 `44801` 和 `44802`

### `NeoLinkState`

状态枚举只有五个值：

- `STOPPED`
- `STARTING`
- `RUNNING`
- `STOPPING`
- `FAILED`

## 异常映射

Java 版会尽量保留服务端返回值语义，常见异常如下：

- `UnsupportedVersionException`
- `UnSupportHostVersionException`
- `NoSuchKeyException`
- `OutDatedKeyException`
- `UnRecognizedKeyException`
- `NoMoreNetworkFlowException`
- `NoMorePortException`
- `PortOccupiedException`

结论很简单：如果服务端返回的是业务错误，不要在调用方把它当成普通 `IOException` 吞掉，应该显式分支处理。
