# Error Mapping

本页说明 `NeoLinkAPI.start()` 会把哪些服务端返回值映射成哪些异常。

## 启动阶段异常

### `UnsupportedVersionException`

作用：
表示当前客户端版本与服务端不兼容。

约束：
- 保留原始服务端响应，可通过 `serverResponse()` 读取。
- 其子类 `UnSupportHostVersionException` 是启动阶段实际抛出的实现。

### `NoSuchKeyException`

作用：
表示密钥不可用或无效。

约束：
- 保留原始服务端响应。
- 其子类 `UnRecognizedKeyException` 表示“未识别的 key”。
- 其子类 `OutDatedKeyException` 表示“key 已过期”。

### `NoMoreNetworkFlowException`

作用：
表示密钥已没有可用流量。

约束：
- 有无参构造和带服务端响应的构造。
- `serverResponse()` 返回原文。

### `PortOccupiedException`

作用：
表示服务端拒绝分配远端端口，因为端口占用或配额已满。

约束：
- 继承 `IOException`。
- `serverResponse()` 返回原文。

### `NoMorePortException`

作用：
表示服务端当前没有可分配端口。

约束：
- 继承 `IOException`。
- `serverResponse()` 返回原文。

## 运行时终止语义

- 服务端发送 `exitNoFlow` 时，会映射成 `NoMoreNetworkFlowException`。
- 服务端发送普通 `exit` 时，如果前置消息里表明是 key 过期、key 失效、版本不兼容、端口占用或端口耗尽，也会按对应异常结束。
- 其他未识别终止信息会落成 `IOException`。

## 推荐处理方式

```java
try {
    tunnel.start();
} catch (UnsupportedVersionException e) {
    System.err.println(e.serverResponse());
} catch (NoSuchKeyException e) {
    System.err.println(e.serverResponse());
} catch (NoMoreNetworkFlowException e) {
    System.err.println(e.serverResponse());
} catch (IOException e) {
    e.printStackTrace();
}
```

## 版本协商

当服务端返回不兼容版本提示时，`NeoLinkAPI` 会先调用 `setUnsupportedVersionDecision(...)` 的决策函数。

- 返回 `true`：会继续向服务端请求更新 URL。
- 返回 `false`：会直接放弃更新 URL 协商。

返回值会通过 `getUpdateURL()` 暴露；如果服务端没有提供有效地址，则返回 `null`。
