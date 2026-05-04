# Configuration

本页覆盖 `NeoLinkCfg` 和 `NeoNode`。这两者负责把“要连哪里、用什么协议、用什么本地端口”变成可执行配置。

## NeoLinkCfg

### `new NeoLinkCfg(String remoteDomainName, int hookPort, int hostConnectPort, String key, int localPort)`

作用：
初始化一份最小可启动配置。它只接受启动必需项，其余能力都通过 setter 补充。

使用：
```java
NeoLinkCfg cfg = new NeoLinkCfg("nps.example.com", 44801, 44802, "access-key", 25565);
```

约束：
- `remoteDomainName` 不能为空白。
- `hookPort`、`hostConnectPort`、`localPort` 必须在 `1..65535`。
- `key` 不能为空白。

### `getRemoteDomainName()` / `setRemoteDomainName(String)`

作用：
读取或修改 NeoProxy 服务器地址。

使用：
```java
cfg.setRemoteDomainName("nps.example.com");
```

约束：
- 不能为空白。
- 会去掉首尾空格。

### `getHookPort()` / `setHookPort(int)`

作用：
读取或修改控制端口，也就是 hook 端口。

约束：
- 端口范围必须是 `1..65535`。

### `getHostConnectPort()` / `setHostConnectPort(int)`

作用：
读取或修改转发端口，也就是连接端口。

约束：
- 端口范围必须是 `1..65535`。

### `getLocalDomainName()` / `setLocalDomainName(String)`

作用：
读取或修改本地服务地址。

使用：
```java
cfg.setLocalDomainName("localhost");
```

约束：
- 不能为空白。
- 允许 IPv4、IPv6 和主机名。

### `getLocalPort()` / `setLocalPort(int)`

作用：
读取或修改本地服务端口。

约束：
- 端口范围必须是 `1..65535`。

### `getKey()` / `setKey(String)`

作用：
读取或修改访问密钥。

约束：
- 不能为空白。
- 会去掉首尾空格。

### `getProxyIPToLocalServer()` / `setProxyIPToLocalServer()` / `setProxyIPToLocalServer(String)`

作用：
配置“到本地服务”的代理链路。

使用：
```java
cfg.setProxyIPToLocalServer("socks->127.0.0.1:7890");
```

约束：
- 空值表示直连。
- 支持 `socks->host:port`、`http->host:port`，以及带认证的 `type->host:port@user;password`。
- 字符串会去掉首尾空格。

### `getProxyIPToNeoServer()` / `setProxyIPToNeoServer()` / `setProxyIPToNeoServer(String)`

作用：
配置“到 NeoProxy 服务器”的代理链路。

约束：
- 规则与 `setProxyIPToLocalServer` 相同。
- 空值表示直连。

### `getHeartBeatPacketDelay()` / `setHeartBeatPacketDelay(int)`

作用：
读取或修改心跳包检测间隔。

约束：
- 必须大于 `0`。
- 默认值是 `1000` 毫秒。

### `isTCPEnabled()` / `setTCPEnabled(boolean)`

作用：
控制是否允许 TCP 转发。

约束：
- 运行时可以通过 `NeoLinkAPI.updateRuntimeProtocolFlags(...)` 调整，但首次启动前必须把配置写对。

### `isUDPEnabled()` / `setUDPEnabled(boolean)`

作用：
控制是否允许 UDP 转发。

约束：
- 同上。

### `isPPV2Enabled()` / `setPPV2Enabled()` / `setPPV2Enabled(boolean)`

作用：
控制是否在 TCP 转发中启用 Proxy Protocol v2。

使用：
```java
cfg.setPPV2Enabled();
```

约束：
- 默认关闭。
- 该开关只影响 TCP 连接链路的代理协议头。

### `getLanguage()` / `setLanguage(String)`

作用：
选择与服务端握手文本匹配的语言集。

使用：
```java
cfg.setLanguage(NeoLinkCfg.ZH_CH);
cfg.setLanguage("en-us");
```

约束：
- 仅接受英文或中文语义：`en`、`en-us`、`en_us`、`english`、`zh`、`zh-cn`、`zh_ch`、`chinese`。
- 输入会转成标准值 `NeoLinkCfg.EN_US` 或 `NeoLinkCfg.ZH_CH`。

### `getClientVersion()` / `setClientVersion(String)`

作用：
读取或修改客户端版本号，握手时会发送给服务端。

约束：
- 不能为空白。
- 版本号必须和实际发布版本保持一致，否则服务端可能返回不兼容版本响应。

### `isDebugMsg()` / `setDebugMsg()` / `setDebugMsg(boolean)`

作用：
控制是否输出调试信息。

约束：
- 默认关闭。
- 调试输出还会受全局调试开关影响。

### 公开常量

- `NeoLinkCfg.EN_US`
- `NeoLinkCfg.ZH_CH`
- `NeoLinkCfg.DEFAULT_PROXY_IP`
- `NeoLinkCfg.DEFAULT_LOCAL_DOMAIN_NAME`
- `NeoLinkCfg.DEFAULT_HEARTBEAT_PACKET_DELAY`

## NeoNode

### `new NeoNode(String name, String realId, String address, String iconSvg, int hookPort, int connectPort)`

作用：
表示 NKM 节点列表中的一个节点。

约束：
- `name`、`realId`、`address` 必须有效。
- `iconSvg` 允许为空。
- `hookPort`、`connectPort` 必须在 `1..65535`。

### `toCfg(String key, int localPort)`

作用：
把节点转换成可直接启动的 `NeoLinkCfg`。

使用：
```java
NeoLinkCfg cfg = node.toCfg("access-key", 25565);
```

约束：
- `key` 不能为空白。
- `localPort` 必须在 `1..65535`。
- 生成的配置会继承该节点的 `address`、`hookPort` 和 `connectPort`。

### `getName()` / `getRealId()` / `getAddress()` / `getIconSvg()` / `getHookPort()` / `getConnectPort()`

作用：
返回节点原始字段。

约束：
- `getIconSvg()` 可能返回 `null`。

### `equals(Object)` / `hashCode()` / `toString()`

作用：
- `equals` 和 `hashCode` 让节点可按值比较。
- `toString` 用于调试输出，SVG 内容不会完整展开。

约束：
- 比较维度包含所有字段。
