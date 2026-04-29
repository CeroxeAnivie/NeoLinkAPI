package top.ceroxe.api.neolink;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("NeoLinkCfg 公开 API 配置")
class NeoLinkCfgTest {
    @Test
    @DisplayName("构造函数强制要求远程域名、Hook 端口、连接端口、密钥和本地端口")
    void constructorRequiresAllMandatoryValues() {
        assertThrows(IllegalArgumentException.class, () -> new NeoLinkCfg("", 44801, 44802, "key", 25565));
        assertThrows(IllegalArgumentException.class, () -> new NeoLinkCfg("p.ceroxe.fun", 0, 44802, "key", 25565));
        assertThrows(IllegalArgumentException.class, () -> new NeoLinkCfg("p.ceroxe.fun", 44801, 0, "key", 25565));
        assertThrows(IllegalArgumentException.class, () -> new NeoLinkCfg("p.ceroxe.fun", 44801, 44802, "", 25565));
        assertThrows(IllegalArgumentException.class, () -> new NeoLinkCfg("p.ceroxe.fun", 44801, 44802, "key", 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new NeoLinkCfg("p.ceroxe.fun", 44801, 44802, "key", 25565).setClientVersion(" ")
        );
    }

    @Test
    @DisplayName("默认值与 config.cfg 的 API 语义保持一致")
    void defaultsMatchDocumentedApiSemantics() {
        NeoLinkCfg cfg = new NeoLinkCfg("p.ceroxe.fun", 44801, 44802, "key", 25565);

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
    }

    @Test
    @DisplayName("setter 修改后的值与 getter 保持一致")
    void settersStaySynchronizedWithGetters() {
        NeoLinkCfg cfg = new NeoLinkCfg("p.ceroxe.fun", 44801, 44802, "key", 25565)
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
        NeoLinkAPI neoLink = new NeoLinkAPI(new NeoLinkCfg("p.ceroxe.fun", 44801, 44802, "key", 25565));

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
        assertSame(neoLink, neoLink.setOnRemotePortChanged(port -> {
        }));
        assertSame(neoLink, neoLink.setUnsupportedVersionDecision(response -> false));
        assertSame(neoLink, neoLink.setDebugSink((message, cause) -> {
        }));
    }

    @Test
    @DisplayName("TCP 和 UDP 默认都开启，握手标志应为 TU")
    void tcpAndUdpAreEnabledByDefault() throws Exception {
        NeoLinkCfg cfg = new NeoLinkCfg("p.ceroxe.fun", 44801, 44802, "key", 25565);
        NeoLinkAPI neoLink = new NeoLinkAPI(cfg);

        var runtimeCfgField = NeoLinkAPI.class.getDeclaredField("runtimeCfg");
        runtimeCfgField.setAccessible(true);
        runtimeCfgField.set(neoLink, cfg.copy());

        assertTrue(neoLink.formatClientInfoString().endsWith(";TU"));
    }

    @Test
    @DisplayName("TCP 和 UDP 开关在配置阶段决定握手标志")
    void transportSwitchesAreAppliedBeforeHandshake() throws Exception {
        NeoLinkCfg cfg = new NeoLinkCfg("p.ceroxe.fun", 44801, 44802, "key", 25565)
                .setLanguage(NeoLinkCfg.EN_US)
                .setTCPEnabled(false)
                .setUDPEnabled(true);
        NeoLinkAPI neoLink = new NeoLinkAPI(cfg);

        var runtimeCfgField = NeoLinkAPI.class.getDeclaredField("runtimeCfg");
        runtimeCfgField.setAccessible(true);
        runtimeCfgField.set(neoLink, cfg.copy());

        assertTrue(neoLink.formatClientInfoString().startsWith("en;"));
        assertTrue(neoLink.formatClientInfoString().endsWith(";U"));
    }

    @Test
    @DisplayName("语言配置应接受常见别名并归一化为协议值")
    void languageAliasesAreNormalized() {
        NeoLinkCfg cfg = new NeoLinkCfg("p.ceroxe.fun", 44801, 44802, "key", 25565);

        cfg.setLanguage("en-us");
        assertEquals(NeoLinkCfg.EN_US, cfg.getLanguage());

        cfg.setLanguage("zh-cn");
        assertEquals(NeoLinkCfg.ZH_CH, cfg.getLanguage());

        assertThrows(IllegalArgumentException.class, () -> cfg.setLanguage("fr-fr"));
    }
}
