# Lifecycle

本页覆盖 `NeoLinkAPI` 的启动、回调、运行时切换和关闭语义。

## `new NeoLinkAPI(NeoLinkCfg cfg)`

作用：
用一份配置创建隧道实例。

约束：
- `cfg` 不能为空。
- 这个对象不是“配置本身”，它是持有生命周期和连接状态的运行时实例。

## `start()`

作用：
使用默认连接超时时间启动隧道。

默认值：
- `start(int)` 的默认连接到 NPS 超时时间是 `1000` 毫秒。

使用：
```java
tunnel.start();
```

约束：
- 方法会阻塞，直到隧道关闭或发生终止性错误。
- 如果实例已经处于 active 状态，重复调用会被忽略。
- 启动前会复制配置，所以启动中的运行态修改不会回写到原始 `NeoLinkCfg`。
- 可能抛 `IOException`、`UnsupportedVersionException`、`NoSuchKeyException`、`NoMoreNetworkFlowException`。

## `start(int connectToNpsTimeoutMillis)`

作用：
使用自定义连接到 NPS 的超时时间启动隧道。

约束：
- `connectToNpsTimeoutMillis` 必须大于 `0`。
- 同样会阻塞。
- 这个值只影响连接 NeoProxy 服务器的握手和转发连接，不影响本地连接默认超时。

## `isActive()`

作用：
判断当前实例是否已经进入运行态。

约束：
- 只反映当前生命周期，不代表网络一定健康。

## `getTunAddr()`

作用：
等待并返回服务端下发的隧道地址，例如 `host:port`。

使用：
```java
String tunAddr = tunnel.getTunAddr();
```

约束：
- 会阻塞，直到服务端发来地址。
- 只能解析两种格式：
  - `Use the address: host:port to start up connections.`
  - 中文等价提示中的地址片段
- 在没有启动或隧道已经关闭时调用，行为上会继续等待新的地址；不要把它当成非阻塞 getter。

## `getHookSocket()`

作用：
返回当前控制通道 socket。

约束：
- 未启动或已关闭时返回 `null`。
- 主要用于诊断，不建议业务代码直接操作。

## `getUpdateURL()`

作用：
返回不兼容版本协商阶段由服务端提供的更新地址。

约束：
- 只有在服务端返回不支持版本，并且本端决定请求更新时才可能有值。
- 未发生该流程时返回 `null`。

## `getState()`

作用：
返回当前生命周期状态。

状态：
- `STOPPED`
- `STARTING`
- `RUNNING`
- `STOPPING`
- `FAILED`

## `setOnStateChanged(Consumer<NeoLinkState>)`

作用：
订阅状态变化。

约束：
- 参数不能为空。
- 回调异常会被隔离，不会直接炸掉主流程。

## `setOnError(BiConsumer<String, Throwable>)`

作用：
订阅致命或诊断级错误。

约束：
- 参数不能为空。
- 回调异常会被隔离。
- `Throwable` 可能为 `null`，所以实现里要先判空。

## `setOnServerMessage(Consumer<String>)`

作用：
接收服务端原始消息。

约束：
- 参数不能为空。
- 这里拿到的是原文，不是已经分类后的异常。

## `setUnsupportedVersionDecision(Function<String, Boolean>)`

作用：
决定在服务端提示版本不兼容时，是否继续向服务端索要更新 URL。

默认行为：
- 默认返回 `true`，也就是会请求更新地址。

约束：
- 参数不能为空。
- 这个函数如果抛异常，会被吞掉并按 `false` 处理。

## `setDebugSink(BiConsumer<String, Throwable>)`

作用：
接收调试日志和调试异常。

约束：
- 参数不能为空。
- debug sink 本身抛异常会被吞掉，避免影响主流程。

## `setOnConnect(ConnectionEventHandler)` / `setOnDisconnect(ConnectionEventHandler)`

作用：
接收 TCP 或 UDP 隧道建立和断开事件。

使用：
```java
tunnel.setOnConnect((protocol, source, target) -> {
    System.out.println(protocol + " " + source + " -> " + target);
});
```

约束：
- 参数不能为空。
- `source` 是服务端侧远端地址。
- `target` 是本地服务地址。
- 两个回调都可能在转发线程中触发，回调里不要做阻塞很久的工作。

## `setOnConnectNeoFailure(Runnable)` / `setOnConnectLocalFailure(Runnable)`

作用：
分别在 NeoProxy 侧转发连接建立失败、或本地服务连接建立失败时触发。

约束：
- 参数不能为空。
- 回调异常会被隔离。

## `updateRuntimeProtocolFlags(boolean tcpEnabled, boolean udpEnabled)`

作用：
在隧道运行中切换 TCP/UDP 运行态开关，并向服务端发送控制指令。

使用：
```java
tunnel.updateRuntimeProtocolFlags(false, true);
```

约束：
- 必须在实例 active 时调用。
- 方法会先向服务端发送新的协议标志，再更新本地运行态。
- 旧协议会保留一个 3 秒宽限期，避免正在切换的流量被直接切断。
- 如果服务端拒绝切换并返回端口不足类响应，本地开关会回滚。

## `version()`

作用：
返回当前 API 版本号。

约束：
- 来自 `api.properties`，和发布版本保持一致。

## `close()`

作用：
关闭当前隧道，释放控制连接、转发连接、心跳线程和 worker 执行器。

约束：
- 关闭后状态会回到 `STOPPED`。
- 关闭后可以重新 `start()` 同一个实例。
- 关闭会让等待中的 `getTunAddr()` / `start()` 结束。

## `TransportProtocol`

值：
- `TCP`
- `UDP`

用途：
- 用在连接事件回调里，标识当前转发协议。

## `ConnectionEventHandler`

签名：
```java
void accept(TransportProtocol protocol, InetSocketAddress source, InetSocketAddress target);
```

约束：
- `source` 和 `target` 都是已解析但可能未连接的地址对象。
- 事件回调不应阻塞。
