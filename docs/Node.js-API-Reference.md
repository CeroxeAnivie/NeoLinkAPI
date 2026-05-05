# Node.js API 参考

> 包名：`neolinkapi`
> 
> 本文档聚焦从包根路径 `neolinkapi` 可直接使用的**核心导出**。`network/*`、`language-data` 等源码内部模块不在此列；`SecureSocket` / `SecureServerSocket` 虽然会从包根路径导出，但通常只在需要直接操作底层加密传输层时才使用，因此这里只标注入口，不逐项展开全部成员。

---

## 目录

- [模块导出清单](#模块导出清单)
- [NeoLinkAPI](#neolinkapi)
- [NeoLinkCfg](#neolinkcfg)
- [NeoLinkState](#neolinkstate)
- [NeoNode](#neonode)
- [NodeFetcher](#nodefetcher)
- [类型定义](#类型定义)
- [错误类](#错误类)
- [常量](#常量)

---

## 模块导出清单

```ts
// 核心类
export { NeoLinkAPI, TransportProtocol } from './neo-link-api';
export type { ConnectionEventHandler } from './neo-link-api';
export { NeoLinkCfg } from './neo-link-cfg';
export {
  EN_US, ZH_CH,
  DEFAULT_HEARTBEAT_PACKET_DELAY,
  DEFAULT_LOCAL_DOMAIN_NAME,
  DEFAULT_PROXY_IP
} from './neo-link-cfg';
export { NeoLinkState } from './neo-link-state';
export { NeoNode } from './neo-node';

// 节点获取
export { NodeFetcher, getFromNKM, parseNodeMap } from './node-fetcher';
export {
  DEFAULT_HOST_CONNECT_PORT,
  DEFAULT_HOST_HOOK_PORT,
  DEFAULT_TIMEOUT_MILLIS
} from './node-fetcher';

// Socket（如需直接与加密层交互）
export { SecureSocket, SecureServerSocket } from './secure-socket';

// 错误类
export {
  NeoLinkError, ServerResponseError,
  UnsupportedVersionException, UnSupportHostVersionException,
  NoSuchKeyException, OutDatedKeyException, UnRecognizedKeyException,
  NoMoreNetworkFlowException, NoMorePortException, PortOccupiedException
} from './errors';

// 版本信息
export { VERSION, AUTHOR } from './version-info';
```

---

## NeoLinkAPI

```ts
export class NeoLinkAPI
```

隧道的唯一控制入口。管理控制连接、心跳检测、服务端指令监听和 TCP/UDP 转发连接的生命周期。

当前 Node.js 实现的 UDP 转发只监听 IPv4，本地下游 UDP 服务请使用 IPv4 地址，例如 `127.0.0.1` 或 IPv4 局域网地址；TCP 不受这个限制。

### 静态属性

| 属性 | 类型 | 说明 |
|------|------|------|
| `TransportProtocol` | `typeof TransportProtocol` | 引用 `TransportProtocol` 枚举。 |

### 静态方法

| 签名 | 返回 | 说明 |
|------|------|------|
| `version()` | `string` | 返回当前 API 包版本。 |
| `parseTunAddrMessage(message: string \| null \| undefined)` | `string \| null` | 从服务端消息中提取隧道地址。 |
| `classifyStartupHandshakeFailure(response: string \| null \| undefined)` | `Error \| null` | 将服务端握手响应分类为结构化错误。 |
| `isSuccessfulHandshakeResponse(response: string)` | `boolean` | 判断响应是否为成功握手。 |

### 构造函数

| 签名 | 说明 |
|------|------|
| `new NeoLinkAPI(cfg: NeoLinkCfg)` | 绑定配置对象。`cfg` 不可为 falsy。 |

### 实例方法 — 生命周期控制

| 签名 | 返回 | 说明 |
|------|------|------|
| `start(connectToNpsTimeoutMillis?: number)` | `Promise<void>` | 建立控制连接、完成握手并等待隧道结束。默认超时 1000ms。 |
| `isActive()` | `boolean` | 查询隧道是否处于运行状态。 |
| `getState()` | `NeoLinkState` | 返回当前生命周期状态。 |
| `close()` | `void` | 请求停止隧道并关闭所有连接。 |

### 实例方法 — 运行时查询

| 签名 | 返回 | 说明 |
|------|------|------|
| `getTunAddr()` | `Promise<string>` | 等待并返回服务端下发的远程连接地址。 |
| `getHookSocket()` | `SecureSocket \| null` | 返回当前控制链路使用的安全 socket。 |
| `getUpdateURL()` | `string \| null` | 返回版本不兼容流程中服务端下发的更新地址。 |

### 实例方法 — 运行时协议切换

| 签名 | 返回 | 说明 |
|------|------|------|
| `updateRuntimeProtocolFlags(tcpEnabled: boolean, udpEnabled: boolean)` | `Promise<void>` | 运行期向服务端请求切换 TCP/UDP 能力。 |

### 实例方法 — 回调注册

所有回调注册方法均返回 `this`，支持链式调用。

| 签名 | 说明 |
|------|------|
| `setOnStateChanged(handler: (state: NeoLinkState) => void)` | 生命周期状态变化回调。 |
| `setOnError(handler: (message: string, cause: unknown) => void)` | 运行期错误回调。 |
| `setOnServerMessage(handler: (message: string) => void)` | 服务端普通消息回调。 |
| `setUnsupportedVersionDecision(handler: (response: string) => boolean \| Promise<boolean>)` | 版本不兼容时是否请求更新地址。默认返回 `true`。 |
| `setDebugSink(handler: (message: string \| null, cause?: unknown) => void)` | 实例级调试事件接收器。 |
| `setOnConnect(handler: ConnectionEventHandler)` | 转发连接建立时触发。 |
| `setOnDisconnect(handler: ConnectionEventHandler)` | 转发连接关闭时触发。 |
| `setOnConnectNeoFailure(handler: () => void)` | 数据通道连接服务端失败时触发。 |
| `setOnConnectLocalFailure(handler: () => void)` | 本地下游连接失败时触发。 |

### 实例方法 — 其他

| 签名 | 返回 | 说明 |
|------|------|------|
| `formatClientInfoString()` | `string` | 格式化当前运行期配置为客户端握手字符串。 |

---

## NeoLinkCfg

```ts
export class NeoLinkCfg
```

对外暴露的可变配置对象。

### 静态属性

| 属性 | 类型 | 值 | 说明 |
|------|------|-----|------|
| `EN_US` | `string` | `"en"` | 英文协议语言标识。 |
| `ZH_CH` | `string` | `"zh"` | 中文协议语言标识。 |
| `DEFAULT_PROXY_IP` | `string` | `""` | 代理默认空值。 |
| `DEFAULT_LOCAL_DOMAIN_NAME` | `string` | `"localhost"` | 本地下游默认地址。 |
| `DEFAULT_HEARTBEAT_PACKET_DELAY` | `number` | `1000` | 默认心跳间隔，单位毫秒。 |

### 构造函数

| 签名 | 说明 |
|------|------|
| `new NeoLinkCfg(remoteDomainName: string, hookPort: number, hostConnectPort: number, key: string, localPort: number)` | 端口范围为 1~65535。 |

### Getter / Setter 一览

所有 setter 均返回 `this`，支持链式调用。

| 配置项 | Getter | Setter | 默认值 |
|--------|--------|--------|--------|
| 远端域名/IP | `getRemoteDomainName()` | `setRemoteDomainName(value: string)` | 构造参数 |
| 控制端口 | `getHookPort()` | `setHookPort(value: number)` | 构造参数 |
| 数据传输端口 | `getHostConnectPort()` | `setHostConnectPort(value: number)` | 构造参数 |
| 本地下游地址 | `getLocalDomainName()` | `setLocalDomainName(value: string)` | `"localhost"` |
| 本地下游端口 | `getLocalPort()` | `setLocalPort(value: number)` | 构造参数 |
| 访问密钥 | `getKey()` | `setKey(value: string)` | 构造参数 |
| 到本地下游的代理 | `getProxyIPToLocalServer()` | `setProxyIPToLocalServer(value?: string)` | `""` |
| 到 NeoServer 的代理 | `getProxyIPToNeoServer()` | `setProxyIPToNeoServer(value?: string)` | `""` |
| 心跳间隔(ms) | `getHeartBeatPacketDelay()` | `setHeartBeatPacketDelay(value: number)` | `1000` |
| TCP 转发 | `isTCPEnabled()` | `setTCPEnabled(value: boolean)` | `true` |
| UDP 转发 | `isUDPEnabled()` | `setUDPEnabled(value: boolean)` | `true` |
| PPv2 透传 | `isPPV2Enabled()` | `setPPV2Enabled(value?: boolean)` | `false` |
| 握手语言 | `getLanguage()` | `setLanguage(value: string)` | `ZH_CH` |
| 客户端版本 | `getClientVersion()` | `setClientVersion(value: string)` | 当前包版本 |
| 调试日志 | `isDebugMsg()` | `setDebugMsg(value?: boolean)` | `false` |

> **注意**：`setProxyIPToLocalServer()`、`setProxyIPToNeoServer()`、`setPPV2Enabled()`、`setDebugMsg()` 可在无参调用时启用/清空默认值。

---

## NeoLinkState

```ts
export enum NeoLinkState
```

隧道生命周期状态枚举。

| 枚举值 | 说明 |
|--------|------|
| `STOPPED = 'STOPPED'` | 无隧道活动，所有资源已释放。 |
| `STARTING = 'STARTING'` | 控制连接建立中，握手尚未完成。 |
| `RUNNING = 'RUNNING'` | 握手完成，隧道正在监听服务端指令。 |
| `STOPPING = 'STOPPING'` | 调用方请求关闭，正在释放资源。 |
| `FAILED = 'FAILED'` | 隧道遇到终止性运行期错误。 |

---

## NeoNode

```ts
export class NeoNode
```

不可变的 NKM 公共节点元数据。

### 构造函数

| 签名 | 说明 |
|------|------|
| `new NeoNode(name: string, realId: string \| null, address: string, iconSvg: string \| null, hookPort: number, connectPort: number)` | `realId` 和 `iconSvg` 允许为 `null`。 |

### 方法

| 签名 | 返回 | 说明 |
|------|------|------|
| `toCfg(key: string, localPort: number)` | `NeoLinkCfg` | 将节点转换为完整隧道配置。 |
| `getName()` | `string` | 节点展示名。 |
| `getRealId()` | `string \| null` | 稳定节点 ID。 |
| `getAddress()` | `string` | 服务端域名或 IP。 |
| `getIconSvg()` | `string \| null` | 可选 SVG 图标内容。 |
| `getHookPort()` | `number` | 控制端口。 |
| `getConnectPort()` | `number` | 数据传输端口。 |
| `equals(other: unknown)` | `boolean` | 基于全部字段的相等性判断。 |
| `toString()` | `string` | 结构化字符串表示。 |

---

## NodeFetcher

```ts
export const NodeFetcher: Readonly<{
  DEFAULT_TIMEOUT_MILLIS: number;
  DEFAULT_HOST_HOOK_PORT: number;
  DEFAULT_HOST_CONNECT_PORT: number;
  getFromNKM: typeof getFromNKM;
  parseNodeMap: typeof parseNodeMap;
}>
```

冻结的 NKM 节点获取工具对象。同时提供独立导出的函数版本。

### 独立导出函数

| 签名 | 返回 | 说明 |
|------|------|------|
| `getFromNKM(url: string, timeoutMillis?: number)` | `Promise<Map<string, NeoNode>>` | 拉取 NKM 节点列表。默认超时 1000ms。 |
| `parseNodeMap(json: string)` | `Map<string, NeoNode>` | 解析 NKM JSON 字符串为节点映射。 |

### NodeFetcher 常量属性

| 属性 | 类型 | 值 | 说明 |
|------|------|-----|------|
| `DEFAULT_TIMEOUT_MILLIS` | `number` | `1000` | 默认 HTTP 超时。 |
| `DEFAULT_HOST_HOOK_PORT` | `number` | `44801` | 节点缺省控制端口。 |
| `DEFAULT_HOST_CONNECT_PORT` | `number` | `44802` | 节点缺省传输端口。 |

---

## 类型定义

### `ConnectionEventHandler`

```ts
export type ConnectionEventHandler = (
  protocol: TransportProtocol,
  source: { host: string; port: number },
  target: { host: string; port: number }
) => void;
```

转发连接事件回调类型。

> `source` 和 `target` 的结构在源码内部对应 `Endpoint`，但 `Endpoint`、`DebugSink`、`ErrorHandler` 并**不会**从包根路径 `neolinkapi` 直接导出。

### `TransportProtocol`

```ts
export enum TransportProtocol {
  TCP = 'TCP',
  UDP = 'UDP'
}
```

---

## 错误类

### 继承关系

```
Error
└── NeoLinkError
    ├── ServerResponseError
    │   ├── UnsupportedVersionException
    │   │   └── UnSupportHostVersionException
    │   ├── NoSuchKeyException
    │   │   ├── OutDatedKeyException
    │   │   └── UnRecognizedKeyException
    │   ├── NoMoreNetworkFlowException
    │   ├── NoMorePortException
    │   └── PortOccupiedException
```

### 错误类列表

| 类 | 父类 | 说明 |
|---|------|------|
| `NeoLinkError` | `Error` | 所有 NeoLinkAPI 错误的基类。 |
| `ServerResponseError` | `NeoLinkError` | 携带服务端原始响应的错误基类。属性：`serverResponse: string \| null`。 |
| `UnsupportedVersionException` | `ServerResponseError` | 握手阶段服务端拒绝当前客户端版本。 |
| `UnSupportHostVersionException` | `UnsupportedVersionException` | 服务端同名异常的 API 侧映射。 |
| `NoSuchKeyException` | `ServerResponseError` | 握手阶段服务端拒绝访问密钥。 |
| `OutDatedKeyException` | `NoSuchKeyException` | 密钥已过期。 |
| `UnRecognizedKeyException` | `NoSuchKeyException` | 密钥未被识别。 |
| `NoMoreNetworkFlowException` | `ServerResponseError` | 访问密钥剩余流量耗尽。默认响应 `'exitNoFlow'`。 |
| `NoMorePortException` | `ServerResponseError` | 服务端无法分配请求的端口。 |
| `PortOccupiedException` | `ServerResponseError` | 远程端口配额已被占用。 |

---

## 常量

### 从 `neo-link-cfg` 导出

| 常量 | 类型 | 值 |
|------|------|-----|
| `EN_US` | `string` | `"en"` |
| `ZH_CH` | `string` | `"zh"` |
| `DEFAULT_PROXY_IP` | `string` | `""` |
| `DEFAULT_LOCAL_DOMAIN_NAME` | `string` | `"localhost"` |
| `DEFAULT_HEARTBEAT_PACKET_DELAY` | `number` | `1000` |

### 从 `node-fetcher` 导出

| 常量 | 类型 | 值 |
|------|------|-----|
| `DEFAULT_TIMEOUT_MILLIS` | `number` | `1000` |
| `DEFAULT_HOST_HOOK_PORT` | `number` | `44801` |
| `DEFAULT_HOST_CONNECT_PORT` | `number` | `44802` |

### 从 `version-info` 导出

| 常量 | 类型 | 说明 |
|------|------|------|
| `VERSION` | `string` | 当前包版本号。 |
| `AUTHOR` | `string` | 作者信息。 |

---

## SecureSocket / SecureServerSocket

```ts
export { SecureSocket, SecureServerSocket } from './secure-socket';
```

加密 socket 实现。一般情况下由 `NeoLinkAPI` 内部自动管理，仅在需要直接与加密传输层交互时才使用。
