# NeoLinkAPI

NeoLinkAPI 是同一套 NeoLink 协议栈的 Monorepo，统一维护 `Java` 和 `TypeScript/Node.js` 两个实现。

```text
packages/
  java/      Java 库，使用 Gradle Wrapper 构建与发布
  nodejs/    TypeScript 库，使用 npm 构建与发布
shared/      跨语言共享的协议契约、fixtures 和 Mock 数据
```

`shared` 只存放跨语言共享事实，不放运行时代码。协议、默认值、握手样例和 NKM fixtures 都应先落在这里，再同步到两个实现。

## 引入方式

### npm

```cmd
npm install neolinkapi
```

### Maven

```xml
<dependency>
    <groupId>top.ceroxe.api</groupId>
    <artifactId>neolinkapi</artifactId>
    <version>7.1.6</version>
</dependency>
```

### Gradle Kotlin DSL

```kotlin
dependencies {
    implementation("top.ceroxe.api:neolinkapi:7.1.6")
}
```

### Gradle Groovy DSL

```groovy
dependencies {
    implementation 'top.ceroxe.api:neolinkapi:7.1.6'
}
```

## Java 完整调用示例

Java 对外入口集中在 `NeoLinkCfg`、`NeoLinkAPI`、`NeoNode`、`NodeFetcher`、`NeoLinkState` 和异常类型。

```java
import top.ceroxe.api.neolink.NeoLinkAPI;
import top.ceroxe.api.neolink.NeoLinkCfg;
import top.ceroxe.api.neolink.NeoLinkState;
import top.ceroxe.api.neolink.NeoNode;
import top.ceroxe.api.neolink.NodeFetcher;
import top.ceroxe.api.neolink.exception.NoMoreNetworkFlowException;
import top.ceroxe.api.neolink.exception.NoMorePortException;
import top.ceroxe.api.neolink.exception.NoSuchKeyException;
import top.ceroxe.api.neolink.exception.OutDatedKeyException;
import top.ceroxe.api.neolink.exception.PortOccupiedException;
import top.ceroxe.api.neolink.exception.UnRecognizedKeyException;
import top.ceroxe.api.neolink.exception.UnSupportHostVersionException;
import top.ceroxe.api.neolink.exception.UnsupportedVersionException;
import top.ceroxe.api.net.SecureSocket;

import java.io.IOException;
import java.util.Map;

public final class JavaExample {
    public static void main(String[] args) throws Exception {
        System.out.println("NeoLinkAPI.version() = " + NeoLinkAPI.version());

        NeoLinkCfg cfg = new NeoLinkCfg("nps.example.com", 44801, 44802, "your-access-key", 25565)
                .setRemoteDomainName("nps.example.com")
                .setHookPort(44801)
                .setHostConnectPort(44802)
                .setLocalDomainName("localhost")
                .setLocalPort(25565)
                .setKey("your-access-key")
                .setProxyIPToLocalServer()
                .setProxyIPToLocalServer("127.0.0.1")
                .setProxyIPToNeoServer()
                .setProxyIPToNeoServer("127.0.0.1")
                .setHeartBeatPacketDelay(NeoLinkCfg.DEFAULT_HEARTBEAT_PACKET_DELAY)
                .setTCPEnabled(true)
                .setUDPEnabled(true)
                .setPPV2Enabled()
                .setPPV2Enabled(true)
                .setLanguage(NeoLinkCfg.ZH_CH)
                .setClientVersion(NeoLinkAPI.version())
                .setDebugMsg()
                .setDebugMsg(true);

        cfg.requireStartReady();
        NeoLinkCfg copiedCfg = cfg.copy();
        System.out.println(copiedCfg.getRemoteDomainName());
        System.out.println(copiedCfg.getHookPort());
        System.out.println(copiedCfg.getHostConnectPort());
        System.out.println(copiedCfg.getLocalDomainName());
        System.out.println(copiedCfg.getLocalPort());
        System.out.println(copiedCfg.getKey());
        System.out.println(copiedCfg.getProxyIPToLocalServer());
        System.out.println(copiedCfg.getProxyIPToNeoServer());
        System.out.println(copiedCfg.getHeartBeatPacketDelay());
        System.out.println(copiedCfg.isTCPEnabled());
        System.out.println(copiedCfg.isUDPEnabled());
        System.out.println(copiedCfg.isPPV2Enabled());
        System.out.println(copiedCfg.getLanguage());
        System.out.println(copiedCfg.getClientVersion());
        System.out.println(copiedCfg.isDebugMsg());

        Map<String, NeoNode> nodes = NodeFetcher.getFromNKM("https://example.com/nkm.json");
        Map<String, NeoNode> nodesWithTimeout = NodeFetcher.getFromNKM("https://example.com/nkm.json", 1500);
        NeoNode node = nodesWithTimeout.values().stream().findFirst().orElseThrow();
        NeoLinkCfg nodeCfg = node.toCfg("your-access-key", 25565);
        System.out.println(node.getName());
        System.out.println(node.getRealId());
        System.out.println(node.getAddress());
        System.out.println(node.getIconSvg());
        System.out.println(node.getHookPort());
        System.out.println(node.getConnectPort());
        System.out.println(node.equals(node));
        System.out.println(node.hashCode());
        System.out.println(node.toString());
        nodeCfg.requireStartReady();

        NeoLinkAPI api = new NeoLinkAPI(copiedCfg)
                .setOnStateChanged(state -> {
                    if (state == NeoLinkState.RUNNING) {
                        System.out.println("Tunnel is ready.");
                    }
                    if (state == NeoLinkState.FAILED) {
                        System.err.println("Tunnel entered FAILED state.");
                    }
                })
                .setOnError((message, cause) -> {
                    System.err.println(message);
                    if (cause != null) {
                        cause.printStackTrace();
                    }
                })
                .setOnServerMessage(System.out::println)
                .setUnsupportedVersionDecision(serverVersion -> {
                    System.out.println("Server version: " + serverVersion);
                    return true;
                })
                .setDebugSink((message, cause) -> {
                    System.out.println("[debug] " + message);
                    if (cause != null) {
                        cause.printStackTrace();
                    }
                })
                .setOnConnect((protocol, source, target) ->
                        System.out.println("connect " + protocol + " " + source + " -> " + target))
                .setOnDisconnect((protocol, source, target) ->
                        System.out.println("disconnect " + protocol + " " + source + " -> " + target))
                .setOnConnectNeoFailure(() -> System.err.println("connect Neo side failed"))
                .setOnConnectLocalFailure(() -> System.err.println("connect local side failed"));

        Runtime.getRuntime().addShutdownHook(new Thread(api::close));

        try {
            api.start(1500);
            System.out.println(api.isActive());
            System.out.println(api.getTunAddr());
            SecureSocket hookSocket = api.getHookSocket();
            System.out.println(hookSocket);
            System.out.println(api.getUpdateURL());
            System.out.println(api.getState());
            api.updateRuntimeProtocolFlags(true, false);
            api.updateRuntimeProtocolFlags(true, true);
        } catch (UnsupportedVersionException
                 | UnSupportHostVersionException
                 | NoSuchKeyException
                 | OutDatedKeyException
                 | UnRecognizedKeyException
                 | NoMoreNetworkFlowException
                 | NoMorePortException
                 | PortOccupiedException
                 | IOException e) {
            e.printStackTrace();
        } finally {
            api.close();
        }
    }
}
```

## TypeScript / Node.js 完整调用示例

TypeScript 对外导出集中在 `NeoLinkAPI`、`NeoLinkCfg`、`NeoNode`、`NodeFetcher`、`SecureSocket`、`SecureServerSocket`、`TransportProtocol`、`NeoLinkState`、错误类型、`VERSION` 和 `AUTHOR`。

```ts
import {
  AUTHOR,
  DEFAULT_HEARTBEAT_PACKET_DELAY,
  DEFAULT_HOST_CONNECT_PORT,
  DEFAULT_HOST_HOOK_PORT,
  DEFAULT_LOCAL_DOMAIN_NAME,
  DEFAULT_PROXY_IP,
  DEFAULT_TIMEOUT_MILLIS,
  EN_US,
  NeoLinkAPI,
  NeoLinkCfg,
  NeoLinkError,
  NeoLinkState,
  NeoNode,
  NodeFetcher,
  NoMoreNetworkFlowException,
  NoMorePortException,
  NoSuchKeyException,
  OutDatedKeyException,
  PortOccupiedException,
  SecureServerSocket,
  SecureSocket,
  ServerResponseError,
  TransportProtocol,
  UnRecognizedKeyException,
  UnsupportedVersionException,
  UnSupportHostVersionException,
  VERSION,
  ZH_CH,
  getFromNKM,
  parseNodeMap
} from 'neolinkapi';

async function main(): Promise<void> {
  console.log(VERSION, AUTHOR);

  const cfg = new NeoLinkCfg('nps.example.com', 44801, 44802, 'your-access-key', 25565)
    .setRemoteDomainName('nps.example.com')
    .setHookPort(44801)
    .setHostConnectPort(44802)
    .setLocalDomainName(DEFAULT_LOCAL_DOMAIN_NAME)
    .setLocalPort(25565)
    .setKey('your-access-key')
    .setProxyIPToLocalServer()
    .setProxyIPToLocalServer(DEFAULT_PROXY_IP)
    .setProxyIPToNeoServer()
    .setProxyIPToNeoServer(DEFAULT_PROXY_IP)
    .setHeartBeatPacketDelay(DEFAULT_HEARTBEAT_PACKET_DELAY)
    .setTCPEnabled(true)
    .setUDPEnabled(true)
    .setPPV2Enabled()
    .setPPV2Enabled(true)
    .setLanguage(ZH_CH)
    .setClientVersion(NeoLinkAPI.version())
    .setDebugMsg()
    .setDebugMsg(true);

  cfg.requireStartReady();
  const copiedCfg = cfg.copy();
  console.log(copiedCfg.getRemoteDomainName());
  console.log(copiedCfg.getHookPort());
  console.log(copiedCfg.getHostConnectPort());
  console.log(copiedCfg.getLocalDomainName());
  console.log(copiedCfg.getLocalPort());
  console.log(copiedCfg.getKey());
  console.log(copiedCfg.getProxyIPToLocalServer());
  console.log(copiedCfg.getProxyIPToNeoServer());
  console.log(copiedCfg.getHeartBeatPacketDelay());
  console.log(copiedCfg.isTCPEnabled());
  console.log(copiedCfg.isUDPEnabled());
  console.log(copiedCfg.isPPV2Enabled());
  console.log(copiedCfg.getLanguage());
  console.log(copiedCfg.getClientVersion());
  console.log(copiedCfg.isDebugMsg());
  console.log(EN_US, ZH_CH, DEFAULT_TIMEOUT_MILLIS, DEFAULT_HOST_HOOK_PORT, DEFAULT_HOST_CONNECT_PORT);

  const nodes = await getFromNKM('https://example.com/nkm.json');
  const moreNodes = await NodeFetcher.getFromNKM('https://example.com/nkm.json', 1500);
  const parsedNodes = parseNodeMap(JSON.stringify({
    nodes: [
      {
        name: 'Demo Node',
        id: 'demo-node',
        addr: 'demo.example.com',
        icon: null,
        hookPort: 44801,
        connectPort: 44802
      }
    ]
  }));
  const node: NeoNode = [...moreNodes.values(), ...parsedNodes.values(), ...nodes.values()][0];
  const nodeCfg = node.toCfg('your-access-key', 25565);
  console.log(node.getName());
  console.log(node.getRealId());
  console.log(node.getAddress());
  console.log(node.getIconSvg());
  console.log(node.getHookPort());
  console.log(node.getConnectPort());
  console.log(node.equals(node));
  console.log(node.toString());
  nodeCfg.requireStartReady();

  const api = new NeoLinkAPI(copiedCfg)
    .setOnStateChanged((state) => {
      if (state === NeoLinkState.RUNNING) {
        console.log('Tunnel is ready.');
      }
      if (state === NeoLinkState.FAILED) {
        console.error('Tunnel entered FAILED state.');
      }
    })
    .setOnError((message, cause) => {
      console.error(message);
      if (cause) {
        console.error(cause);
      }
    })
    .setOnServerMessage((message) => console.log(message))
    .setUnsupportedVersionDecision((serverVersion) => {
      console.log(serverVersion);
      return true;
    })
    .setDebugSink((message, cause) => {
      console.log('[debug]', message);
      if (cause) {
        console.error(cause);
      }
    })
    .setOnConnect((protocol, source, target) => {
      if (protocol === TransportProtocol.TCP || protocol === TransportProtocol.UDP) {
        console.log('connect', protocol, source, target);
      }
    })
    .setOnDisconnect((protocol, source, target) => {
      console.log('disconnect', protocol, source, target);
    })
    .setOnConnectNeoFailure(() => console.error('connect Neo side failed'))
    .setOnConnectLocalFailure(() => console.error('connect local side failed'));

  process.once('SIGINT', () => api.close());
  process.once('SIGTERM', () => api.close());

  try {
    console.log(NeoLinkAPI.version());
    console.log(NeoLinkAPI.parseTunAddrMessage('Use the address: 127.0.0.1:25565 to start up connections.'));
    console.log(NeoLinkAPI.classifyStartupHandshakeFailure('exitNoFlow'));
    console.log(NeoLinkAPI.isSuccessfulHandshakeResponse('success'));

    const startPromise = api.start(1500);
    console.log(api.isActive());
    console.log(await api.getTunAddr());
    console.log(api.getHookSocket());
    console.log(api.getUpdateURL());
    console.log(api.getState());
    await api.updateRuntimeProtocolFlags(true, false);
    await api.updateRuntimeProtocolFlags(true, true);
    await startPromise;
  } catch (error) {
    if (
      error instanceof NeoLinkError ||
      error instanceof ServerResponseError ||
      error instanceof UnsupportedVersionException ||
      error instanceof UnSupportHostVersionException ||
      error instanceof NoSuchKeyException ||
      error instanceof OutDatedKeyException ||
      error instanceof UnRecognizedKeyException ||
      error instanceof NoMoreNetworkFlowException ||
      error instanceof NoMorePortException ||
      error instanceof PortOccupiedException
    ) {
      console.error(error);
    }
    throw error;
  } finally {
    api.close();
  }

  SecureSocket.setMaxAllowedPacketSize(SecureSocket.getMaxAllowedPacketSize());
  SecureSocket.setDefaultConnectTimeoutMillis(SecureSocket.getDefaultConnectTimeoutMillis());

  const server = await SecureServerSocket.listen(0, '127.0.0.1');
  try {
    const acceptPromise = server.accept(5000);
    const client = await SecureSocket.connect('127.0.0.1', server.getLocalPort(), 1000);
    try {
      await client.sendStr('hello');
      await client.sendBytes(Buffer.from('payload'));
      await client.sendInt(7);
      console.log(client.isClosed(), client.isConnected(), client.isConnectionBroken());
      console.log(client.getPort(), client.getLocalPort());
      console.log(client.getRemoteSocketAddress(), client.getLocalSocketAddress());

      const accepted = await acceptPromise;
      try {
        accepted.initServerMode();
        await accepted.ensureHandshake();
        console.log(await accepted.receiveStr(1000));
        console.log(await accepted.receiveBytes(1000));
        console.log(await accepted.receiveInt(1000));
        console.log(await accepted.readRawPacketInternal(1).catch(() => null));
        accepted.shutdownInput();
        accepted.shutdownOutput();
      } finally {
        accepted.close();
      }
    } finally {
      client.close();
    }

    server.addIgnoreIP('127.0.0.2');
    console.log(server.getIgnoreIPs());
    console.log(server.removeIgnoreIP('127.0.0.2'));
    console.log(server.isClosed());
  } finally {
    server.close();
  }
}

void main();
```

## 仓库级命令

```cmd
npm install
npm run check:shared
npm run build
npm run test
```

单独构建 Java：

```cmd
cd packages\java
gradlew.bat build
gradlew.bat test
```

单独构建 TypeScript：

```cmd
cd packages\nodejs
npm install
npm run build
npm run test
```
