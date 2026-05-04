# Node.js 开发指南

Node.js 版适合脚本、服务端和自动化集成。接入时先明确一个事实：`api.start()` 返回的 Promise 会等待隧道运行结束，不是“启动完成后立刻 resolve”。

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

```ts
import { NeoLinkAPI, NeoLinkCfg } from 'neolinkapi';

async function main(): Promise<void> {
  const api = new NeoLinkAPI(
    new NeoLinkCfg('nps.example.com', 44801, 44802, 'your-access-key', 25565)
  );

  process.once('SIGINT', () => api.close());
  await api.start();
}

void main();
```

## 启动后继续执行

如果你还要在启动后读取隧道地址或继续执行业务逻辑，把 `api.start()` 留在后台 Promise。`getTunAddr()` 会等待服务端下发地址。

```ts
import { NeoLinkAPI, NeoLinkCfg } from 'neolinkapi';

async function main(): Promise<void> {
  const api = new NeoLinkAPI(
    new NeoLinkCfg('nps.example.com', 44801, 44802, 'your-access-key', 25565)
  );

  process.once('SIGINT', () => api.close());

  const running = api.start().catch((error) => {
    console.error(error);
    throw error;
  });

  console.log('tunAddr =', await api.getTunAddr());
  await running;
}

void main();
```

## 需要时才改默认值

只在业务需要时调用 setter。

```ts
const cfg = new NeoLinkCfg('nps.example.com', 44801, 44802, 'your-access-key', 25565)
  .setLocalDomainName('127.0.0.1')
  .setUDPEnabled(false)
  .setPPV2Enabled(true)
  .setDebugMsg(true);
```

## 从 NKM 节点启动

```ts
import { NeoLinkAPI, NodeFetcher } from 'neolinkapi';

async function main(): Promise<void> {
  const nodes = await NodeFetcher.getFromNKM('https://example.com/nkm.json');
  const first = nodes.values().next();
  if (first.done) {
    throw new Error('nkm 里没有可用节点');
  }

  const cfg = first.value.toCfg('your-access-key', 25565);
  const api = new NeoLinkAPI(cfg);
  process.once('SIGINT', () => api.close());
  await api.start();
}

void main();
```

`NodeFetcher.getFromNKM(url)` 默认超时是 `1000` 毫秒，也可以传入自定义超时：

```ts
async function loadNodes() {
  return NodeFetcher.getFromNKM('https://example.com/nkm.json', 1500);
}
```

## 常用回调

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

## 常用对象

`NeoLinkCfg` 负责启动前配置。构造函数参数不能省略，setter 用于覆盖默认值。

`NeoLinkAPI` 负责运行期控制。常用方法是 `start(...)`、`getTunAddr()`、`getState()`、`isActive()`、`updateRuntimeProtocolFlags(...)` 和 `close()`。

`NeoNode` 表示 NKM 节点。常用方法是 `getName()`、`getRealId()`、`getAddress()`、`getHookPort()`、`getConnectPort()` 和 `toCfg(...)`。

`NeoLinkState` 的状态值是 `STOPPED`、`STARTING`、`RUNNING`、`STOPPING`、`FAILED`。

## 常见错误

- 不要把 `await api.start()` 当成“启动完成后继续执行”的写法。
- 不要在最小调用里重复写默认值，除非你想明确覆盖配置。
- `getTunAddr()` 返回 Promise，必须 `await`。
- 运行中切换协议用 `updateRuntimeProtocolFlags(...)`，启动前默认 TCP/UDP 已启用。
