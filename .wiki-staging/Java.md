# Java 开发指南

Java 版适合直接嵌入业务服务。最常见的用法不是研究全部方法，而是先把配置、启动、关闭这三件事做对。

## 最小可用示例

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
            System.out.println("state = " + api.getState());
            System.out.println("tunAddr = " + api.getTunAddr());
        } finally {
            api.close();
        }
    }
}
```

## 最常见的三种调用

### 1. 直连本地服务

只要服务端地址、本地端口和访问密钥确定，直接构造配置就行。

```java
NeoLinkCfg cfg = new NeoLinkCfg("nps.example.com", 44801, 44802, "your-access-key", 25565)
        .setLocalDomainName("localhost")
        .setTCPEnabled(true)
        .setUDPEnabled(true);
```

### 2. 从 NKM 节点启动

如果你手上是节点列表，先取节点，再转成配置。

```java
import java.util.Map;
import top.ceroxe.api.neolink.NeoNode;
import top.ceroxe.api.neolink.NodeFetcher;
import top.ceroxe.api.neolink.NeoLinkCfg;

Map<String, NeoNode> nodes = NodeFetcher.getFromNKM("https://example.com/nkm.json");
NeoNode node = nodes.values().stream().findFirst().orElseThrow();
NeoLinkCfg cfg = node.toCfg("your-access-key", 25565);
NeoLinkAPI api = new NeoLinkAPI(cfg);
```

### 3. 监听状态和连接事件

如果你只想知道“什么时候连上、什么时候断开”，只装这些回调就够了。

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
```

## 你最常会用到的对象

### `NeoLinkCfg`

构造函数：

```java
new NeoLinkCfg(remoteDomainName, hookPort, hostConnectPort, key, localPort)
```

最常用的 setter：

- `setLocalDomainName(String)`
- `setTCPEnabled(boolean)`
- `setUDPEnabled(boolean)`
- `setProxyIPToLocalServer(String)` / `setProxyIPToLocalServer()`
- `setProxyIPToNeoServer(String)` / `setProxyIPToNeoServer()`
- `setPPV2Enabled(boolean)` / `setPPV2Enabled()`
- `setDebugMsg(boolean)` / `setDebugMsg()`

常见规则：

- 空字符串会被当成非法值处理。
- 代理地址传空表示直连。
- 端口范围必须是 `1..65535`。
- `language` 只接受英文或中文的标准值和常见别名。

### `NeoLinkAPI`

最常用的方法：

- `start()`
- `start(int connectToNpsTimeoutMillis)`
- `isActive()`
- `getTunAddr()`
- `getState()`
- `getHookSocket()`
- `getUpdateURL()`
- `updateRuntimeProtocolFlags(boolean tcpEnabled, boolean udpEnabled)`
- `close()`

最常用的回调：

- `setOnStateChanged(...)`
- `setOnError(...)`
- `setOnServerMessage(...)`
- `setUnsupportedVersionDecision(...)`
- `setOnConnect(...)`
- `setOnDisconnect(...)`

建议的调用顺序：

1. 先组装 `NeoLinkCfg`。
2. 再创建 `NeoLinkAPI`。
3. 启动前挂好回调。
4. 用 `try/finally` 确保关闭。

### `NeoNode`

节点对象主要用于“拿到节点后直接转配置”。

```java
NeoNode node = new NeoNode("demo", "demo-id", "nps.example.com", null, 44801, 44802);
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
- `equals(Object)`
- `hashCode()`
- `toString()`

### `NodeFetcher`

`NodeFetcher` 适合节点发现场景。

```java
Map<String, NeoNode> nodes = NodeFetcher.getFromNKM("https://example.com/nkm.json");
Map<String, NeoNode> nodesWithTimeout = NodeFetcher.getFromNKM("https://example.com/nkm.json", 1500);
```

关键点：

- 只接受 `http` / `https`
- JSON 根节点必须是数组
- 返回值用 `realId` 做 key
- 默认超时是 `1000` 毫秒

## 常见排障

- 启动前先确认 `key`、端口、域名都已填写。
- 如果是 NKM 节点转出来的配置，记得先确认节点字段完整。
- 如果是多线程环境，`NeoLinkAPI` 实例不要在未关闭的情况下反复复用。
