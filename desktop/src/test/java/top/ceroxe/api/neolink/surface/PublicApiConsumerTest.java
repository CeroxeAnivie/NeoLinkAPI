package top.ceroxe.api.neolink.surface;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.ceroxe.api.neolink.NeoLinkAPI;
import top.ceroxe.api.neolink.NeoLinkCfg;
import top.ceroxe.api.neolink.NeoLinkState;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Public API consumer surface")
class PublicApiConsumerTest {
    @Test
    @DisplayName("package-external callers can use the inherited runtime API")
    void packageExternalCallersCanUseInheritedRuntimeApi() {
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
        assertSame(api, api.setUnsupportedVersionDecision(response -> false));
        assertSame(api, api.setDebugSink((message, cause) -> {
        }));
        api.close();
    }
}
