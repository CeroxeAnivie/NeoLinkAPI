# NeoLinkAPI

NeoLinkAPI 是面向 Java 应用的嵌入式隧道 API。它不包含 GUI、CLI 交互、配置文件读取、自动更新或节点列表管理；调用方只需要提供明确配置，然后通过 `NeoLinkAPI` 控制 TCP/UDP 隧道生命周期。

## 引入API：

Maven：

```xml
<dependency>
    <groupId>top.ceroxe.api</groupId>
    <artifactId>neolinkapi</artifactId>
    <version>6.1.0</version>
</dependency>
```

Gradle Kotlin DSL：

```kotlin
dependencies {
    implementation("top.ceroxe.api:neolinkapi:6.1.0")
}
```

Gradle Groovy DSL：

```groovy
dependencies {
    implementation 'top.ceroxe.api:neolinkapi:6.1.0'
}
```

## 最小可用示例

```java
import top.ceroxe.api.neolink.NeoLinkAPI;
import top.ceroxe.api.neolink.NeoLinkCfg;
import top.ceroxe.api.neolink.NeoLinkState;
import top.ceroxe.api.neolink.exception.NoMoreNetworkFlowException;
import top.ceroxe.api.neolink.exception.NoSuchKeyException;
import top.ceroxe.api.neolink.exception.UnsupportedVersionException;

import java.io.IOException;

public class Example {
    public static void main(String[] args) {
        NeoLinkCfg cfg = new NeoLinkCfg("p.ceroxe.fun", 44801, 44802, "your-key", 25565)
                .setLocalDomainName("localhost")
                .setTCPEnabled(true)
                .setUDPEnabled(true)
                .setPPV2Enabled(false)
                .setLanguage(NeoLinkCfg.ZH_CH)
                .setDebugMsg(false)
                .setHeartBeatPacketDelay(1000);

        NeoLinkAPI tunnel = new NeoLinkAPI(cfg)
                .setOnStateChanged(state -> {
                    if (state == NeoLinkState.RUNNING) {
                        System.out.println("tunnel is running");
                    }
                })
                .setOnRemotePortChanged(port -> {
                    if (port > 0) {
                        System.out.println("public port: " + port);
                    }
                })
                .setOnServerMessage(message -> {
                    System.out.println("server: " + message);
                })
                .setOnError((message, cause) -> {
                    System.err.println("tunnel error: " + message);
                    if (cause != null) {
                        cause.printStackTrace();
                    }
                })
                .setOnConnect((source, target) -> {
                    System.out.println("connected: " + source + " -> " + target);
                })
                .setOnDisconnect((source, target) -> {
                    System.out.println("disconnected: " + source + " -> " + target);
                })
                .setOnConnectNeoFailure(() -> {
                    System.err.println("failed to connect NeoProxyServer transfer port");
                })
                .setOnConnectLocalFailure(() -> {
                    System.err.println("failed to connect local service");
                })
                .setDebugSink((message, cause) -> {
                    if (message != null) {
                        System.out.println("[debug] " + message);
                    }
                    if (cause != null) {
                        cause.printStackTrace();
                    }
                });

        try {
            tunnel.start();

            Runtime.getRuntime().addShutdownHook(new Thread(tunnel::close));
            Thread.currentThread().join();
        } catch (UnsupportedVersionException e) {
            System.err.println("unsupported version: " + e.serverResponse());
        } catch (NoSuchKeyException e) {
            System.err.println("key rejected: " + e.serverResponse());
        } catch (NoMoreNetworkFlowException e) {
            System.err.println("no traffic left: " + e.serverResponse());
        } catch (IOException e) {
            System.err.println("tunnel I/O failure: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            tunnel.close();
        }
    }
}
```

## 代理示例

代理配置只绑定当前 `NeoLinkAPI` 对象，不写 JVM 全局代理，也不安装全局 `Authenticator`。TCP 和 UDP 的控制连接、传输连接都会按 `proxyIPToNeoServer` 连接远端；连接本地下游时按 `proxyIPToLocalServer` 执行。

```java
NeoLinkCfg cfg = new NeoLinkCfg("nps.example.com", 44801, 44802, "your-key", 25565)
        .setProxyIPToNeoServer("socks->127.0.0.1:14455")
        .setProxyIPToLocalServer("http->127.0.0.1:8080");
```

支持格式：

```text
socks->127.0.0.1:14455
http->127.0.0.1:8080
socks->[::1]:14455@user;password
http->proxy.example.com:8080@user;password
```

未知代理类型会直接抛出 `IllegalArgumentException`，这是预期行为。

## 对外 API

### `NeoLinkCfg`

构造函数：

```java
new NeoLinkCfg(remoteDomainName, hookPort, hostConnectPort, key, localPort)
```

必填参数：

- `remoteDomainName`：NeoProxyServer 域名或 IP，不能为空。
- `hookPort`：控制连接端口，范围 `1..65535`。
- `hostConnectPort`：数据传输连接端口，范围 `1..65535`。
- `key`：访问密钥，不能为空。
- `localPort`：本地下游服务端口，范围 `1..65535`。

常量：

- `NeoLinkCfg.EN_US`：英文握手语言，值为 `en`。
- `NeoLinkCfg.ZH_CH`：中文握手语言，值为 `zh`。
- `NeoLinkCfg.DEFAULT_PROXY_IP`：默认空代理，值为 `""`。
- `NeoLinkCfg.DEFAULT_LOCAL_DOMAIN_NAME`：默认本地下游地址，值为 `localhost`。
- `NeoLinkCfg.DEFAULT_HEARTBEAT_PACKET_DELAY`：默认心跳间隔，值为 `1000` 毫秒。

远端配置：

- `getRemoteDomainName()`：读取远端地址。
- `setRemoteDomainName(String)`：设置远端地址，返回 `NeoLinkCfg`。
- `getHookPort()`：读取控制连接端口。
- `setHookPort(int)`：设置控制连接端口，返回 `NeoLinkCfg`。
- `getHostConnectPort()`：读取传输连接端口。
- `setHostConnectPort(int)`：设置传输连接端口，返回 `NeoLinkCfg`。

本地下游配置：

- `getLocalDomainName()`：读取本地下游地址，默认 `localhost`。
- `setLocalDomainName(String)`：设置本地下游地址，返回 `NeoLinkCfg`。
- `getLocalPort()`：读取本地下游端口。
- `setLocalPort(int)`：设置本地下游端口，返回 `NeoLinkCfg`。

访问密钥：

- `getKey()`：读取访问密钥。
- `setKey(String)`：设置访问密钥，返回 `NeoLinkCfg`。

代理：

- `getProxyIPToLocalServer()`：读取本地下游代理配置。
- `setProxyIPToLocalServer()`：清空本地下游代理，返回 `NeoLinkCfg`。
- `setProxyIPToLocalServer(String)`：设置本地下游代理，返回 `NeoLinkCfg`。
- `getProxyIPToNeoServer()`：读取远端代理配置。
- `setProxyIPToNeoServer()`：清空远端代理，返回 `NeoLinkCfg`。
- `setProxyIPToNeoServer(String)`：设置远端代理，返回 `NeoLinkCfg`。

传输协议：

- `isTCPEnabled()`：读取 TCP 是否启用，默认 `true`。
- `setTCPEnabled(boolean)`：设置 TCP 是否启用，返回 `NeoLinkCfg`。
- `isUDPEnabled()`：读取 UDP 是否启用，默认 `true`。
- `setUDPEnabled(boolean)`：设置 UDP 是否启用，返回 `NeoLinkCfg`。
- `isPPV2Enabled()`：读取是否透传 Proxy Protocol v2，默认 `false`。
- `setPPV2Enabled()`：启用 PPv2，返回 `NeoLinkCfg`。
- `setPPV2Enabled(boolean)`：设置 PPv2 开关，返回 `NeoLinkCfg`。

语言、心跳和调试：

- `getLanguage()`：读取握手语言。
- `setLanguage(String)`：设置握手语言，接受 `en`、`en-us`、`en_us`、`english`、`zh`、`zh-cn`、`zh_ch`、`chinese`。
- `getClientVersion()`：读取握手阶段上报给 NeoProxyServer 的客户端版本，默认等于当前 API 包版本。
- `setClientVersion(String)`：设置握手版本，供桌面客户端、兼容性测试或自动更新流程精确控制服务端看到的版本。
- `getHeartBeatPacketDelay()`：读取心跳间隔。
- `setHeartBeatPacketDelay(int)`：设置心跳间隔，必须大于 `0`。
- `isDebugMsg()`：读取是否输出详细英文调试日志，默认 `false`。
- `setDebugMsg()`：启用详细英文调试日志。
- `setDebugMsg(boolean)`：设置调试日志开关。

### `NeoLinkState`

生命周期状态：

- `STOPPED`：隧道未运行，API 持有的 socket、心跳线程和 worker 已释放。
- `STARTING`：正在建立控制连接并等待握手结果。
- `RUNNING`：握手已完成，正在监听服务端指令。
- `STOPPING`：调用方请求关闭，API 正在释放资源。
- `FAILED`：隧道遇到终止性运行期错误；清理完成后会进入 `STOPPED`。

### `NeoLinkAPI`

构造函数：

```java
NeoLinkAPI tunnel = new NeoLinkAPI(cfg);
```

生命周期：

- `start()`：建立控制连接、发送握手、启动心跳和服务端指令监听。该方法会阻塞到握手成功或失败。
- `close()`：关闭控制连接、心跳线程、所有活动 TCP/UDP 转发连接和内部 executor，不抛受检异常。
- `isActive()`：返回隧道是否仍处于活动状态。
- `getRemotePort()`：读取服务端下发的公网端口；尚未收到时为 `0`。
- `getState()`：读取最近一次生命周期状态。
- `version()`：静态方法，返回当前 API 版本。

`start()` 已经执行且隧道仍活动时，再次调用会直接返回。`close()` 完成后，同一个 `NeoLinkAPI` 实例可以再次调用 `start()`；新的启动会重新复制当前 `NeoLinkCfg` 配置。

回调：

- `setOnStateChanged(Consumer<NeoLinkState>)`：生命周期变化时触发，适合驱动 UI、外部 supervisor 或重试策略。
- `setOnError(BiConsumer<String, Throwable>)`：运行期业务可见错误回调，例如心跳失败、控制连接异常关闭、流量耗尽或转发连接创建失败。
- `setOnServerMessage(Consumer<String>)`：服务端普通文本消息回调；非 `:>` 控制命令的消息会通过这里交给调用方展示或记录。
- `setOnRemotePortChanged(IntConsumer)`：服务端下发公网端口时触发；`getRemotePort()` 会同步更新。
- `setOnConnect(BiConsumer<InetSocketAddress, InetSocketAddress>)`：转发连接建立时触发，参数为远端访问者地址和本地下游地址。
- `setOnConnect(Runnable)`：不关心地址时使用的连接建立回调。
- `setOnDisconnect(BiConsumer<InetSocketAddress, InetSocketAddress>)`：转发连接断开时触发，参数语义同 `setOnConnect`。
- `setOnDisconnect(Runnable)`：不关心地址时使用的连接断开回调。
- `setOnConnectNeoFailure(Runnable)`：连接 NeoProxyServer 传输端口失败时触发。
- `setOnConnectLocalFailure(Runnable)`：连接本地下游服务失败时触发。
- `setUnsupportedVersionDecision(Function<String, Boolean>)`：服务端拒绝当前版本时，决定是否回复 `true` 表示调用方将自行处理更新；API 不执行下载或替换文件。
- `setDebugSink(BiConsumer<String, Throwable>)`：实例级调试事件接收器，只接收诊断细节，不承载业务错误。

所有调用方回调都会被异常隔离。回调抛出的 `RuntimeException` 会进入 debug sink，不会中断隧道生命周期线程或转发线程。

### 异常

`NeoLinkAPI.start()` 声明以下受检异常：

- `UnsupportedVersionException`：服务端拒绝当前 API 版本。
- `NoSuchKeyException`：密钥错误、过期、占用或被服务端拒绝。
- `NoMoreNetworkFlowException`：服务端通知剩余流量耗尽。
- `IOException`：网络连接失败、连接被关闭或其他 I/O 失败。

三个业务异常都提供：

- `serverResponse()`：返回服务端原始响应，便于日志记录和错误展示。

## 行为说明

- TCP/UDP 开关在 `NeoLinkCfg` 阶段设置，并参与握手；不要在 `start()` 后再切换协议声明。
- `NeoLinkCfg` 在 `start()` 时会被复制，运行中的隧道不会被后续配置修改影响。
- `clientVersion` 默认等于 API 包版本；只有需要模拟旧客户端、对接桌面自动更新或做兼容性探针时才应显式设置。
- PPv2 默认关闭。只有 Nginx、HAProxy 等本地下游已经配置 accept-proxy 时才应开启。
- 连接超时统一为 `5000ms`，覆盖连接 NeoProxyServer 控制端口、传输端口和本地下游服务。
- Debug 日志是英文，包含握手、连接、代理、转发、关闭等细节；密钥在日志中会被遮蔽。推荐通过 `setDebugSink` 接管实例级调试输出，避免库直接污染宿主应用控制台。
- 业务错误走 `setOnError`，生命周期走 `setOnStateChanged`，服务端普通消息走 `setOnServerMessage`，调试细节走 `setDebugSink`。不要用 debug 日志推断业务状态。
