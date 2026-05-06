# Node.js 开发指南

Node.js 版适合脚本、服务端和自动化集成。接入时先明确一个事实：`api.start()` 返回的 Promise 会等待隧道运行结束，不是“启动完成后立刻 resolve”。

## 最小调用

`NeoLinkCfg` 构造函数已经包含最小必填项：远端地址、控制端口、转发端口、访问密钥、本地端口。

构造函数参数含义如下：

| 参数 | 示例 | 从哪里来 | 用途 |
| --- | --- | --- | --- |
| `remoteDomainName` | `'nps.example.com'` | NeoProxy/NeoLink 服务端地址，或 NKM 节点的 `address` 字段 | API 会连接这个主机的控制端口和转发端口 |
| `hookPort` | `44801` | NeoProxy/NeoLink 服务端的控制端口，或 NKM 节点的 `HOST_HOOK_PORT`/`hookPort` 字段 | 启动时建立控制连接、发送 key/版本/协议能力、接收服务端指令 |
| `hostConnectPort` | `44802` | NeoProxy/NeoLink 服务端的数据转发端口，或 NKM 节点的 `HOST_CONNECT_PORT`/`connectPort` 字段 | 每次 TCP/UDP 转发会连接这个端口创建数据通道 |
| `key` | `'your-access-key'` | 你在服务端创建隧道/端口时拿到的访问密钥 | 握手鉴权；空值会直接抛 `TypeError` |
| `localPort` | `25565` | 你本机实际运行的下游服务端口，例如 Minecraft/HTTP/SSH | 收到公网连接后，API 会把流量转发到 `localDomainName:localPort` |

如果你不是从 NKM 获取节点，就必须从自己的 NeoProxy/NeoLink 服务端配置里填 `remoteDomainName`、`hookPort`、`hostConnectPort`。如果使用 NKM，节点对象会提供前三个值，仍然需要你自己提供 `key` 和 `localPort`。

这些值已有默认配置，不需要在最小调用里重复写：

- `localDomainName` 默认是 `localhost`
- TCP 默认启用
- UDP 默认启用；当前 Node.js 实现的 UDP 转发只监听 IPv4，本地下游 UDP 服务请使用 IPv4 地址，例如 `127.0.0.1` 或 IPv4 局域网地址
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

常用配置项：

| 方法 | 默认值 | 什么时候改 | 注意 |
| --- | --- | --- | --- |
| `setLocalDomainName(value)` | `'localhost'` | 本地服务只监听 `127.0.0.1`、`::1`、容器网关或固定内网地址时 | 这是本地服务地址，不是 NeoProxy/NeoLink 服务端地址 |
| `setTCPEnabled(value)` | `true` | 确定不需要 TCP 转发时设为 `false` | 最小调用不需要显式设为 `true` |
| `setUDPEnabled(value)` | `true` | 确定不需要 UDP 转发时设为 `false` | 最小调用不需要显式设为 `true` |
| `setProxyIPToNeoServer(value)` | `''`，直连 | 连接 NeoProxy/NeoLink 服务端必须走代理时 | 格式是 `socks->host:port` 或 `http->host:port`，带认证用 `socks->host:port@user;password` |
| `setProxyIPToLocalServer(value)` | `''`，直连 | 连接本地下游服务必须走代理时 | 普通本机服务不要设置 |
| `setHeartBeatPacketDelay(value)` | `1000` 毫秒 | 需要调整控制连接心跳检测间隔时 | 必须大于 `0` |
| `setPPV2Enabled(value = true)` | `false` | 本地下游是 Nginx/HAProxy 等能解析 Proxy Protocol v2 的服务时 | Minecraft、SSH、RDP、普通 HTTP 应保持关闭 |
| `setLanguage(value)` | `NeoLinkCfg.ZH_CH` | 需要握手使用英文协议提示时 | 支持 `NeoLinkCfg.ZH_CH`、`NeoLinkCfg.EN_US` 及常见别名 |
| `setClientVersion(value)` | 当前包版本 | 测试兼容性或模拟客户端版本时 | 普通调用不要改 |
| `setDebugMsg(value = true)` | `false` | 排查连接、握手、转发问题时 | 调试输出走 `setDebugSink(...)` |

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

NKM 节点字段含义：

| 字段/方法 | 含义 |
| --- | --- |
| `realId` / `getRealId()` | NKM 节点稳定 ID，`NodeFetcher.getFromNKM(...)` 返回的 `Map` 使用它作为 key |
| `name` / `getName()` | 节点展示名称，只用于 UI/日志选择 |
| `address` / `getAddress()` | NeoProxy/NeoLink 服务端地址，会变成 `NeoLinkCfg.remoteDomainName` |
| `HOST_HOOK_PORT` 或 `hookPort` / `getHookPort()` | 控制端口，会变成 `NeoLinkCfg.hookPort`；缺省时使用 `44801` |
| `HOST_CONNECT_PORT` 或 `connectPort` / `getConnectPort()` | 数据转发端口，会变成 `NeoLinkCfg.hostConnectPort`；缺省时使用 `44802` |
| `icon` 或 `iconSvg` / `getIconSvg()` | 可选 SVG 图标，隧道启动不依赖它 |
| `toCfg(key, localPort)` | 把节点里的地址和端口，加上你的访问密钥和本地端口，转换成可启动配置 |

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

回调参数含义：

| 回调 | 参数 | 触发时机 |
| --- | --- | --- |
| `setOnStateChanged((state) => void)` | `state` 是 `STOPPED`、`STARTING`、`RUNNING`、`STOPPING`、`FAILED` 之一 | 生命周期状态变化时 |
| `setOnError((message, cause) => void)` | `message` 是可读错误说明，`cause` 是原始异常 | 启动、握手、运行期或转发连接失败时 |
| `setOnServerMessage((message) => void)` | `message` 是服务端原始文本消息 | 服务端发送非命令消息时，也包括可能携带公网访问地址的消息 |
| `setOnConnect((protocol, source, target) => void)` | `protocol` 是 `'TCP'`/`'UDP'`，`source` 和 `target` 是 `{ host, port }` | 单条 TCP/UDP 转发连接建立时 |
| `setOnDisconnect((protocol, source, target) => void)` | 参数同 `setOnConnect` | 单条 TCP/UDP 转发连接结束时 |
| `setOnConnectNeoFailure(() => void)` | 无参数 | 数据通道连接 NeoProxy/NeoLink 服务端失败时 |
| `setOnConnectLocalFailure(() => void)` | 无参数 | TCP 转发连接本地下游服务失败时 |
| `setUnsupportedVersionDecision((response) => boolean \| Promise<boolean>)` | `response` 是服务端返回的不兼容版本提示；返回 `true` 会继续协商更新地址 | 服务端认为客户端版本不兼容时 |
| `setDebugSink((message, cause) => void)` | `message` 是调试文本，`cause` 是可选异常 | `debugMsg` 启用后接收诊断细节 |

## 常用对象

`NeoLinkCfg` 负责启动前配置。构造函数参数不能省略，因为这五个值没有安全的通用默认值；setter 只用于覆盖默认值或运行前调整配置。

`NeoLinkAPI` 负责运行期控制。常用方法说明：

| 方法 | 作用 |
| --- | --- |
| `start(connectToNpsTimeoutMillis = 1000)` | 返回一个会等待隧道结束的 Promise；参数是连接 NeoProxy/NeoLink 服务端的超时时间，单位毫秒，必须大于 `0` |
| `getTunAddr()` | 等待并返回服务端下发的公网访问地址；未收到地址前 Promise 不会 resolve |
| `getState()` | 返回当前生命周期状态 |
| `isActive()` | 返回当前实例是否仍处于运行状态 |
| `getUpdateURL()` | 版本不兼容且选择更新后，返回服务端协商出的更新地址；没有则为 `null` |
| `updateRuntimeProtocolFlags(tcpEnabled, udpEnabled)` | 运行中向服务端请求切换 TCP/UDP 能力；启动前不用它，直接用配置默认值或 setter |
| `isPPV2Enabled()` | 查询当前 PPv2 透传状态；运行中读取运行期配置，未运行时读取初始配置 |
| `setPPV2Enabled(value = true)` | 运行中切换 PPv2 透传；不通知服务端，只影响之后新建的 TCP 连接 |
| `close()` | 请求停止隧道并关闭控制/转发连接，适合绑定 `SIGINT`/`SIGTERM` |

`NeoNode` 表示 NKM 节点。常用方法是 `getName()`、`getRealId()`、`getAddress()`、`getHookPort()`、`getConnectPort()` 和 `toCfg(...)`。

`NeoLinkState` 的状态值是 `STOPPED`、`STARTING`、`RUNNING`、`STOPPING`、`FAILED`。

## 常见错误

- 不要把 `await api.start()` 当成“启动完成后继续执行”的写法。
- 不要在最小调用里重复写默认值，除非你想明确覆盖配置。
- `getTunAddr()` 返回 Promise，必须 `await`。
- 运行中切换协议用 `updateRuntimeProtocolFlags(...)`，启动前默认 TCP/UDP 已启用。
- 运行中切换 PPv2 用 `api.setPPV2Enabled(...)`；已建立的 TCP 连接保持创建时的 PPv2 行为，后续新 TCP 连接使用新值。
- Node.js 的 UDP 转发只支持 IPv4；如果本地下游 UDP 服务只监听 `::1` 或其他 IPv6 地址，请改为监听 IPv4 地址或在配置中使用 IPv4 地址。
