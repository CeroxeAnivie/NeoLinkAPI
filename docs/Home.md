# NeoLinkAPI Node.js 开发指南

NeoLinkAPI Node.js 版是独立的 TypeScript/npm 包，源码位于仓库根目录的 `src/`，构建产物输出到 `dist/` 和 `dist-cjs/`。

## 入口

- [Node.js 开发指南](Node.js)
- [Node.js API 参考](Node.js-API-Reference)

## 先理解这两点

- `NeoLinkCfg` 构造函数已经包含最小必填项，`localhost`、TCP 启用、UDP 启用都是默认值。
- `api.start()` 返回的 Promise 会等待隧道运行结束，不是“启动完成后立刻 resolve”。
- `NodeFetcher` 拉取 NKM 节点，`NeoNode.toCfg(...)` 可以把节点转换成启动配置。

## 常用命令

所有命令都在仓库根目录执行：

| 目标 | 命令 |
| --- | --- |
| 安装依赖 | `npm install` |
| 构建 ESM 和 CommonJS 产物 | `npm run build` |
| 构建并运行测试 | `npm test` |

## 发布前检查

```cmd
npm ci
npm test
```

`npm test` 会先执行完整构建，再运行 `dist/test/*.js` 下的 Node.js 测试。
