# API Reference

这一页按公开类和公开方法列出 NeoLinkAPI 的对外契约。每个方法都只写三件事：作用、用法、约束。

## `NeoLinkAPI`

### `new NeoLinkAPI(NeoLinkCfg cfg)`

作用：
创建一个可启动的隧道实例。

约束：
- `cfg` 不能为空。
- 实例持有生命周期状态，不要把它当纯配置对象。

### `start()`

作用：
以默认 1000ms 的 NPS 连接超时启动隧道。

约束：
- 会阻塞。
- 已经 active 时会被忽略。
- 可能抛 `IOException`、`UnsupportedVersionException`、`NoSuchKeyException`、`NoMoreNetworkFlowException`。

### `start(int connectToNpsTimeoutMillis)`

作用：
用自定义超时时间启动隧道。

约束：
- 参数必须大于 0。
- 只影响连接 NeoProxy 服务器，不影响本地服务连接默认值。

### `isActive()`

作用：
判断隧道是否处于运行中。

约束：
- 只表示生命周期，不表示业务层所有连接一定健康。

### `getTunAddr()`

作用：
返回服务端下发的隧道地址。

约束：
- 会阻塞直到地址到达。
- 只解析服务端消息中的纯地址片段，不返回整句提示。

### `getHookSocket()`

作用：
获取当前控制 socket。

约束：
- 未启动或已关闭时返回 `null`。

### `getUpdateURL()`

作用：
获取版本不兼容协商阶段的更新地址。

约束：
- 没发生不兼容协商时返回 `null`。

### `getState()`

作用：
获取生命周期状态。

约束：
- 可能返回 `STOPPED`、`STARTING`、`RUNNING`、`STOPPING`、`FAILED`。

### `setOnStateChanged(Consumer<NeoLinkState>)`

作用：
订阅状态变化。

约束：
- 参数不能为空。
- 回调异常会被隔离。

### `setOnError(BiConsumer<String, Throwable>)`

作用：
订阅错误。

约束：
- 参数不能为空。
- `Throwable` 可能为 `null`。

### `setOnServerMessage(Consumer<String>)`

作用：
订阅原始服务端消息。

约束：
- 参数不能为空。

### `setUnsupportedVersionDecision(Function<String, Boolean>)`

作用：
决定不兼容版本协商时是否继续请求更新 URL。

约束：
- 参数不能为空。

### `setDebugSink(BiConsumer<String, Throwable>)`

作用：
接收调试输出。

约束：
- 参数不能为空。

### `setOnConnect(ConnectionEventHandler)` / `setOnDisconnect(ConnectionEventHandler)`

作用：
订阅 TCP/UDP 连接与断开事件。

约束：
- 参数不能为空。
- source 是远端地址，target 是本地地址。

### `setOnConnectNeoFailure(Runnable)` / `setOnConnectLocalFailure(Runnable)`

作用：
订阅连接 NeoProxy 侧或本地侧失败事件。

约束：
- 参数不能为空。

### `updateRuntimeProtocolFlags(boolean tcpEnabled, boolean udpEnabled)`

作用：
运行中切换 TCP/UDP 转发开关。

约束：
- 必须在 active 状态调用。
- 切换后有 3 秒宽限期处理旧流量。

### `version()`

作用：
返回 API 版本。

约束：
- 来源于 `api.properties`。

### `close()`

作用：
关闭并释放资源。

约束：
- 关闭后可再次 `start()` 同一实例。

## `NeoLinkCfg`

### `new NeoLinkCfg(...)`

作用：
创建最小启动配置。

约束：
- 必填项不能为空白。
- 端口必须在 `1..65535`。

### `setLanguage(String)`

作用：
设置语言集。

约束：
- 只接受 `en` / `zh` 及其别名。

### `setProxyIPToLocalServer(String)` / `setProxyIPToNeoServer(String)`

作用：
设置代理链路。

约束：
- 空值表示直连。
- 支持 `socks->host:port` 和 `http->host:port`。

### `setPPV2Enabled(boolean)`

作用：
控制 TCP 链路是否启用 Proxy Protocol v2。

约束：
- 默认关闭。

### `setHeartBeatPacketDelay(int)`

作用：
设置心跳间隔。

约束：
- 必须大于 0。

### 其余 getter/setter

作用：
对所有字段做同步读写。

约束：
- 读写方法都保持同步，以避免启动前后状态不一致。

## `NodeFetcher`

### `getFromNKM(String)` / `getFromNKM(String, int)`

作用：
从 NKM 拉取并解析节点列表。

约束：
- URL 只接受 `http` / `https`。
- JSON 根节点必须是数组。
- 返回值以 `realId` 为键。

## `NeoNode`

### `new NeoNode(...)`

作用：
表示单个节点。

约束：
- 必填字段不能为空白。
- 端口必须在 `1..65535`。

### `toCfg(String, int)`

作用：
把节点转成可启动的 `NeoLinkCfg`。

约束：
- `key` 不能为空白。
- `localPort` 必须合法。

### `equals(Object)` / `hashCode()` / `toString()`

作用：
- 值比较
- 哈希
- 调试输出

## 异常类型

- `UnsupportedVersionException`
- `UnSupportHostVersionException`
- `NoSuchKeyException`
- `UnRecognizedKeyException`
- `OutDatedKeyException`
- `NoMoreNetworkFlowException`
- `PortOccupiedException`
- `NoMorePortException`

约束：
- 这些异常都保留了原始服务端响应，必要时用 `serverResponse()` 取回。
