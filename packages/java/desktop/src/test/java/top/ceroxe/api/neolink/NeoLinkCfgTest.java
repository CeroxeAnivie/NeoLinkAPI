package top.ceroxe.api.neolink;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("NeoLinkCfg 公开 API 配置")
class NeoLinkCfgTest {
    private static Field declaredField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Method declaredMethod(Class<?> type, String name, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredMethod(name, parameterTypes);
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchMethodException(name);
    }

    @Test
    @DisplayName("构造函数强制要求远程域名、Hook 端口、连接端口、密钥和本地端口")
    void constructorRequiresAllMandatoryValues() {
        assertThrows(IllegalArgumentException.class, () -> new NeoLinkCfg("", 44801, 44802, "key", 25565));
        assertThrows(IllegalArgumentException.class, () -> new NeoLinkCfg("top.ceroxe.example", 0, 44802, "key", 25565));
        assertThrows(IllegalArgumentException.class, () -> new NeoLinkCfg("top.ceroxe.example", 44801, 0, "key", 25565));
        assertThrows(IllegalArgumentException.class, () -> new NeoLinkCfg("top.ceroxe.example", 44801, 44802, "", 25565));
        assertThrows(IllegalArgumentException.class, () -> new NeoLinkCfg("top.ceroxe.example", 44801, 44802, "key", 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new NeoLinkCfg("top.ceroxe.example", 44801, 44802, "key", 25565).setClientVersion(" ")
        );
    }

    @Test
    @DisplayName("默认值与 config.cfg 的 API 语义保持一致")
    void defaultsMatchDocumentedApiSemantics() {
        NeoLinkCfg cfg = new NeoLinkCfg("top.ceroxe.example", 44801, 44802, "key", 25565);

        assertEquals("localhost", cfg.getLocalDomainName());
        assertEquals("key", cfg.getKey());
        assertEquals("", cfg.getProxyIPToLocalServer());
        assertEquals("", cfg.getProxyIPToNeoServer());
        assertEquals(1000, cfg.getHeartBeatPacketDelay());
        assertTrue(cfg.isTCPEnabled());
        assertTrue(cfg.isUDPEnabled());
        assertFalse(cfg.isPPV2Enabled());
        assertFalse(cfg.isDebugMsg());
        assertEquals(NeoLinkCfg.ZH_CH, cfg.getLanguage());
        assertEquals(NeoLinkAPI.version(), cfg.getClientVersion());
        assertEquals("7.1.12", NeoLinkAPI.version());
    }

    @Test
    @DisplayName("setter 修改后的值与 getter 保持一致")
    void settersStaySynchronizedWithGetters() {
        NeoLinkCfg cfg = new NeoLinkCfg("top.ceroxe.example", 44801, 44802, "key", 25565)
                .setRemoteDomainName("nps.example.com")
                .setHookPort(30001)
                .setHostConnectPort(30002)
                .setLocalDomainName("127.0.0.1")
                .setLocalPort(19132)
                .setKey("secret-key")
                .setProxyIPToLocalServer("socks->127.0.0.1:7890")
                .setProxyIPToNeoServer("http->127.0.0.1:8080")
                .setHeartBeatPacketDelay(1500)
                .setTCPEnabled(false)
                .setUDPEnabled(true)
                .setPPV2Enabled()
                .setLanguage(NeoLinkCfg.EN_US)
                .setClientVersion("6.0.2-test")
                .setDebugMsg();

        assertEquals("nps.example.com", cfg.getRemoteDomainName());
        assertEquals(30001, cfg.getHookPort());
        assertEquals(30002, cfg.getHostConnectPort());
        assertEquals("127.0.0.1", cfg.getLocalDomainName());
        assertEquals(19132, cfg.getLocalPort());
        assertEquals("secret-key", cfg.getKey());
        assertEquals("socks->127.0.0.1:7890", cfg.getProxyIPToLocalServer());
        assertEquals("http->127.0.0.1:8080", cfg.getProxyIPToNeoServer());
        assertEquals(1500, cfg.getHeartBeatPacketDelay());
        assertFalse(cfg.isTCPEnabled());
        assertTrue(cfg.isUDPEnabled());
        assertTrue(cfg.isPPV2Enabled());
        assertEquals(NeoLinkCfg.EN_US, cfg.getLanguage());
        assertEquals("6.0.2-test", cfg.getClientVersion());
        assertTrue(cfg.isDebugMsg());
    }

    @Test
    @DisplayName("NeoLinkAPI facade 暴露规定的失败回调 setter")
    void neoLinkExposesFailureCallbackSetters() {
        NeoLinkAPI neoLink = new NeoLinkAPI(new NeoLinkCfg("top.ceroxe.example", 44801, 44802, "key", 25565));

        assertSame(neoLink, neoLink.setOnConnectNeoFailure(() -> {
        }));
        assertSame(neoLink, neoLink.setOnConnectLocalFailure(() -> {
        }));
        assertSame(neoLink, neoLink.setOnStateChanged(state -> {
        }));
        assertSame(neoLink, neoLink.setOnError((message, cause) -> {
        }));
        assertSame(neoLink, neoLink.setOnServerMessage(message -> {
        }));
        assertSame(neoLink, neoLink.setOnConnect((protocol, source, target) -> {
        }));
        assertSame(neoLink, neoLink.setOnDisconnect((protocol, source, target) -> {
        }));
        assertSame(neoLink, neoLink.setUnsupportedVersionDecision(response -> false));
        assertSame(neoLink, neoLink.setDebugSink((message, cause) -> {
        }));
        assertFalse(neoLink.isPPV2Enabled());
        assertSame(neoLink, neoLink.setPPV2Enabled());
        assertTrue(neoLink.isPPV2Enabled());
        assertSame(neoLink, neoLink.setPPV2Enabled(false));
        assertFalse(neoLink.isPPV2Enabled());
        assertThrows(IllegalArgumentException.class, () -> neoLink.start(0));
    }

    @Test
    @DisplayName("NeoLinkAPI 的 PPv2 开关应同步写入当前运行期配置")
    void neoLinkPPV2SwitchUpdatesRuntimeConfig() throws Exception {
        NeoLinkCfg cfg = new NeoLinkCfg("top.ceroxe.example", 44801, 44802, "key", 25565);
        NeoLinkAPI neoLink = new NeoLinkAPI(cfg);
        NeoLinkCfg runtimeCfg = cfg.copy();

        var runtimeCfgField = declaredField(NeoLinkAPI.class, "runtimeCfg");
        runtimeCfgField.setAccessible(true);
        runtimeCfgField.set(neoLink, runtimeCfg);

        var runningField = declaredField(NeoLinkAPI.class, "running");
        runningField.setAccessible(true);
        ((AtomicBoolean) runningField.get(neoLink)).set(true);

        assertFalse(neoLink.isPPV2Enabled());

        neoLink.setPPV2Enabled(true);
        assertTrue(cfg.isPPV2Enabled());
        assertTrue(runtimeCfg.isPPV2Enabled());
        assertTrue(neoLink.isPPV2Enabled());

        neoLink.setPPV2Enabled(false);
        assertFalse(cfg.isPPV2Enabled());
        assertFalse(runtimeCfg.isPPV2Enabled());
        assertFalse(neoLink.isPPV2Enabled());
    }

    @Test
    @DisplayName("connection events expose transport protocol and normalized addresses")
    void connectionEventsExposeProtocolAndAddresses() throws Exception {
        NeoLinkCfg cfg = new NeoLinkCfg("top.ceroxe.example", 44801, 44802, "key", 25565);
        NeoLinkAPI neoLink = new NeoLinkAPI(cfg);
        AtomicReference<NeoLinkAPI.TransportProtocol> protocolRef = new AtomicReference<>();
        AtomicReference<InetSocketAddress> sourceRef = new AtomicReference<>();
        AtomicReference<InetSocketAddress> targetRef = new AtomicReference<>();

        var runtimeCfgField = declaredField(NeoLinkAPI.class, "runtimeCfg");
        runtimeCfgField.setAccessible(true);
        runtimeCfgField.set(neoLink, cfg.copy());

        Method emitConnectionEvent = declaredMethod(
                NeoLinkAPI.class,
                "emitConnectionEvent",
                NeoLinkAPI.ConnectionEventHandler.class,
                NeoLinkAPI.TransportProtocol.class,
                String.class
        );
        emitConnectionEvent.setAccessible(true);
        emitConnectionEvent.invoke(
                neoLink,
                (NeoLinkAPI.ConnectionEventHandler) (protocol, source, target) -> {
                    protocolRef.set(protocol);
                    sourceRef.set(source);
                    targetRef.set(target);
                },
                NeoLinkAPI.TransportProtocol.UDP,
                "203.0.113.10:4567"
        );

        assertEquals(NeoLinkAPI.TransportProtocol.UDP, protocolRef.get());
        assertEquals("203.0.113.10", sourceRef.get().getHostString());
        assertEquals(4567, sourceRef.get().getPort());
        assertEquals("localhost", targetRef.get().getHostString());
        assertEquals(25565, targetRef.get().getPort());
    }

    @Test
    @DisplayName("TCP 和 UDP 默认都开启，握手标志应为 TU")
    void tcpAndUdpAreEnabledByDefault() throws Exception {
        NeoLinkCfg cfg = new NeoLinkCfg("top.ceroxe.example", 44801, 44802, "key", 25565);
        NeoLinkAPI neoLink = new NeoLinkAPI(cfg);

        var runtimeCfgField = declaredField(NeoLinkAPI.class, "runtimeCfg");
        runtimeCfgField.setAccessible(true);
        runtimeCfgField.set(neoLink, cfg.copy());

        assertTrue(neoLink.formatClientInfoString().endsWith(";TU"));
    }

    @Test
    @DisplayName("TCP 和 UDP 开关在配置阶段决定握手标志")
    void transportSwitchesAreAppliedBeforeHandshake() throws Exception {
        NeoLinkCfg cfg = new NeoLinkCfg("top.ceroxe.example", 44801, 44802, "key", 25565)
                .setLanguage(NeoLinkCfg.EN_US)
                .setTCPEnabled(false)
                .setUDPEnabled(true);
        NeoLinkAPI neoLink = new NeoLinkAPI(cfg);

        var runtimeCfgField = declaredField(NeoLinkAPI.class, "runtimeCfg");
        runtimeCfgField.setAccessible(true);
        runtimeCfgField.set(neoLink, cfg.copy());

        assertTrue(neoLink.formatClientInfoString().startsWith("en;"));
        assertTrue(neoLink.formatClientInfoString().endsWith(";U"));
    }

    @Test
    @DisplayName("语言配置应接受常见别名并归一化为协议值")
    void languageAliasesAreNormalized() {
        NeoLinkCfg cfg = new NeoLinkCfg("top.ceroxe.example", 44801, 44802, "key", 25565);

        cfg.setLanguage("en-us");
        assertEquals(NeoLinkCfg.EN_US, cfg.getLanguage());

        cfg.setLanguage("zh-cn");
        assertEquals(NeoLinkCfg.ZH_CH, cfg.getLanguage());

        assertThrows(IllegalArgumentException.class, () -> cfg.setLanguage("fr-fr"));
    }
}
