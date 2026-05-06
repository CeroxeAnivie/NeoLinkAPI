package top.ceroxe.api.neolink;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@DisplayName("文档化 Java API 表面")
class DocumentedApiSurfaceTest {
    @Test
    @DisplayName("NeoLinkAPI 文档列出的入口、查询、回调和嵌套类型必须存在")
    void neoLinkApiSurfaceMatchesDocumentation() throws Exception {
        assertMethod(NeoLinkAPI.class, "version");
        assertMethod(NeoLinkAPI.class, "start");
        assertMethod(NeoLinkAPI.class, "start", int.class);
        assertMethod(NeoLinkAPI.class, "isActive");
        assertMethod(NeoLinkAPI.class, "getState");
        assertMethod(NeoLinkAPI.class, "close");
        assertMethod(NeoLinkAPI.class, "getTunAddr");
        assertMethod(NeoLinkAPI.class, "getHookSocket");
        assertMethod(NeoLinkAPI.class, "getUpdateURL");
        assertMethod(NeoLinkAPI.class, "isPPV2Enabled");
        assertMethod(NeoLinkAPI.class, "setPPV2Enabled", boolean.class);
        assertMethod(NeoLinkAPI.class, "setPPV2Enabled");
        assertMethod(NeoLinkAPI.class, "updateRuntimeProtocolFlags", boolean.class, boolean.class);
        assertMethod(NeoLinkAPI.class, "setOnStateChanged", Consumer.class);
        assertMethod(NeoLinkAPI.class, "setOnError", BiConsumer.class);
        assertMethod(NeoLinkAPI.class, "setOnServerMessage", Consumer.class);
        assertMethod(NeoLinkAPI.class, "setUnsupportedVersionDecision", Function.class);
        assertMethod(NeoLinkAPI.class, "setDebugSink", BiConsumer.class);
        assertMethod(NeoLinkAPI.class, "setOnConnect", NeoLinkAPI.ConnectionEventHandler.class);
        assertMethod(NeoLinkAPI.class, "setOnDisconnect", NeoLinkAPI.ConnectionEventHandler.class);
        assertMethod(NeoLinkAPI.class, "setOnConnectNeoFailure", Runnable.class);
        assertMethod(NeoLinkAPI.class, "setOnConnectLocalFailure", Runnable.class);

        assertEquals(SecureSocket.class, assertMethod(NeoLinkAPI.class, "getHookSocket").getReturnType());
        assertEquals(NeoLinkState.class, assertMethod(NeoLinkAPI.class, "getState").getReturnType());
        assertEquals(NeoLinkAPI.TransportProtocol.class, NeoLinkAPI.TransportProtocol.TCP.getClass());
        assertMethod(NeoLinkAPI.ConnectionEventHandler.class, "accept", NeoLinkAPI.TransportProtocol.class, InetSocketAddress.class, InetSocketAddress.class);
    }

    @Test
    @DisplayName("NeoLinkCfg 文档列出的常量、构造函数、getter 和 setter 必须存在")
    void neoLinkCfgSurfaceMatchesDocumentation() throws Exception {
        assertNotNull(NeoLinkCfg.EN_US);
        assertNotNull(NeoLinkCfg.ZH_CH);
        assertNotNull(NeoLinkCfg.DEFAULT_PROXY_IP);
        assertNotNull(NeoLinkCfg.DEFAULT_LOCAL_DOMAIN_NAME);
        assertEquals(1000, NeoLinkCfg.DEFAULT_HEARTBEAT_PACKET_DELAY);
        assertNotNull(NeoLinkCfg.class.getConstructor(String.class, int.class, int.class, String.class, int.class));

        for (String getter : new String[]{
                "getRemoteDomainName", "getHookPort", "getHostConnectPort", "getLocalDomainName", "getLocalPort",
                "getKey", "getProxyIPToLocalServer", "getProxyIPToNeoServer", "getHeartBeatPacketDelay",
                "isTCPEnabled", "isUDPEnabled", "isPPV2Enabled", "getLanguage", "getClientVersion", "isDebugMsg"
        }) {
            assertMethod(NeoLinkCfg.class, getter);
        }

        assertMethod(NeoLinkCfg.class, "setRemoteDomainName", String.class);
        assertMethod(NeoLinkCfg.class, "setHookPort", int.class);
        assertMethod(NeoLinkCfg.class, "setHostConnectPort", int.class);
        assertMethod(NeoLinkCfg.class, "setLocalDomainName", String.class);
        assertMethod(NeoLinkCfg.class, "setLocalPort", int.class);
        assertMethod(NeoLinkCfg.class, "setKey", String.class);
        assertMethod(NeoLinkCfg.class, "setProxyIPToLocalServer", String.class);
        assertMethod(NeoLinkCfg.class, "setProxyIPToLocalServer");
        assertMethod(NeoLinkCfg.class, "setProxyIPToNeoServer", String.class);
        assertMethod(NeoLinkCfg.class, "setProxyIPToNeoServer");
        assertMethod(NeoLinkCfg.class, "setHeartBeatPacketDelay", int.class);
        assertMethod(NeoLinkCfg.class, "setTCPEnabled", boolean.class);
        assertMethod(NeoLinkCfg.class, "setUDPEnabled", boolean.class);
        assertMethod(NeoLinkCfg.class, "setPPV2Enabled", boolean.class);
        assertMethod(NeoLinkCfg.class, "setPPV2Enabled");
        assertMethod(NeoLinkCfg.class, "setLanguage", String.class);
        assertMethod(NeoLinkCfg.class, "setClientVersion", String.class);
        assertMethod(NeoLinkCfg.class, "setDebugMsg", boolean.class);
        assertMethod(NeoLinkCfg.class, "setDebugMsg");
    }

    @Test
    @DisplayName("NeoNode 和 NodeFetcher 必须保留在 shared 可发布 API 中")
    void nodeSurfaceMatchesDocumentation() throws Exception {
        assertNotNull(NeoNode.class.getConstructor(String.class, String.class, String.class, String.class, int.class, int.class));
        assertMethod(NeoNode.class, "toCfg", String.class, int.class);
        assertMethod(NeoNode.class, "getName");
        assertMethod(NeoNode.class, "getRealId");
        assertMethod(NeoNode.class, "getAddress");
        assertMethod(NeoNode.class, "getIconSvg");
        assertMethod(NeoNode.class, "getHookPort");
        assertMethod(NeoNode.class, "getConnectPort");

        assertNotNull(NodeFetcher.class);
        assertEquals(1000, NodeFetcher.DEFAULT_TIMEOUT_MILLIS);
        assertEquals(44801, NodeFetcher.DEFAULT_HOST_HOOK_PORT);
        assertEquals(44802, NodeFetcher.DEFAULT_HOST_CONNECT_PORT);
        assertEquals(Map.class, assertMethod(NodeFetcher.class, "getFromNKM", String.class).getReturnType());
        assertEquals(Map.class, assertMethod(NodeFetcher.class, "getFromNKM", String.class, int.class).getReturnType());
    }

    @Test
    @DisplayName("文档列出的结构化异常必须暴露 serverResponse")
    void exceptionSurfaceMatchesDocumentation() throws Exception {
        for (Class<?> type : new Class<?>[]{
                UnsupportedVersionException.class,
                UnSupportHostVersionException.class,
                NoSuchKeyException.class,
                OutDatedKeyException.class,
                UnRecognizedKeyException.class,
                NoMoreNetworkFlowException.class,
                NoMorePortException.class,
                PortOccupiedException.class
        }) {
            assertEquals(String.class, assertMethod(type, "serverResponse").getReturnType(), type.getName());
        }
        assertEquals(IOException.class, NoMorePortException.class.getSuperclass());
        assertEquals(IOException.class, PortOccupiedException.class.getSuperclass());
    }

    private static Method assertMethod(Class<?> type, String name, Class<?>... parameterTypes) throws NoSuchMethodException {
        return type.getMethod(name, parameterTypes);
    }
}
