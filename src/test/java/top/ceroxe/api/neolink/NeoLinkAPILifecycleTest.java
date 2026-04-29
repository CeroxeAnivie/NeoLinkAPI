package top.ceroxe.api.neolink;

import fun.ceroxe.api.net.SecureServerSocket;
import fun.ceroxe.api.net.SecureSocket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("NeoLinkAPI lifecycle")
class NeoLinkAPILifecycleTest {
    @Test
    @DisplayName("close 后同一个 NeoLinkAPI 实例应允许重新 start")
    void closeAllowsRestartingSameInstance() throws Exception {
        CountDownLatch handshakes = new CountDownLatch(2);
        AtomicReference<Throwable> serverError = new AtomicReference<>();

        try (SecureServerSocket server = new SecureServerSocket(0)) {
            Thread serverThread = Thread.ofVirtual().start(() -> {
                for (int i = 0; i < 2; i++) {
                    try (SecureSocket socket = server.accept()) {
                        String handshake = socket.receiveStr(2000);
                        assertNotNull(handshake);
                        socket.sendStr("Connection built successfully");
                        handshakes.countDown();
                        Thread.sleep(200);
                    } catch (Throwable e) {
                        serverError.compareAndSet(null, e);
                        return;
                    }
                }
            });

            NeoLinkCfg cfg = new NeoLinkCfg("localhost", server.getLocalPort(), server.getLocalPort(), "key", 25565)
                    .setTCPEnabled(false)
                    .setUDPEnabled(false);
            NeoLinkAPI neoLink = new NeoLinkAPI(cfg);

            neoLink.start();
            assertTrue(neoLink.isActive());
            neoLink.close();
            assertFalse(neoLink.isActive());

            neoLink.start();
            assertTrue(neoLink.isActive());
            neoLink.close();
            assertFalse(neoLink.isActive());

            assertTrue(handshakes.await(3, TimeUnit.SECONDS));
            serverThread.join(3000);
            if (serverError.get() != null) {
                fail("Lifecycle test server failed", serverError.get());
            }
        }
    }
}
