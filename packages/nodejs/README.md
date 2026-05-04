# NeoLinkAPI for Node.js

`neolinkapi` 是 NeoLinkAPI 的 Node.js 发布包，源码使用 TypeScript 编写，当前版本 `7.1.6`。

完整开发指南请看：

- [Node.js 开发指南](https://github.com/CeroxeAnivie/NeoLinkAPI/wiki/Node.js)

## 安装

```cmd
npm install neolinkapi
```

## 最小示例

`NeoLinkCfg` 构造函数已经包含最小必填项。`localDomainName` 默认是 `localhost`，TCP 和 UDP 默认都是启用状态。

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
