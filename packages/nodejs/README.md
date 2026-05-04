# NeoLinkAPI for Node.js

`neolinkapi` 是 NeoLinkAPI 的 Node.js 发布包，源码使用 TypeScript 编写，当前版本 `7.1.6`。

完整开发指南请看：

- [Node.js 开发指南](https://github.com/CeroxeAnivie/NeoLinkAPI/wiki/Node.js)

## 安装

```cmd
npm install neolinkapi
```

## 与 Java 版的关系

Node.js 版和 Java 版保持同一套对象模型：`NeoLinkCfg` 配置启动参数，`NeoLinkAPI` 管理运行期，`NodeFetcher` 拉取 NKM 节点，`NeoNode.toCfg(...)` 把节点转换成启动配置。

差异只来自语言模型：Java 的 `start()` 和 `getTunAddr()` 是阻塞方法；Node.js 的 `start()` 和 `getTunAddr()` 返回 Promise，所以需要 `await` 或放到后台 Promise。

## 最小示例

`NeoLinkCfg` 构造函数已经包含最小必填项。`localDomainName` 默认是 `localhost`，TCP 和 UDP 默认都是启用状态。

构造参数从左到右是：

| 参数 | 示例 | 从哪里来 | 用途 |
| --- | --- | --- | --- |
| `remoteDomainName` | `'nps.example.com'` | NeoProxy/NeoLink 服务端地址，或 NKM 节点的 `address` 字段 | API 会连接这个主机的控制端口和转发端口 |
| `hookPort` | `44801` | NeoProxy/NeoLink 服务端控制端口，或 NKM 节点的 `HOST_HOOK_PORT`/`hookPort` 字段 | 启动控制连接，发送 key/版本/协议能力，接收服务端指令 |
| `hostConnectPort` | `44802` | NeoProxy/NeoLink 服务端数据转发端口，或 NKM 节点的 `HOST_CONNECT_PORT`/`connectPort` 字段 | 每次 TCP/UDP 转发会连接这个端口创建数据通道 |
| `key` | `'your-access-key'` | 服务端创建隧道/端口时生成的访问密钥 | 握手鉴权 |
| `localPort` | `25565` | 本机实际运行的下游服务端口 | 收到公网连接后转发到 `localDomainName:localPort` |

`await api.start()` 会等待隧道运行结束；如果需要启动后继续执行其他逻辑，请把 `api.start()` 放到后台 Promise。

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

## 后台运行示例

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
