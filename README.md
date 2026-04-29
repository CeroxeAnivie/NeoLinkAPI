# NeoLinkAPI

NeoLinkAPI 是面向 Java 应用的基础隧道控制 API。它把远端服务域名、控制端口、传输端口、访问密钥和本地服务端口封装为清晰的对象接口，让业务程序可以直接创建、监听和关闭 TCP/UDP 隧道。

这个项目只提供嵌入式 API 能力，不绑定 GUI、命令行交互、配置文件加载、自动重连、自动更新或节点列表管理。调用方负责自己的业务生命周期，NeoLinkAPI 负责隧道连接、转发状态和异常语义。

## 对外 API 包

```text
top.ceroxe.api.neolink
```

主要入口：

- `NeoLinkCfg`：隧道配置。
- `NeoLink`：隧道生命周期、连接回调和传输开关。

## 最小用法

```java
import top.ceroxe.api.neolink.NeoLink;
import top.ceroxe.api.neolink.NeoLinkCfg;
import top.ceroxe.api.neolink.exception.NoMoreNetworkFlowException;
import top.ceroxe.api.neolink.exception.NoSuchKeyException;
import top.ceroxe.api.neolink.exception.UnsupportedVersionException;

import java.io.IOException;

public class Example {
    public static void main(String[] args) {
        NeoLinkCfg nc = new NeoLinkCfg("p.ceroxe.fun", 44801, 44802, "your-key", 25565);
        nc.setLocalDomainName("localhost");
        nc.setProxyIPToLocalServer();
        nc.setProxyIPToNeoServer();
        nc.setHeartBeatPacketDelay(1000);

        NeoLink nl = new NeoLink(nc);
        nl.setOnConnect((source, target) -> {
            // source 是远端连接地址，target 是本地服务地址。
        });
        nl.setOnDisconnect((source, target) -> {
            // 连接断开时触发，参数语义和 onConnect 一致。
        });
        nl.setOnConnectNeoFailure(() -> {
            // 连接远端传输端口失败时触发。
        });
        nl.setOnConnectLocalFailure(() -> {
            // 连接本地服务失败时触发。
        });

        try {
            nl.start();
            nl.setTCPEnabled(true);
            nl.setUDPEnabled(true);
        } catch (UnsupportedVersionException e) {
            // API 版本不被服务端支持。
        } catch (NoSuchKeyException e) {
            // 密钥错误、过期、占用或被服务端拒绝。
        } catch (NoMoreNetworkFlowException e) {
            // 服务端通知流量耗尽。
        } catch (IOException e) {
            // 网络连接失败或隧道运行失败。
        } finally {
            nl.close();
        }
    }
}
```

## 配置项

构造函数参数全部必填，顺序固定：

```java
new NeoLinkCfg(remoteDomainName, hookPort, hostConnectPort, key, localPort);
```

- `remoteDomainName`：远端服务域名。
- `hookPort`：控制连接端口。
- `hostConnectPort`：传输连接端口。
- `key`：访问密钥，只使用 `getKey()` / `setKey()`。
- `localPort`：本地服务端口。
- `localDomainName`：本地域名，默认 `localhost`。
- `proxyIPToLocalServer`：默认空字符串，空值表示不走本地代理。
- `proxyIPToNeoServer`：默认空字符串，空值表示不走远端代理。
- `heartBeatPacketDelay`：心跳检测间隔，默认 `1000` 毫秒。

## 生命周期

- `start()`：建立隧道，启动后开始接收远端连接指令。
- `isActive()`：查询隧道是否处于运行状态。
- `setTCPEnabled(boolean)`：隧道启动后动态开启或关闭 TCP 转发，默认开启。
- `setUDPEnabled(boolean)`：隧道启动后动态开启或关闭 UDP 转发，默认开启。
- `close()`：立即关闭隧道、控制连接和所有活动转发连接，不向调用方抛出异常。

## 异常语义

`NeoLink.start()` 只声明以下受检异常：

- `UnsupportedVersionException`：服务端返回不支持当前 API 版本。
- `NoSuchKeyException`：密钥错误、过期、被占用或服务端拒绝。
- `NoMoreNetworkFlowException`：服务端通知流量耗尽。
- `IOException`：网络连接失败、连接被关闭或其他 I/O 失败。

## 构建

```powershell
chcp 65001 >nul
.\gradlew.bat clean test
```
