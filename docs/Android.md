# Android 开发指南

Android 版适合直接嵌入 App、前台服务或其他移动端宿主。接入前先记住一条硬约束：`NeoLinkAPI.start()` 是长运行阻塞方法，而且 **不能在主线程调用**。

Android 产物坐标是 `top.ceroxe.api:neolinkapi-android:7.2.0`。共享模型与 NKM 节点工具坐标是 `top.ceroxe.api:neolinkapi-shared:7.2.0`，其中包含 `NodeFetcher`、`NeoNode` 和 `NeoLinkCfg`。不要在 Android 项目中依赖桌面 JVM 产物 `top.ceroxe.api:neolinkapi-desktop`。

源码层面，Android 产物会编译 `packages/java/common` 中的内部运行时实现，以及 `packages/java/shared` 中的公开公共 API。根目录 `shared/` 不是 Java 模块，它只保存跨语言协议契约、fixtures 和版本元数据。

## 最小调用

Android 版依然使用和 Java 版一致的两个核心对象：

- `NeoLinkCfg`：启动前配置对象
- `NeoLinkAPI`：运行期控制对象

最小配置参数也完全一致：

| 参数 | 示例 | 从哪里来 | 用途 |
| --- | --- | --- | --- |
| `remoteDomainName` | `"nps.example.com"` | NeoProxy/NeoLink 服务端地址，或 NKM 节点的 `address` 字段 | API 会连接这个主机的控制端口和转发端口 |
| `hookPort` | `44801` | NeoProxy/NeoLink 服务端控制端口，或 NKM 节点的 `HOST_HOOK_PORT`/`hookPort` 字段 | 启动时建立控制连接、发送 key/版本/协议能力、接收服务端指令 |
| `hostConnectPort` | `44802` | NeoProxy/NeoLink 服务端数据转发端口，或 NKM 节点的 `HOST_CONNECT_PORT`/`connectPort` 字段 | 每次 TCP/UDP 转发会连接这个端口创建数据通道 |
| `key` | `"your-access-key"` | 你在服务端创建隧道/端口时拿到的访问密钥 | 握手鉴权 |
| `localPort` | `25565` | 你设备或局域网内下游服务的端口 | 收到公网连接后，API 会把流量转发到 `localDomainName:localPort` |

最小调用示例：

```java
import android.util.Log;
import top.ceroxe.api.neolink.NeoLinkAPI;
import top.ceroxe.api.neolink.NeoLinkCfg;

NeoLinkAPI api = new NeoLinkAPI(
        new NeoLinkCfg("nps.example.com", 44801, 44802, "your-access-key", 25565)
).bindAndroidLog("NeoLinkAPI");

Thread tunnelThread = new Thread(() -> {
    try {
        api.start();
    } catch (Exception e) {
        Log.e("NeoLinkAPI", "Tunnel stopped.", e);
    }
}, "neolink-android");
tunnelThread.start();
```

## 不要在主线程调用

`start()` 会阻塞到隧道停止、服务端断开、运行期错误或其他线程调用 `close()`。因此它不能放在：

- `Activity` 主线程
- `Fragment` 主线程
- `Application.onCreate()` 主线程
- 任意直接跑在 UI Looper 上的回调

Android 版会主动拒绝这种调用，并抛出 `IllegalStateException`。

推荐承载方式：

- 普通后台线程
- Kotlin 协程里的 `Dispatchers.IO`
- 需要常驻连接时使用前台服务

Kotlin 协程写法：

```kotlin
val api = NeoLinkAPI(
    NeoLinkCfg("nps.example.com", 44801, 44802, "your-access-key", 25565)
).bindAndroidLog("NeoLinkAPI")

scope.launch(Dispatchers.IO) {
    api.start()
}
```

前台服务场景里，建议把 `NeoLinkAPI` 作为服务级成员，在 `onCreate()` 或 `onStartCommand()` 里创建实例，在服务自己的后台执行器里启动，在 `onDestroy()` 里调用 `close()`。

## 推荐日志接法

Android 版新增了：

- `bindAndroidLog()`
- `bindAndroidLog(String tag)`

它会把这些事件统一写进 `android.util.Log`：

- 生命周期状态变化
- 服务端普通消息
- 运行期错误
- 调试事件

例如：

```java
NeoLinkAPI api = new NeoLinkAPI(cfg)
        .bindAndroidLog("NeoLinkAPI");
```

如果你已经有自己的日志或埋点系统，可以继续覆盖：

- `setOnStateChanged(...)`
- `setOnServerMessage(...)`
- `setOnError(...)`
- `setDebugSink(...)`

## 从 NKM 节点启动

```java
import java.util.Map;
import top.ceroxe.api.neolink.NeoLinkAPI;
import top.ceroxe.api.neolink.NeoLinkCfg;
import top.ceroxe.api.neolink.NeoNode;
import top.ceroxe.api.neolink.NodeFetcher;

Map<String, NeoNode> nodes = NodeFetcher.getFromNKM("https://example.com/nkm.json");
NeoNode node = nodes.values().stream().findFirst().orElseThrow();
NeoLinkCfg cfg = node.toCfg("your-access-key", 25565);
NeoLinkAPI api = new NeoLinkAPI(cfg).bindAndroidLog("NeoLinkAPI");
```

`NodeFetcher.getFromNKM(url)` 默认超时是 `1000` 毫秒，也可以传入自定义超时：

```java
Map<String, NeoNode> nodes = NodeFetcher.getFromNKM("https://example.com/nkm.json", 1500);
```

## 常用对象

Android 版常用对象和 Java 版保持一致：

| 对象 | 作用 |
| --- | --- |
| `NeoLinkCfg` | 启动前配置对象 |
| `NeoLinkAPI` | 运行期控制对象 |
| `NeoNode` | NKM 节点对象 |
| `NodeFetcher` | NKM 节点拉取工具 |
| `NeoLinkState` | 生命周期状态枚举 |

## 构建与测试命令

### 在仓库根目录执行

PPv2 默认跟随 `NeoLinkCfg`，而 `NeoLinkCfg` 的默认值是 `false`。运行中可以通过 `api.setPPV2Enabled(...)` 热切换；它只影响之后新建的 TCP 连接，已经建立的连接保持创建时的 PPv2 行为。

| 目标 | 命令 | 作用 |
| --- | --- | --- |
| 构建 Android Java 库 | `npm run build:java:android` | 从根目录调用独立 Android 工程执行 `build` |
| 测试 Android Java 库 | `npm run test:java:android` | 运行 Android Library 的全部单元测试 |
| 快速测试 Android Debug 单测 | `npm run test:java:android:fast` | 只跑 `testDebugUnitTest`，适合日常快速回归 |

### 进入 `packages/java/android/neolinkapi-android` 后执行

| 目标 | 命令 | 作用 |
| --- | --- | --- |
| 构建 Android AAR | `.\gradlew.bat build` | 编译 Android Library 并生成发布产物 |
| 测试 Android 单元测试 | `.\gradlew.bat test` | 运行全部本地单元测试 |
| 快速测试 Android Debug 单测 | `.\gradlew.bat testDebugUnitTest` | 只跑 Debug 变体 |

## 当前 Android 基线

当前 Android artifact 仍然声明 `minSdk 33`。这不是 NeoLink 单独一个模块的问题，而是因为它依赖的 `top.ceroxe.api:ceroxe-core-android` 目前也使用同一条 Android 基线。

所以现在可以明确说：

- Android 结构已经独立
- Android API 已经开始补专属行为和文档
- 但要把兼容面继续下压，必须和 `ceroxe-core-android` 一起做
