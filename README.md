# NeoLinkAPI

NeoLinkAPI 是一个面向 NeoProxy/NeoLink 隧道场景的 Java API。它提供 TCP/UDP 转发、NKM 节点转换、生命周期控制和运行时协议切换能力。

更完整的逐方法说明已经迁移到项目 Wiki：

- [Wiki Home](https://github.com/CeroxeAnivie/NeoLinkAPI/wiki)

## 引入

### Maven

```xml
<dependency>
    <groupId>top.ceroxe.api</groupId>
    <artifactId>neolinkapi</artifactId>
    <version>7.1.5</version>
</dependency>
```

### Gradle Kotlin DSL

```kotlin
dependencies {
    implementation("top.ceroxe.api:neolinkapi:7.1.5")
}
```

## 最小使用示例

```java
import top.ceroxe.api.neolink.NeoLinkAPI;
import top.ceroxe.api.neolink.NeoLinkCfg;
import top.ceroxe.api.neolink.NeoLinkState;

public class Example {
    public static void main(String[] args) {
        NeoLinkCfg cfg = new NeoLinkCfg(
                "nps.example.com",
                44801,
                44802,
                "your-access-key",
                25565
        )
                .setLocalDomainName("localhost")
                .setTCPEnabled(true)
                .setUDPEnabled(true)
                .setLanguage(NeoLinkCfg.ZH_CH);

        NeoLinkAPI tunnel = new NeoLinkAPI(cfg)
                .setOnStateChanged(state -> {
                    if (state == NeoLinkState.RUNNING) {
                        System.out.println("tunnel is ready");
                    }
                })
                .setOnError((message, cause) -> {
                    System.err.println(message);
                    if (cause != null) {
                        cause.printStackTrace();
                    }
                });

        Runtime.getRuntime().addShutdownHook(new Thread(tunnel::close));

        try {
            tunnel.start();
            System.out.println("tunnel address: " + tunnel.getTunAddr());
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            tunnel.close();
        }
    }
}
```

## 说明

- `NeoLinkCfg` 是运行配置入口。
- `NeoLinkAPI.start()` 会阻塞，直到隧道关闭或发生终止性错误。
- 方法级细节、约束、回调语义和异常映射都在 Wiki 中维护。
