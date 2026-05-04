# Node.js 开发指南

Node.js 版适合脚本、服务端和自动化集成。大多数场景都可以先按“配置 -> 启动 -> 读取地址 -> 关闭”这条线走。

## 最小可用示例

```ts
import { NeoLinkAPI, NeoLinkCfg } from 'neolinkapi';

const api = new NeoLinkAPI(
  new NeoLinkCfg('nps.example.com', 44801, 44802, 'your-access-key', 25565)
    .setLocalDomainName('localhost')
    .setTCPEnabled(true)
    .setUDPEnabled(true)
);

await api.start();
console.log('state =', api.getState());
console.log('tunAddr =', await api.getTunAddr());
```

## 最常见的三种调用

### 1. 直连本地服务

```ts
const cfg = new NeoLinkCfg('nps.example.com', 44801, 44802, 'your-access-key', 25565)
  .setLocalDomainName('localhost')
  .setTCPEnabled(true)
  .setUDPEnabled(true);
```

### 2. 从 NKM 节点启动

```ts
import { NodeFetcher } from 'neolinkapi';

const nodes = await NodeFetcher.getFromNKM('https://example.com/nkm.json');
const first = nodes.values().next();
if (first.done) {
  throw new Error('nkm 里没有可用节点');
}
const node = first.value;
const cfg = node.toCfg('your-access-key', 25565);
```

### 3. 只关心事件和错误

```ts
api.setOnStateChanged((state) => console.log('state =', state));
api.setOnError((message, cause) => {
  console.error(message);
  if (cause) {
    console.error(cause);
  }
});
api.setOnConnect((protocol, source, target) => {
  console.log('connect', protocol, source, target);
});
api.setOnDisconnect((protocol, source, target) => {
  console.log('disconnect', protocol, source, target);
});
```

## 你最常会用到的对象

### `NeoLinkCfg`

构造函数：

```ts
new NeoLinkCfg(remoteDomainName, hookPort, hostConnectPort, key, localPort)
```

最常用的方法：

- `setLocalDomainName(value)`
- `setTCPEnabled(value)`
- `setUDPEnabled(value)`
- `setProxyIPToLocalServer(value?)`
- `setProxyIPToNeoServer(value?)`
- `setPPV2Enabled(value = true)`
- `setDebugMsg(value = true)`
- `copy()`
- `requireStartReady()`

常见规则：

- 代理地址传空字符串表示直连。
- 端口范围必须是 `1..65535`。
- `requireStartReady()` 适合从 NKM 节点转出的配置，用来提前检查启动必需字段。
- `copy()` 适合在启动前留一份快照，避免外部继续改同一个对象。

### `NeoLinkAPI`

最常用的方法：

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

常用回调：

- `setOnStateChanged(handler)`
- `setOnError(handler)`
- `setOnServerMessage(handler)`
- `setUnsupportedVersionDecision(handler)`
- `setOnConnect(handler)`
- `setOnDisconnect(handler)`
- `setOnConnectNeoFailure(handler)`
- `setOnConnectLocalFailure(handler)`

简单原则：

1. `start()` 前先把配置准备好。
2. `getTunAddr()` 要 `await`。
3. `close()` 最好放在 `finally`。
4. 协议切换只在运行中调用。

### `NeoNode`

```ts
const node = new NeoNode('demo', 'demo-id', 'nps.example.com', null, 44801, 44802);
const cfg = node.toCfg('your-access-key', 25565);
```

常用方法：

- `getName()`
- `getRealId()`
- `getAddress()`
- `getIconSvg()`
- `getHookPort()`
- `getConnectPort()`
- `toCfg(key, localPort)`
- `equals(other)`
- `toString()`

### `NodeFetcher`

```ts
const nodes = await NodeFetcher.getFromNKM('https://example.com/nkm.json');
const parsed = NodeFetcher.parseNodeMap(rawJson);
```

关键点：

- 只接受 `http` / `https`
- JSON 根节点必须是数组
- 返回值是 `Map<string, NeoNode>`
- 默认超时是 `1000` 毫秒

## 常见排障

- `getTunAddr()` 返回的是 Promise，不要当同步函数用。
- `unsupportedVersionDecision` 可以返回布尔值，也可以返回 Promise。
- 如果只想做“能不能启动”的判断，记得保留 `serverResponse` 或 `cause`，不要直接吞异常。
