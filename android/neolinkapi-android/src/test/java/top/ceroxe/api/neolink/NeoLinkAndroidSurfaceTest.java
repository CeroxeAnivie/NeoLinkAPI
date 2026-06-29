package top.ceroxe.api.neolink;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class NeoLinkAndroidSurfaceTest {
    @Test
    public void versionAndConfigDefaultsStayStable() {
        NeoLinkCfg cfg = new NeoLinkCfg("top.ceroxe.example", 44801, 44802, "key", 25565);

        assertNotNull(NeoLinkAPI.version());
        assertTrue(!NeoLinkAPI.version().isBlank());
        assertEquals("localhost", cfg.getLocalDomainName());
        assertEquals("", cfg.getProxyIPToLocalServer());
        assertEquals("", cfg.getProxyIPToNeoServer());
        assertEquals(1000, cfg.getHeartBeatPacketDelay());
        assertTrue(cfg.isTCPEnabled());
        assertTrue(cfg.isUDPEnabled());
        assertFalse(cfg.isPPV2Enabled());
        assertFalse(cfg.isDebugMsg());
    }

    @Test
    public void tunnelAddressParsingSupportsEnglishAndChineseMessages() {
        assertEquals(
                "p.ceroxe.fun:45678",
                NeoLinkAPI.parseTunAddrMessage("Use the address: p.ceroxe.fun:45678 to start up connections.")
        );
        assertEquals(
                "p.ceroxe.top:45678",
                NeoLinkAPI.parseTunAddrMessage("使用链接地址： p.ceroxe.top:45678 来从公网连接。")
        );
        assertNull(NeoLinkAPI.parseTunAddrMessage("traffic warning from server"));
    }

    @Test
    public void bindAndroidLogReturnsSameInstanceAndRejectsBlankTag() {
        NeoLinkAPI api = new NeoLinkAPI(new NeoLinkCfg("top.ceroxe.example", 44801, 44802, "key", 25565));

        assertSame(api, api.bindAndroidLog());
        assertSame(api, api.bindAndroidLog("NeoLink-Android"));
        assertThrows(IllegalArgumentException.class, () -> api.bindAndroidLog(" "));
    }
}
