# NeoLinkAPI for TypeScript / Node.js

`neolinkapi` 是 NeoLinkAPI 的 TypeScript / Node.js 发布包，当前版本 `7.1.6`。

## 安装

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
```

完整 API、Java/TypeScript 双语言完整示例、Monorepo 结构和共享契约说明见仓库根文档：

- [GitHub README](https://github.com/CeroxeAnivie/NeoLinkAPI#readme)
