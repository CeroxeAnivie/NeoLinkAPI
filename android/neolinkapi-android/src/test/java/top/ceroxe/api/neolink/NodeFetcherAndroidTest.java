package top.ceroxe.api.neolink;

import org.junit.Test;

import java.io.IOException;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class NodeFetcherAndroidTest {
    @Test
    public void parseNodeMapReturnsNodesKeyedByRealId() throws Exception {
        Map<String, NeoNode> nodes = NodeFetcher.parseNodeMap("""
                [
                  {
                    "realId": "node-suqian",
                    "name": "中国 - 宿迁官方",
                    "address": "p.ceroxe.top",
                    "icon": "<svg viewBox='0 0 1 1'></svg>",
                    "HOST_HOOK_PORT": 44801,
                    "HOST_CONNECT_PORT": "44802"
                  }
                ]
                """);

        assertEquals(1, nodes.size());
        NeoNode node = nodes.get("node-suqian");
        assertNotNull(node);
        assertEquals("中国 - 宿迁官方", node.getName());
        assertEquals("node-suqian", node.getRealId());
        assertEquals("p.ceroxe.top", node.getAddress());
        assertEquals("<svg viewBox='0 0 1 1'></svg>", node.getIconSvg());
        assertEquals(44801, node.getHookPort());
        assertEquals(44802, node.getConnectPort());
    }

    @Test
    public void parseNodeMapFallsBackToDefaultPorts() throws Exception {
        Map<String, NeoNode> nodes = NodeFetcher.parseNodeMap("""
                [{"realId":"node-default","name":"Default Node","address":"nps.example.com"}]
                """);

        NeoNode node = nodes.get("node-default");
        assertNotNull(node);
        assertNull(node.getIconSvg());
        assertEquals(NodeFetcher.DEFAULT_HOST_HOOK_PORT, node.getHookPort());
        assertEquals(NodeFetcher.DEFAULT_HOST_CONNECT_PORT, node.getConnectPort());
    }

    @Test
    public void parseNodeMapRejectsInvalidPayloads() {
        IOException duplicateError = assertThrows(IOException.class, () -> NodeFetcher.parseNodeMap("""
                [
                  {"realId":"node-1","name":"Node A","address":"a.example.com"},
                  {"realId":"node-1","name":"Node B","address":"b.example.com"}
                ]
                """));
        assertTrue(duplicateError.getMessage().contains("duplicate realId"));

        assertThrows(IOException.class, () -> NodeFetcher.parseNodeMap("{not-json"));
        assertThrows(IOException.class, () -> NodeFetcher.parseNodeMap("{}"));
        assertThrows(IOException.class, () -> NodeFetcher.parseNodeMap("[{}]"));
        assertThrows(IOException.class, () -> NodeFetcher.parseNodeMap("""
                [{"realId":"node-1","name":"Node","address":"nps.example.com","HOST_HOOK_PORT":0}]
                """));
    }

    @Test
    public void getFromNkmValidatesInputBeforeRequest() {
        assertThrows(IllegalArgumentException.class, () -> NodeFetcher.getFromNKM(" ", 1000));
        assertThrows(IllegalArgumentException.class, () -> NodeFetcher.getFromNKM("file:///nodes.json", 1000));
        assertThrows(IllegalArgumentException.class, () -> NodeFetcher.getFromNKM("https://example.com/nodes", 0));
    }
}
