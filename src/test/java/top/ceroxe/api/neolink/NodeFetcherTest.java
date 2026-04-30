package top.ceroxe.api.neolink;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("NodeFetcher NKM 节点获取")
class NodeFetcherTest {
    @Test
    @DisplayName("getFromNKM 应按 realId 返回 NeoLinkCfg")
    void getFromNkmReturnsConfigsKeyedByRealId() throws Exception {
        String payload = """
                [
                  {
                    "realId": "node-suqian",
                    "name": "中国 - 宿迁官方",
                    "address": "p.ceroxe.top",
                    "HOST_HOOK_PORT": 44801,
                    "HOST_CONNECT_PORT": "44802"
                  }
                ]
                """;

        try (TestHttpServer server = TestHttpServer.responding(200, payload)) {
            Map<String, NeoLinkCfg> nodes = NodeFetcher.getFromNKM(server.url(), 1000);

            assertEquals(1, nodes.size());
            NeoLinkCfg cfg = nodes.get("node-suqian");
            assertNotNull(cfg);
            assertEquals("p.ceroxe.top", cfg.getRemoteDomainName());
            assertEquals(44801, cfg.getHookPort());
            assertEquals(44802, cfg.getHostConnectPort());
        }
    }

    @Test
    @DisplayName("端口缺省时应使用桌面客户端一致的默认值")
    void missingPortsUseDesktopDefaults() throws Exception {
        Map<String, NeoLinkCfg> nodes = NodeFetcher.parseNodeMap("""
                [{"realId":"node-default","address":"nps.example.com"}]
                """);

        NeoLinkCfg cfg = nodes.get("node-default");
        assertEquals(NodeFetcher.DEFAULT_HOST_HOOK_PORT, cfg.getHookPort());
        assertEquals(NodeFetcher.DEFAULT_HOST_CONNECT_PORT, cfg.getHostConnectPort());
    }

    @Test
    @DisplayName("realId 重复必须失败，避免静默覆盖节点配置")
    void duplicateRealIdFails() {
        IOException error = assertThrows(IOException.class, () -> NodeFetcher.parseNodeMap("""
                [
                  {"realId":"node-1","address":"a.example.com"},
                  {"realId":"node-1","address":"b.example.com"}
                ]
                """));

        assertTrue(error.getMessage().contains("duplicate realId"));
    }

    @Test
    @DisplayName("非法 JSON 或非法节点结构必须失败")
    void invalidJsonOrSchemaFails() {
        assertThrows(IOException.class, () -> NodeFetcher.parseNodeMap("{not-json"));
        assertThrows(IOException.class, () -> NodeFetcher.parseNodeMap("{}"));
        assertThrows(IOException.class, () -> NodeFetcher.parseNodeMap("[{}]"));
        assertThrows(IOException.class, () -> NodeFetcher.parseNodeMap("""
                [{"realId":"node-1","address":"nps.example.com","HOST_HOOK_PORT":0}]
                """));
        assertThrows(IOException.class, () -> NodeFetcher.parseNodeMap("""
                [{"realId":"node-1","address":"nps.example.com","HOST_HOOK_PORT":44801.5}]
                """));
    }

    @Test
    @DisplayName("HTTP 非 200 必须暴露为 IOException")
    void nonOkHttpStatusFails() throws Exception {
        try (TestHttpServer server = TestHttpServer.responding(503, "busy")) {
            IOException error = assertThrows(IOException.class, () -> NodeFetcher.getFromNKM(server.url(), 1000));

            assertTrue(error.getMessage().contains("HTTP status 503"));
        }
    }

    @Test
    @DisplayName("从 NKM 得到的配置必须补齐 key 和 localPort 后才能启动")
    void nkmConfigRequiresCallerOwnedFieldsBeforeStart() throws Exception {
        Map<String, NeoLinkCfg> nodes = NodeFetcher.parseNodeMap("""
                [{"realId":"node-1","address":"localhost"}]
                """);
        NeoLinkCfg cfg = nodes.get("node-1");

        assertThrows(IllegalStateException.class, () -> new NeoLinkAPI(cfg).start(1));

        cfg.setKey("key").setLocalPort(25565);
        assertThrows(IOException.class, () -> new NeoLinkAPI(cfg).start(1));
    }

    @Test
    @DisplayName("URL 和超时参数必须在请求前校验")
    void inputValidationHappensBeforeRequest() {
        assertThrows(IllegalArgumentException.class, () -> NodeFetcher.getFromNKM(" ", 1000));
        assertThrows(IllegalArgumentException.class, () -> NodeFetcher.getFromNKM("file:///nodes.json", 1000));
        assertThrows(IllegalArgumentException.class, () -> NodeFetcher.getFromNKM("https://example.com/nodes", 0));
    }

    private static final class TestHttpServer implements AutoCloseable {
        private final HttpServer server;
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        private TestHttpServer(HttpServer server) {
            this.server = server;
        }

        private static TestHttpServer responding(int statusCode, String body) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            TestHttpServer holder = new TestHttpServer(server);
            server.createContext("/nodes", exchange -> {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                try (exchange) {
                    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                    exchange.sendResponseHeaders(statusCode, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } catch (Throwable e) {
                    holder.failure.compareAndSet(null, e);
                    throw e;
                }
            });
            server.start();
            return holder;
        }

        private String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/nodes";
        }

        @Override
        public void close() {
            server.stop(0);
            if (failure.get() != null) {
                fail("Test HTTP server failed", failure.get());
            }
        }
    }
}
