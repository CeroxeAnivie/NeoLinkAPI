# NeoLinkAPI Node.js

NeoLinkAPI Node.js 是面向 NeoLink TCP/UDP 隧道的 TypeScript 客户端。源码位于仓库根目录，构建时同时产出 ESM 和 CommonJS。

完整文档：

- [Node.js 开发指南](https://github.com/CeroxeAnivie/NeoLinkAPI/wiki/Node.js)
- [Node.js API 参考](https://github.com/CeroxeAnivie/NeoLinkAPI/wiki/Node.js-API-Reference)

## 项目结构

```text
src/            TypeScript 源码和测试
scripts/        构建辅助脚本
dist/           构建生成的 ESM 产物
dist-cjs/       构建生成的 CommonJS 产物
docs/           Node.js 文档页面
```

`dist/` 和 `dist-cjs/` 是构建产物，不提交到仓库。

## 安装

```cmd
npm install neolinkapi
```

## 快速开始

`NeoLinkCfg` 构造参数是 `remoteDomainName`、`hookPort`、`hostConnectPort`、`key` 和 `localPort`。`localDomainName` 默认是 `localhost`，TCP 和 UDP 默认启用。

`await api.start()` 会等待隧道运行结束，不是“启动完成后立刻 resolve”。

```ts
import {NeoLinkAPI, NeoLinkCfg} from 'neolinkapi';

async function main(): Promise<void> {
    const api = new NeoLinkAPI(
        new NeoLinkCfg('nps.example.com', 44801, 44802, 'your-access-key', 25565)
    );

    process.once('SIGINT', () => api.close());
    await api.start();
}

void main();
```

如果启动后还要继续执行业务逻辑，把 `start()` 的 Promise 留在后台，并用 `getTunAddr()` 等待地址就绪：

```ts
import {NeoLinkAPI, NeoLinkCfg} from 'neolinkapi';

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

## NKM 节点

```ts
import {NodeFetcher} from 'neolinkapi';

const nodes = await NodeFetcher.getFromNKM('https://example.com/nkm.json');
const node = nodes.values().next().value;

if (!node) {
    throw new Error('No node available.');
}

const cfg = node.toCfg('your-access-key', 25565);
```

## PPv2

`NeoLinkCfg` 默认关闭 PPv2。运行期切换只影响之后新建的 TCP 连接，已经建立的连接会保持创建时的 PPv2 状态。

```ts
api.setPPV2Enabled(true);
const ppv2Enabled = api.isPPV2Enabled();
api.setPPV2Enabled(false);
```

## UDP 注意事项

当前 Node.js UDP transformer 监听 IPv4。本地下游 UDP 服务请使用 IPv4 地址，例如 `127.0.0.1`。

## 构建与测试

```cmd
npm install
npm run build
npm test
```

`npm run build` 会把 ESM 产物编译到 `dist/`，把 CommonJS 产物编译到 `dist-cjs/`，并写入 CommonJS 解析所需的嵌套 `package.json`。

`npm test` 会先执行完整构建，再用 Node.js 内置 test runner 运行编译后的测试。
