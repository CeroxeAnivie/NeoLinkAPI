# Java 开发指南

Java 版适合直接嵌入业务服务。接入时先明确一个事实：`NeoLinkAPI.start()` 是长运行阻塞方法，它会阻塞到隧道停止、服务端断开、运行期错误，或者其他线程调用 `close()`。

## 最小调用

`NeoLinkCfg` 构造函数已经包含最小必填项：远端地址、控制端口、转发端口、访问密钥、本地端口。

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
```

## 常用对象

`NeoLinkCfg` 负责启动前配置。构造函数参数不能省略，setter 用于覆盖默认值。

`NeoLinkAPI` 负责运行期控制。常用方法是 `start()`、`getTunAddr()`、`getState()`、`isActive()`、`updateRuntimeProtocolFlags(...)` 和 `close()`。

`NeoNode` 表示 NKM 节点。常用方法是 `getName()`、`getRealId()`、`getAddress()`、`getHookPort()`、`getConnectPort()` 和 `toCfg(...)`。

`NeoLinkState` 的状态值是 `STOPPED`、`STARTING`、`RUNNING`、`STOPPING`、`FAILED`。

## 常见错误

- 不要把 `start()` 当成“启动后立刻返回”的方法。
- 不要在最小调用里重复写默认值，除非你想明确覆盖配置。
- `getTunAddr()` 会等待服务端下发地址。
- 运行中切换协议用 `updateRuntimeProtocolFlags(...)`，启动前默认 TCP/UDP 已启用。
