# Node Discovery

`NodeFetcher` 负责从 NKM 拉取节点列表，并把 JSON 转成 `Map<String, NeoNode>`。

## `getFromNKM(String url)`

作用：
用默认超时时间拉取节点列表。

默认值：
- `DEFAULT_TIMEOUT_MILLIS = 1000`
- `DEFAULT_HOST_HOOK_PORT = 44801`
- `DEFAULT_HOST_CONNECT_PORT = 44802`

约束：
- 只接受 `http` 或 `https` URL。
- 适合在启动阶段或手动刷新节点列表时使用。

## `getFromNKM(String url, int timeoutMillis)`

作用：
从远程 NKM 地址拉取节点列表并解析。

使用：
```java
Map<String, NeoNode> nodes = NodeFetcher.getFromNKM(
        "https://example.com/client/nodelist",
        1500
);
```

约束：
- `url` 不能为空白。
- `timeoutMillis` 必须大于 `0`。
- 请求失败、HTTP 非 200、JSON 结构不合法都会抛 `IOException`。
- 节点列表根节点必须是 JSON 数组。
- 每一项必须是对象。
- 每个对象必须包含非空的 `realId`、`name`、`address`。
- `icon` / `iconSvg` 可选。
- `HOST_HOOK_PORT` / `hookPort` 缺失时使用默认 hook 端口。
- `HOST_CONNECT_PORT` / `connectPort` 缺失时使用默认 connect 端口。
- `realId` 不能重复。

## 返回值语义

- 返回的 `Map` 以 `realId` 为键。
- 每个值都是一个已经校验过的 `NeoNode`。
- JSON 中的端口可以是数字，也可以是数字字符串。

## 推荐用法

```java
Map<String, NeoNode> nodes = NodeFetcher.getFromNKM("https://example.com/client/nodelist");
NeoNode node = nodes.get("node-suqian");
NeoLinkCfg cfg = node.toCfg("access-key", 25565);
```
