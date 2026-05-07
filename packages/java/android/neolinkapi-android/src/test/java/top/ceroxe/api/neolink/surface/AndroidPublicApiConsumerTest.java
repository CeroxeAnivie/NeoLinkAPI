package top.ceroxe.api.neolink.surface;

import org.junit.Test;
import top.ceroxe.api.neolink.NeoLinkAPI;
import top.ceroxe.api.neolink.NeoLinkCfg;
import top.ceroxe.api.neolink.NeoLinkState;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class AndroidPublicApiConsumerTest {
    @Test
    public void packageExternalCallersCanUseTheInheritedRuntimeApi() {
        NeoLinkAPI api = new NeoLinkAPI(new NeoLinkCfg("top.ceroxe.example", 44801, 44802, "key", 25565));

        assertFalse(api.isActive());
        assertSame(NeoLinkState.STOPPED, api.getState());
        assertFalse(api.isPPV2Enabled());
        assertSame(api, api.setPPV2Enabled());
        assertTrue(api.isPPV2Enabled());
        assertSame(api, api.setPPV2Enabled(false));
        assertFalse(api.isPPV2Enabled());
        assertSame(api, api.setOnStateChanged(state -> {
        }));
        assertSame(api, api.setOnError((message, cause) -> {
        }));
        assertSame(api, api.setOnServerMessage(message -> {
        }));
        assertSame(api, api.setOnConnect((protocol, source, target) -> {
        }));
        assertSame(api, api.setOnDisconnect((protocol, source, target) -> {
        }));
        assertSame(api, api.setOnTraffic((protocol, direction, bytes) -> {
        }));
        assertSame(api, api.setUnsupportedVersionDecision(response -> false));
        assertSame(api, api.setDebugSink((message, cause) -> {
        }));
        assertSame(api, api.bindAndroidLog("NeoLinkAPI"));
        api.close();
    }
}
