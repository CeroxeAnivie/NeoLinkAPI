package top.ceroxe.api.neolink;

import fun.ceroxe.api.net.SecureServerSocket;
import fun.ceroxe.api.net.SecureSocket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.ceroxe.api.neolink.exception.UnsupportedVersionException;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CopyOnWriteArrayList;

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

    @Test
    @DisplayName("生命周期、端口和服务端消息应通过稳定回调暴露")
    void lifecyclePortAndServerMessagesAreObservable() throws Exception {
        CountDownLatch remotePortUpdated = new CountDownLatch(1);
        CountDownLatch serverMessageReceived = new CountDownLatch(1);
        AtomicReference<Throwable> serverError = new AtomicReference<>();
        AtomicReference<String> serverMessage = new AtomicReference<>();
        AtomicReference<Integer> remotePort = new AtomicReference<>();
        List<NeoLinkState> states = new CopyOnWriteArrayList<>();

        try (SecureServerSocket server = new SecureServerSocket(0)) {
            Thread serverThread = Thread.ofVirtual().start(() -> {
                try (SecureSocket socket = server.accept()) {
                    assertNotNull(socket.receiveStr(2000));
                    socket.sendStr("Connection built successfully");
                    socket.sendStr(":>45678");
                    socket.sendStr("traffic warning from server");
                    Thread.sleep(2000);
                } catch (Throwable e) {
                    serverError.compareAndSet(null, e);
                }
            });

            NeoLinkCfg cfg = new NeoLinkCfg("localhost", server.getLocalPort(), server.getLocalPort(), "key", 25565)
                    .setTCPEnabled(false)
                    .setUDPEnabled(false);
            NeoLinkAPI neoLink = new NeoLinkAPI(cfg)
                    .setOnStateChanged(states::add)
                    .setOnRemotePortChanged(port -> {
                        remotePort.set(port);
                        remotePortUpdated.countDown();
                    })
                    .setOnServerMessage(message -> {
                        serverMessage.set(message);
                        serverMessageReceived.countDown();
                    });

            neoLink.start();
            assertTrue(remotePortUpdated.await(3, TimeUnit.SECONDS));
            assertTrue(serverMessageReceived.await(3, TimeUnit.SECONDS));

            assertEquals(45678, remotePort.get());
            assertEquals(45678, neoLink.getRemotePort());
            assertEquals("traffic warning from server", serverMessage.get());
            assertTrue(states.contains(NeoLinkState.STARTING));
            assertTrue(states.contains(NeoLinkState.RUNNING));

            neoLink.close();
            serverThread.join(3000);
            assertFalse(neoLink.isActive());
            assertEquals(NeoLinkState.STOPPED, neoLink.getState());
            assertTrue(states.contains(NeoLinkState.STOPPING));
            assertTrue(states.contains(NeoLinkState.STOPPED));
            if (serverError.get() != null) {
                fail("Lifecycle test server failed", serverError.get());
            }
        }
    }

    @Test
    @DisplayName("运行期断线应走错误回调并进入 FAILED 后清理为 STOPPED")
    void runtimeDisconnectEmitsErrorAndFailsBeforeStopped() throws Exception {
        CountDownLatch errorReceived = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        AtomicReference<Throwable> serverError = new AtomicReference<>();
        AtomicReference<String> errorMessage = new AtomicReference<>();
        List<NeoLinkState> states = new CopyOnWriteArrayList<>();

        try (SecureServerSocket server = new SecureServerSocket(0)) {
            Thread serverThread = Thread.ofVirtual().start(() -> {
                try (SecureSocket socket = server.accept()) {
                    assertNotNull(socket.receiveStr(2000));
                    socket.sendStr("Connection built successfully");
                } catch (Throwable e) {
                    serverError.compareAndSet(null, e);
                }
            });

            NeoLinkCfg cfg = new NeoLinkCfg("localhost", server.getLocalPort(), server.getLocalPort(), "key", 25565)
                    .setTCPEnabled(false)
                    .setUDPEnabled(false);
            NeoLinkAPI neoLink = new NeoLinkAPI(cfg)
                    .setOnStateChanged(state -> {
                        states.add(state);
                        if (state == NeoLinkState.STOPPED) {
                            stopped.countDown();
                        }
                    })
                    .setOnError((message, cause) -> {
                        errorMessage.set(message);
                        errorReceived.countDown();
                    });

            neoLink.start();

            assertTrue(errorReceived.await(3, TimeUnit.SECONDS));
            assertTrue(stopped.await(3, TimeUnit.SECONDS));
            assertEquals("NeoLinkAPI 隧道异常停止。", errorMessage.get());
            assertTrue(states.contains(NeoLinkState.FAILED));
            assertEquals(NeoLinkState.STOPPED, neoLink.getState());

            serverThread.join(3000);
            if (serverError.get() != null) {
                fail("Lifecycle test server failed", serverError.get());
            }
        }
    }

    @Test
    @DisplayName("不支持版本时调用方可决定是否声明自行更新")
    void unsupportedVersionDecisionControlsHandshakeReply() throws Exception {
        CountDownLatch replyReceived = new CountDownLatch(1);
        AtomicReference<Throwable> serverError = new AtomicReference<>();
        AtomicReference<String> handshake = new AtomicReference<>();
        AtomicReference<String> updateReply = new AtomicReference<>();
        AtomicReference<String> decisionInput = new AtomicReference<>();

        try (SecureServerSocket server = new SecureServerSocket(0)) {
            Thread serverThread = Thread.ofVirtual().start(() -> {
                try (SecureSocket socket = server.accept()) {
                    handshake.set(socket.receiveStr(2000));
                    String response = "Unsupported version:6.0.1|6.0.2";
                    socket.sendStr(response);
                    updateReply.set(socket.receiveStr(2000));
                    replyReceived.countDown();
                } catch (Throwable e) {
                    serverError.compareAndSet(null, e);
                }
            });

            NeoLinkCfg cfg = new NeoLinkCfg("localhost", server.getLocalPort(), server.getLocalPort(), "key", 25565)
                    .setLanguage(NeoLinkCfg.EN_US)
                    .setClientVersion("0.0.1-test")
                    .setTCPEnabled(false)
                    .setUDPEnabled(false);
            NeoLinkAPI neoLink = new NeoLinkAPI(cfg)
                    .setUnsupportedVersionDecision(response -> {
                        decisionInput.set(response);
                        return true;
                    });

            assertThrows(UnsupportedVersionException.class, neoLink::start);

            assertTrue(replyReceived.await(3, TimeUnit.SECONDS));
            assertEquals("en;0.0.1-test;key;", handshake.get());
            assertEquals("Unsupported version:6.0.1|6.0.2", decisionInput.get());
            assertEquals("true", updateReply.get());
            serverThread.join(3000);
            if (serverError.get() != null) {
                fail("Lifecycle test server failed", serverError.get());
            }
        }
    }

    @Test
    @DisplayName("调用方回调异常必须被隔离并进入 debug sink")
    void callbackFailuresAreIsolatedAndReportedToDebugSink() throws Exception {
        CountDownLatch debugExceptionReceived = new CountDownLatch(1);
        AtomicReference<Throwable> serverError = new AtomicReference<>();
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();

        try (SecureServerSocket server = new SecureServerSocket(0)) {
            Thread serverThread = Thread.ofVirtual().start(() -> {
                try (SecureSocket socket = server.accept()) {
                    assertNotNull(socket.receiveStr(2000));
                    socket.sendStr("Connection built successfully");
                    Thread.sleep(1000);
                } catch (Throwable e) {
                    serverError.compareAndSet(null, e);
                }
            });

            NeoLinkCfg cfg = new NeoLinkCfg("localhost", server.getLocalPort(), server.getLocalPort(), "key", 25565)
                    .setTCPEnabled(false)
                    .setUDPEnabled(false)
                    .setDebugMsg(true);
            NeoLinkAPI neoLink = new NeoLinkAPI(cfg)
                    .setOnStateChanged(state -> {
                        throw new IllegalStateException("state callback failed");
                    })
                    .setDebugSink((message, cause) -> {
                        if (cause != null) {
                            callbackFailure.set(cause);
                            debugExceptionReceived.countDown();
                        }
                    });

            assertDoesNotThrow(neoLink::start);
            assertTrue(debugExceptionReceived.await(3, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, callbackFailure.get());

            neoLink.close();
            serverThread.join(3000);
            if (serverError.get() != null) {
                fail("Lifecycle test server failed", serverError.get());
            }
        }
    }
}
