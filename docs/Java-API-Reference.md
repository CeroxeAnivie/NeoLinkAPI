# Java API 参考

> 包根路径：`top.ceroxe.api.neolink`
>
> 本文档聚焦**常规集成需要直接使用的核心 API**。`LanguageData`、`VersionInfo` 等包级可见实现不在此列；`util`、`network`、
`network.threads` 下虽然有部分 `public` 工具类，但它们属于底层实现细节，不作为常规接入入口，因此不逐项展开。

---

## 目录

- [NeoLinkAPI](#neolinkapi)
- [NeoLinkCfg](#neolinkcfg)
- [NeoLinkState](#neolinkstate)
- [NeoNode](#neonode)
- [NodeFetcher](#nodefetcher)
- [异常](#异常)
- [嵌套类型](#嵌套类型)

---

## NeoLinkAPI

```java
public final class NeoLinkAPI implements AutoCloseable
```

隧道的唯一控制入口。管理控制连接、心跳检测、服务端指令监听和 TCP/UDP 转发连接的生命周期。

### 构造函数

| 签名                           | 说明                                                |
|------------------------------|---------------------------------------------------|
| `NeoLinkAPI(NeoLinkCfg cfg)` | 绑定一个不可为空的配置对象。`start()` 会复制该配置，之后运行中的隧道不再读取原配置对象。 |

### 静态方法

| 签名          | 返回       | 说明            |
|-------------|----------|---------------|
| `version()` | `String` | 返回当前 API 包版本。 |

### 实例方法 — 生命周期控制

| 签名                                     | 返回             | 抛出                                                                                               | 说明                                   |
|----------------------------------------|----------------|--------------------------------------------------------------------------------------------------|--------------------------------------|
| `start()`                              | `void`         | `IOException`, `UnsupportedVersionException`, `NoSuchKeyException`, `NoMoreNetworkFlowException` | 建立控制连接、完成握手并阻塞运行到隧道停止。               |
| `start(int connectToNpsTimeoutMillis)` | `void`         | 同上 + `IllegalArgumentException`                                                                  | 使用自定义 NPS 连接超时时间（必须大于 0）。            |
| `isActive()`                           | `boolean`      | —                                                                                                | 查询隧道是否处于运行状态。                        |
| `getState()`                           | `NeoLinkState` | —                                                                                                | 返回当前生命周期状态。                          |
| `close()`                              | `void`         | —                                                                                                | 请求停止隧道并关闭控制/转发连接，适合放进 shutdown hook。 |

### 实例方法 — 运行时查询

| 签名                | 返回             | 说明                                      |
|-------------------|----------------|-----------------------------------------|
| `getTunAddr()`    | `String`       | 阻塞等待 NeoProxyServer 明确下发的远程连接地址。        |
| `getHookSocket()` | `SecureSocket` | 返回当前控制链路使用的安全 socket；控制链路未建立时返回 `null`。 |
| `getUpdateURL()`  | `String`       | 返回版本不兼容流程中下发的客户端更新地址；没有可用地址时返回 `null`。  |
| `isPPV2Enabled()` | `boolean`      | 返回当前 PPv2 透传开关；运行中读取运行期配置，未运行时读取初始配置。    |

### 实例方法 — 运行时协议切换

| 签名                                                                   | 返回     | 抛出            | 说明                      |
|----------------------------------------------------------------------|--------|---------------|-------------------------|
| `updateRuntimeProtocolFlags(boolean tcpEnabled, boolean udpEnabled)` | `void` | `IOException` | 运行期向服务端请求切换 TCP/UDP 能力。 |
| `setPPV2Enabled(boolean ppv2Enabled)` / `setPPV2Enabled()`           | `NeoLinkAPI` | —       | 运行期切换 PPv2 透传；只影响之后新建的 TCP 连接。 |

### 实例方法 — 回调注册

所有回调注册方法均返回 `NeoLinkAPI` 自身，支持链式调用。

| 签名                                                                                    | 说明                                 |
|---------------------------------------------------------------------------------------|------------------------------------|
| `setOnStateChanged(Consumer<NeoLinkState> onStateChanged)`                            | 生命周期状态变化回调。                        |
| `setOnError(BiConsumer<String, Throwable> onError)`                                   | 运行期错误回调。参数为错误摘要和原始异常。              |
| `setOnServerMessage(Consumer<String> onServerMessage)`                                | 服务端普通消息回调。                         |
| `setUnsupportedVersionDecision(Function<String, Boolean> unsupportedVersionDecision)` | 不支持当前版本时是否请求服务端下发更新地址。默认返回 `true`。 |
| `setDebugSink(BiConsumer<String, Throwable> debugSink)`                               | 实例级调试事件接收器。                        |
| `setOnConnect(ConnectionEventHandler onConnect)`                                      | 转发连接建立时触发。                         |
| `setOnDisconnect(ConnectionEventHandler onDisconnect)`                                | 转发连接关闭时触发。                         |
| `setOnTraffic(TrafficEventHandler onTraffic)`                                        | 成功转发业务负载后触发，参数为协议、方向和字节数。         |
| `setOnConnectNeoFailure(Runnable onConnectNeoFailure)`                                | 数据通道连接 NeoProxyServer 失败时触发。       |
| `setOnConnectLocalFailure(Runnable onConnectLocalFailure)`                            | TCP 转发连接本地下游服务失败时触发。               |

### 内部接口

#### `NeoLinkAPI.ConnectionEventHandler`

```java
@FunctionalInterface
public interface ConnectionEventHandler {
    void accept(TransportProtocol protocol, InetSocketAddress source, InetSocketAddress target);
}
```

接收转发连接事件。参数依次为协议类型、公网来源地址、本地下游地址。

#### `NeoLinkAPI.TrafficEventHandler`

```java
@FunctionalInterface
public interface TrafficEventHandler {
    void accept(TransportProtocol protocol, TrafficDirection direction, long bytes);
}
```

接收已成功转发的业务流量事件。`bytes` 只包含业务负载：UDP 不包含内部序列化头，TCP 在关闭 PPv2 透传时不包含已剥离的 PPv2 头。

#### `NeoLinkAPI.TransportProtocol`

```java
public enum TransportProtocol { TCP, UDP }
```

#### `NeoLinkAPI.TrafficDirection`

```java
public enum TrafficDirection { NEO_TO_LOCAL, LOCAL_TO_NEO }
```

---

## NeoLinkCfg

```java
public final class NeoLinkCfg
```

对外暴露的可变配置对象。构造函数中的五个参数没有安全的通用默认值，强制调用方显式传入。

### 常量

| 常量                               | 类型       | 值             | 说明           |
|----------------------------------|----------|---------------|--------------|
| `EN_US`                          | `String` | `"en"`        | 英文协议语言标识。    |
| `ZH_CH`                          | `String` | `"zh"`        | 中文协议语言标识。    |
| `DEFAULT_PROXY_IP`               | `String` | `""`          | 代理默认空值（直连）。  |
| `DEFAULT_LOCAL_DOMAIN_NAME`      | `String` | `"localhost"` | 本地下游默认地址。    |
| `DEFAULT_HEARTBEAT_PACKET_DELAY` | `int`    | `1000`        | 默认心跳间隔，单位毫秒。 |

### 构造函数

| 签名                                                                                                  | 说明                      |
|-----------------------------------------------------------------------------------------------------|-------------------------|
| `NeoLinkCfg(String remoteDomainName, int hookPort, int hostConnectPort, String key, int localPort)` | 创建最小可用配置。端口范围为 1~65535。 |

### Getter / Setter 一览

所有 setter 均返回 `NeoLinkCfg` 自身，支持链式调用。

| 配置项             | Getter                      | Setter                                                          | 默认值           |
|-----------------|-----------------------------|-----------------------------------------------------------------|---------------|
| 远端域名/IP         | `getRemoteDomainName()`     | `setRemoteDomainName(String)`                                   | 构造参数          |
| 控制端口            | `getHookPort()`             | `setHookPort(int)`                                              | 构造参数          |
| 数据传输端口          | `getHostConnectPort()`      | `setHostConnectPort(int)`                                       | 构造参数          |
| 本地下游地址          | `getLocalDomainName()`      | `setLocalDomainName(String)`                                    | `"localhost"` |
| 本地下游端口          | `getLocalPort()`            | `setLocalPort(int)`                                             | 构造参数          |
| 访问密钥            | `getKey()`                  | `setKey(String)`                                                | 构造参数          |
| 到本地下游的代理        | `getProxyIPToLocalServer()` | `setProxyIPToLocalServer(String)` / `setProxyIPToLocalServer()` | `""`          |
| 到 NeoServer 的代理 | `getProxyIPToNeoServer()`   | `setProxyIPToNeoServer(String)` / `setProxyIPToNeoServer()`     | `""`          |
| 心跳间隔(ms)        | `getHeartBeatPacketDelay()` | `setHeartBeatPacketDelay(int)`                                  | `1000`        |
| TCP 转发          | `isTCPEnabled()`            | `setTCPEnabled(boolean)`                                        | `true`        |
| UDP 转发          | `isUDPEnabled()`            | `setUDPEnabled(boolean)`                                        | `true`        |
| PPv2 透传         | `isPPV2Enabled()`           | `setPPV2Enabled(boolean)` / `setPPV2Enabled()`                  | `false`       |
| 握手语言            | `getLanguage()`             | `setLanguage(String)`                                           | `ZH_CH`       |
| 客户端版本           | `getClientVersion()`        | `setClientVersion(String)`                                      | 当前包版本         |
| 调试日志            | `isDebugMsg()`              | `setDebugMsg(boolean)` / `setDebugMsg()`                        | `false`       |

---

## NeoLinkState

```java
public enum NeoLinkState
```

隧道生命周期状态枚举。

| 枚举值        | 含义                            |
|------------|-------------------------------|
| `STOPPED`  | 无隧道活动，所有 API 持有的资源已释放。        |
| `STARTING` | 控制连接建立中，启动握手尚未完成。             |
| `RUNNING`  | 启动握手完成，隧道正在监听服务端指令。           |
| `STOPPING` | 调用方请求关闭，正在释放 socket、心跳和转发线程。  |
| `FAILED`   | 隧道遇到终止性运行期错误。随后会进入 `STOPPED`。 |

---

## NeoNode

```java
public final class NeoNode
```

不可变的 NKM 公共节点元数据。

### 构造函数

| 签名                                                                                                   | 说明                               |
|------------------------------------------------------------------------------------------------------|----------------------------------|
| `NeoNode(String name, String realId, String address, String iconSvg, int hookPort, int connectPort)` | `realId` 和 `iconSvg` 允许为 `null`。 |

### 方法

| 签名                                 | 返回           | 说明                      |
|------------------------------------|--------------|-------------------------|
| `toCfg(String key, int localPort)` | `NeoLinkCfg` | 将节点转换为完整隧道配置。           |
| `getName()`                        | `String`     | 节点展示名。                  |
| `getRealId()`                      | `String`     | 稳定节点 ID，可能为 `null`。     |
| `getAddress()`                     | `String`     | 服务端域名或 IP。              |
| `getIconSvg()`                     | `String`     | 可选 SVG 图标内容，可能为 `null`。 |
| `getHookPort()`                    | `int`        | 控制端口。                   |
| `getConnectPort()`                 | `int`        | 数据传输端口。                 |
| `equals(Object o)`                 | `boolean`    | 基于全部字段的相等性判断。           |
| `hashCode()`                       | `int`        | 基于全部字段的哈希码。             |
| `toString()`                       | `String`     | 结构化字符串表示。               |

---

## NodeFetcher

```java
public final class NodeFetcher
```

从 NKM 拉取公开 NeoLink 节点定义的工具类。不可实例化。

### 常量

| 常量                          | 类型    | 值       | 说明               |
|-----------------------------|-------|---------|------------------|
| `DEFAULT_TIMEOUT_MILLIS`    | `int` | `1000`  | 默认 HTTP 超时，单位毫秒。 |
| `DEFAULT_HOST_HOOK_PORT`    | `int` | `44801` | 节点缺省控制端口。        |
| `DEFAULT_HOST_CONNECT_PORT` | `int` | `44802` | 节点缺省传输端口。        |

### 静态方法

| 签名                                          | 返回                     | 抛出                                        | 说明                   |
|---------------------------------------------|------------------------|-------------------------------------------|----------------------|
| `getFromNKM(String url)`                    | `Map<String, NeoNode>` | `IOException`                             | 使用默认 1 秒超时拉取 NKM 节点。 |
| `getFromNKM(String url, int timeoutMillis)` | `Map<String, NeoNode>` | `IOException`, `IllegalArgumentException` | 使用自定义超时拉取。           |

---

## 异常

包路径：`top.ceroxe.api.neolink.exception`

### 继承关系

```
Exception
├── UnsupportedVersionException
│   └── UnSupportHostVersionException
├── NoSuchKeyException
│   ├── OutDatedKeyException
│   └── UnRecognizedKeyException
└── NoMoreNetworkFlowException

IOException
├── NoMorePortException
└── PortOccupiedException
```

### 异常列表

| 异常                              | 父类                            | 说明                |
|---------------------------------|-------------------------------|-------------------|
| `UnsupportedVersionException`   | `Exception`                   | 握手阶段服务端拒绝当前客户端版本。 |
| `UnSupportHostVersionException` | `UnsupportedVersionException` | 服务端同名异常的 API 侧映射。 |
| `NoSuchKeyException`            | `Exception`                   | 握手阶段服务端拒绝访问密钥。    |
| `OutDatedKeyException`          | `NoSuchKeyException`          | 密钥已过期。            |
| `UnRecognizedKeyException`      | `NoSuchKeyException`          | 密钥未被识别。           |
| `NoMoreNetworkFlowException`    | `Exception`                   | 访问密钥剩余流量耗尽。       |
| `NoMorePortException`           | `IOException`                 | 服务端无法分配请求的端口。     |
| `PortOccupiedException`         | `IOException`                 | 远程端口配额已被占用。       |

### 公共方法

所有异常均提供：

| 方法                 | 返回       | 说明           |
|--------------------|----------|--------------|
| `serverResponse()` | `String` | 返回服务端原始响应文本。 |

---

## 嵌套类型

以下类型由 `NeoLinkAPI` 内部定义，通过 `NeoLinkAPI.TransportProtocol`、`NeoLinkAPI.TrafficDirection`、`NeoLinkAPI.ConnectionEventHandler` 和 `NeoLinkAPI.TrafficEventHandler` 的形式引用：

| 类型                       | 定义位置         | 说明                    |
|--------------------------|--------------|-----------------------|
| `TransportProtocol`      | `NeoLinkAPI` | 枚举，值 `TCP` / `UDP`。   |
| `TrafficDirection`       | `NeoLinkAPI` | 枚举，值 `NEO_TO_LOCAL` / `LOCAL_TO_NEO`。 |
| `ConnectionEventHandler` | `NeoLinkAPI` | 函数式接口，接收协议、来源地址、目标地址。 |
| `TrafficEventHandler`    | `NeoLinkAPI` | 函数式接口，接收协议、方向、业务负载字节数。 |
