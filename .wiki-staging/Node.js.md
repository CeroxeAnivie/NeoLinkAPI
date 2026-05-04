# Node.js

NeoLinkAPI 的 Node.js 版是一个发布到 npm 的独立包，包名是 `neolinkapi`。它对外暴露的行为和 Java 版保持一致，但更适合在 Node 进程里直接集成和自动化测试。

## 环境与安装

- Node.js `>= 20.0.0`
- 安装：

```cmd
npm install neolinkapi
```

## 最小示例

```ts
import { NeoLinkAPI, NeoLinkCfg } from 'neolinkapi';

const api = new NeoLinkAPI(
  new NeoLinkCfg('nps.example.com', 44801, 44802, 'your-access-key', 25565)
    .setLocalDomainName('localhost')
    .setTCPEnabled(true)
    .setUDPEnabled(true)
);

await api.start();
console.log(await api.getTunAddr());
```

## 导出总览

包根导出包含：

- `NeoLinkAPI`
- `NeoLinkCfg`
- `NeoNode`
- `NodeFetcher`
- `NeoLinkState`
- `SecureSocket`
- `SecureServerSocket`
- `TransportProtocol`
- 异常类：`NeoLinkError`、`ServerResponseError`、`UnsupportedVersionException`、`UnSupportHostVersionException`、`NoSuchKeyException`、`OutDatedKeyException`、`UnRecognizedKeyException`、`NoMoreNetworkFlowException`、`NoMorePortException`、`PortOccupiedException`
- 常量：`VERSION`、`AUTHOR`

## 核心对象

### `NeoLinkCfg`

Node.js 版的配置对象也是可变的，但和 Java 版一样会在 setter 里立刻做输入校验；源码本身由 TypeScript 实现。

构造函数：

```ts
new NeoLinkCfg(remoteDomainName, hookPort, hostConnectPort, key, localPort)
```

常用方法：

- `setRemoteDomainName(value)`
- `setHookPort(value)`
- `setHostConnectPort(value)`
- `setLocalDomainName(value)`
- `setLocalPort(value)`
- `setKey(value)`
- `setProxyIPToLocalServer(value?)`
- `setProxyIPToNeoServer(value?)`
- `setHeartBeatPacketDelay(value)`
- `setTCPEnabled(value)`
- `setUDPEnabled(value)`
- `setPPV2Enabled(value = true)`
- `setLanguage(value)`
- `setClientVersion(value)`
- `setDebugMsg(value = true)`
- `copy()`
- `requireStartReady()`

约定：

- 端口范围必须是 `1..65535`
- `setProxyIPToLocalServer('')` 和 `setProxyIPToNeoServer('')` 表示直连
- `setLanguage(...)` 接受 `en`、`zh` 以及常见别名，但最终会规范化成标准值
- `copy()` 用于保留当前配置快照，避免调用方复用同一个实例造成状态串扰
- `requireStartReady()` 主要给从 NKM 节点转出的配置使用，用来提前校验启动所需字段是否已经补齐

使用示例：

```ts
const cfg = node.toCfg('your-access-key', 25565);
cfg.requireStartReady();
```

### `NeoNode`

`NeoNode` 表示一个 NKM 节点。

```ts
const node = new NeoNode(name, realId, address, iconSvg, hookPort, connectPort);
const cfg = node.toCfg('your-access-key', 25565);
```

方法：

- `getName()`
- `getRealId()`
- `getAddress()`
- `getIconSvg()`
- `getHookPort()`
- `getConnectPort()`
- `toCfg(key, localPort)`
- `equals(other)`
- `toString()`

使用示例：

```ts
const node = new NeoNode('demo', 'demo-id', 'nps.example.com', null, 44801, 44802);
console.log(node.equals(node));
console.log(node.toString());
```

### `NodeFetcher`

节点发现入口：

```ts
const nodes = await NodeFetcher.getFromNKM('https://example.com/nkm.json');
const parsed = NodeFetcher.parseNodeMap(rawJson);
```

行为约束：

- 只接受 `http` / `https`
- `getFromNKM(url, timeoutMillis?)` 默认超时是 `1000` 毫秒
- JSON 根节点必须是数组
- 返回值是 `Map<string, NeoNode>`
- 默认节点端口常量为 `44801` / `44802`

使用示例：

```ts
const rawJson = '[{"name":"demo","realId":"demo-id","address":"nps.example.com"}]';
const parsed = NodeFetcher.parseNodeMap(rawJson);
const nodes = await NodeFetcher.getFromNKM('https://example.com/nkm.json');
```

### `NeoLinkAPI`

这是运行期控制器。

主要方法：

- `static version()`
- `static parseTunAddrMessage(message)`
- `static classifyStartupHandshakeFailure(response)`
- `static isSuccessfulHandshakeResponse(response)`
- `start(connectToNpsTimeoutMillis?)`
- `isActive()`
- `getTunAddr()`
- `getHookSocket()`
- `getUpdateURL()`
- `getState()`
- `updateRuntimeProtocolFlags(tcpEnabled, udpEnabled)`
- `close()`
- `formatClientInfoString()`

回调设置：

- `setOnStateChanged(handler)`
- `setOnError(handler)`
- `setOnServerMessage(handler)`
- `setUnsupportedVersionDecision(handler)`
- `setDebugSink(handler)`
- `setOnConnect(handler)`
- `setOnDisconnect(handler)`
- `setOnConnectNeoFailure(handler)`
- `setOnConnectLocalFailure(handler)`

行为边界：

- `start()` 会复制一份配置快照，避免启动后修改原 `NeoLinkCfg` 引发半生效状态。
- `getTunAddr()` 返回 `Promise<string>`，所以调用方要显式 `await`。
- `unsupportedVersionDecision` 可以返回 `boolean` 或 `Promise<boolean>`，适合异步决定是否继续。
- `updateRuntimeProtocolFlags(...)` 只应该在运行中调用。

静态辅助方法也有实际用途：

```ts
console.log(NeoLinkAPI.version());
console.log(NeoLinkAPI.parseTunAddrMessage('Use the address: 1.2.3.4:25565 to start up connections.'));
console.log(NeoLinkAPI.classifyStartupHandshakeFailure('noSuchKey'));
console.log(NeoLinkAPI.isSuccessfulHandshakeResponse('connectionBuildUpSuccessfully'));
```

回调类型说明：

- `TransportProtocol`：`TCP` / `UDP`
- `ConnectionEventHandler`：`(protocol, source, target) => void`

使用示例：

```ts
api.setOnConnect((protocol, source, target) => {
  console.log('connect', protocol, source, target);
});
api.setOnDisconnect((protocol, source, target) => {
  console.log('disconnect', protocol, source, target);
});
```

`formatClientInfoString()` 会返回握手时发送给服务端的客户端信息串，通常只在调试或协议分析时直接查看：

```ts
console.log(api.formatClientInfoString());
```

### `NeoLinkState`

枚举值如下：

- `STOPPED`
- `STARTING`
- `RUNNING`
- `STOPPING`
- `FAILED`

## 异常

Node 版把服务端返回值映射为明确的异常类型，便于调用方在上层做分支处理：

- `UnsupportedVersionException`
- `UnSupportHostVersionException`
- `NoSuchKeyException`
- `OutDatedKeyException`
- `UnRecognizedKeyException`
- `NoMoreNetworkFlowException`
- `NoMorePortException`
- `PortOccupiedException`

如果你只是在 Node 侧做一层“能不能启动”的判断，不要把这些异常统一吞掉；至少要保留 `serverResponse` 或原始 `cause`，否则排障会变得很慢。
