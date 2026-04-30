package top.ceroxe.api.neolink;

import top.ceroxe.api.OshiUtils;
import top.ceroxe.api.net.SecureServerSocket;
import top.ceroxe.api.net.SecureSocket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.ceroxe.api.neolink.exception.UnsupportedVersionException;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
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
                        Thread.sleep(1000);
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

            CompletableFuture<Void> firstStart = startAsync(neoLink);
            awaitRunning(neoLink);
            assertTrue(neoLink.isActive());
            assertFalse(firstStart.isDone(), "start() must block while the tunnel is active.");
            neoLink.close();
            assertStartCompleted(firstStart);
            assertFalse(neoLink.isActive());

            CompletableFuture<Void> secondStart = startAsync(neoLink);
            awaitRunning(neoLink);
            assertTrue(neoLink.isActive());
            assertFalse(secondStart.isDone(), "start() must block while the restarted tunnel is active.");
            neoLink.close();
            assertStartCompleted(secondStart);
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

            CompletableFuture<Void> startFuture = startAsync(neoLink);
            assertTrue(remotePortUpdated.await(3, TimeUnit.SECONDS));
            assertTrue(serverMessageReceived.await(3, TimeUnit.SECONDS));
            assertFalse(startFuture.isDone(), "start() must keep blocking after startup succeeds.");

            assertNotNull(neoLink.getHookSocket());
            assertTrue(neoLink.getHookSocket().isConnected());
            assertNull(neoLink.getUpdateURL());
            assertEquals(45678, remotePort.get());
            assertEquals(45678, neoLink.getRemotePort());
            assertEquals("traffic warning from server", serverMessage.get());
            assertTrue(states.contains(NeoLinkState.STARTING));
            assertTrue(states.contains(NeoLinkState.RUNNING));

            neoLink.close();
            assertStartCompleted(startFuture);
            serverThread.join(3000);
            assertFalse(neoLink.isActive());
            assertEquals(NeoLinkState.STOPPED, neoLink.getState());
            assertTrue(states.contains(NeoLinkState.STOPPING));
            assertTrue(states.contains(NeoLinkState.STOPPED));
            assertNull(neoLink.getHookSocket());
            if (serverError.get() != null) {
                fail("Lifecycle test server failed", serverError.get());
            }
        }
    }

    @Test
    @DisplayName("start 重载应只覆盖连接 NPS 的超时时间")
    void startOverloadAppliesCustomNpsConnectTimeout() throws Exception {
        CountDownLatch timeoutObserved = new CountDownLatch(1);
        AtomicReference<Throwable> serverError = new AtomicReference<>();

        try (SecureServerSocket server = new SecureServerSocket(0)) {
            Thread serverThread = Thread.ofVirtual().start(() -> {
                try (SecureSocket socket = server.accept()) {
                    assertNotNull(socket.receiveStr(2000));
                    socket.sendStr("Connection built successfully");
                    Thread.sleep(2000);
                } catch (Throwable e) {
                    serverError.compareAndSet(null, e);
                }
            });

            NeoLinkCfg cfg = new NeoLinkCfg("localhost", server.getLocalPort(), server.getLocalPort(), "key", 25565)
                    .setTCPEnabled(false)
                    .setUDPEnabled(false)
                    .setDebugMsg(true);
            NeoLinkAPI neoLink = new NeoLinkAPI(cfg)
                    .setDebugSink((message, cause) -> {
                        if (message != null && message.contains("connectToNpsTimeoutMs=1234")) {
                            timeoutObserved.countDown();
                        }
                    });

            CompletableFuture<Void> startFuture = startAsync(neoLink, 1234);
            awaitRunning(neoLink);
            assertTrue(timeoutObserved.await(3, TimeUnit.SECONDS));
            assertFalse(startFuture.isDone(), "start(int) must block while the tunnel is active.");

            neoLink.close();
            assertStartCompleted(startFuture);
            serverThread.join(3000);
            if (serverError.get() != null) {
                fail("Lifecycle test server failed", serverError.get());
            }
        }
    }

    @Test
    @DisplayName("非法代理配置不应污染生命周期状态")
    void invalidProxyConfigurationDoesNotLeaveStartingState() {
        NeoLinkCfg cfg = new NeoLinkCfg("localhost", 1, 1, "key", 25565)
                .setProxyIPToNeoServer("bad-proxy-format");
        NeoLinkAPI neoLink = new NeoLinkAPI(cfg);

        assertThrows(IllegalArgumentException.class, () -> neoLink.start(1));
        assertFalse(neoLink.isActive());
        assertEquals(NeoLinkState.STOPPED, neoLink.getState());
    }

    @Test
    @DisplayName("TCP 服务端指令应创建传输连接并触发连接回调")
    void tcpServerCommandCreatesTransferConnection() throws Exception {
        CountDownLatch transferHandshakeReceived = new CountDownLatch(1);
        CountDownLatch localAccepted = new CountDownLatch(1);
        CountDownLatch connectCallbackReceived = new CountDownLatch(1);
        CountDownLatch disconnectCallbackReceived = new CountDownLatch(1);
        AtomicReference<Throwable> serverError = new AtomicReference<>();
        AtomicReference<String> transferHandshake = new AtomicReference<>();
        AtomicReference<NeoLinkAPI.TransportProtocol> protocol = new AtomicReference<>();
        AtomicReference<NeoLinkAPI.TransportProtocol> disconnectedProtocol = new AtomicReference<>();

        try (SecureServerSocket hookServer = new SecureServerSocket(0);
             SecureServerSocket transferServer = new SecureServerSocket(0);
             ServerSocket localServer = new ServerSocket(0)) {
            Thread hookThread = Thread.ofVirtual().start(() -> {
                try (SecureSocket socket = hookServer.accept()) {
                    assertNotNull(socket.receiveStr(2000));
                    socket.sendStr("Connection built successfully");
                    socket.sendStr(":>sendSocketTCP;socket-tcp;203.0.113.9:4567");
                    Thread.sleep(3000);
                } catch (Throwable e) {
                    serverError.compareAndSet(null, e);
                }
            });
            Thread transferThread = Thread.ofVirtual().start(() -> {
                try (SecureSocket socket = transferServer.accept()) {
                    transferHandshake.set(socket.receiveStr(3000));
                    transferHandshakeReceived.countDown();
                } catch (Throwable e) {
                    serverError.compareAndSet(null, e);
                }
            });
            Thread localThread = Thread.ofVirtual().start(() -> {
                try (Socket socket = localServer.accept()) {
                    localAccepted.countDown();
                } catch (Throwable e) {
                    serverError.compareAndSet(null, e);
                }
            });

            NeoLinkCfg cfg = new NeoLinkCfg(
                    "localhost",
                    hookServer.getLocalPort(),
                    transferServer.getLocalPort(),
                    "key",
                    localServer.getLocalPort()
            ).setUDPEnabled(false);
            NeoLinkAPI neoLink = new NeoLinkAPI(cfg)
                    .setOnConnect((transportProtocol, source, target) -> {
                        protocol.set(transportProtocol);
                        connectCallbackReceived.countDown();
                    })
                    .setOnDisconnect((transportProtocol, source, target) -> {
                        disconnectedProtocol.set(transportProtocol);
                        disconnectCallbackReceived.countDown();
                    });

            CompletableFuture<Void> startFuture = startAsync(neoLink);
            assertTrue(transferHandshakeReceived.await(5, TimeUnit.SECONDS));
            assertTrue(localAccepted.await(5, TimeUnit.SECONDS));
            assertTrue(connectCallbackReceived.await(5, TimeUnit.SECONDS));
            assertTrue(disconnectCallbackReceived.await(5, TimeUnit.SECONDS));
            assertEquals("TCP;socket-tcp", transferHandshake.get());
            assertEquals(NeoLinkAPI.TransportProtocol.TCP, protocol.get());
            assertEquals(NeoLinkAPI.TransportProtocol.TCP, disconnectedProtocol.get());

            neoLink.close();
            assertStartCompleted(startFuture);
            hookThread.join(3000);
            transferThread.join(3000);
            localThread.join(3000);
            if (serverError.get() != null) {
                fail("TCP dispatch test server failed", serverError.get());
            }
        }
    }

    @Test
    @DisplayName("UDP 服务端指令应创建传输连接并触发连接回调")
    void udpServerCommandCreatesTransferConnection() throws Exception {
        CountDownLatch transferHandshakeReceived = new CountDownLatch(1);
        CountDownLatch connectCallbackReceived = new CountDownLatch(1);
        AtomicReference<Throwable> serverError = new AtomicReference<>();
        AtomicReference<String> transferHandshake = new AtomicReference<>();
        AtomicReference<NeoLinkAPI.TransportProtocol> protocol = new AtomicReference<>();

        try (SecureServerSocket hookServer = new SecureServerSocket(0);
             SecureServerSocket transferServer = new SecureServerSocket(0)) {
            Thread hookThread = Thread.ofVirtual().start(() -> {
                try (SecureSocket socket = hookServer.accept()) {
                    assertNotNull(socket.receiveStr(2000));
                    socket.sendStr("Connection built successfully");
                    socket.sendStr(":>sendSocketUDP;socket-udp;203.0.113.10:4568");
                    Thread.sleep(3000);
                } catch (Throwable e) {
                    serverError.compareAndSet(null, e);
                }
            });
            Thread transferThread = Thread.ofVirtual().start(() -> {
                try (SecureSocket socket = transferServer.accept()) {
                    transferHandshake.set(socket.receiveStr(3000));
                    transferHandshakeReceived.countDown();
                    Thread.sleep(3000);
                } catch (Throwable e) {
                    serverError.compareAndSet(null, e);
                }
            });

            NeoLinkCfg cfg = new NeoLinkCfg(
                    "localhost",
                    hookServer.getLocalPort(),
                    transferServer.getLocalPort(),
                    "key",
                    25565
            ).setTCPEnabled(false);
            NeoLinkAPI neoLink = new NeoLinkAPI(cfg)
                    .setOnConnect((transportProtocol, source, target) -> {
                        protocol.set(transportProtocol);
                        connectCallbackReceived.countDown();
                    });

            CompletableFuture<Void> startFuture = startAsync(neoLink);
            assertTrue(transferHandshakeReceived.await(5, TimeUnit.SECONDS));
            assertTrue(connectCallbackReceived.await(5, TimeUnit.SECONDS));
            assertEquals("UDP;socket-udp", transferHandshake.get());
            assertEquals(NeoLinkAPI.TransportProtocol.UDP, protocol.get());

            neoLink.close();
            assertStartCompleted(startFuture);
            hookThread.join(3000);
            transferThread.join(3000);
            if (serverError.get() != null) {
                fail("UDP dispatch test server failed", serverError.get());
            }
        }
    }

    @Test
    @DisplayName("runtime disconnect emits error and fails before stopped")
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

            CompletableFuture<Void> startFuture = startAsync(neoLink);

            assertTrue(errorReceived.await(3, TimeUnit.SECONDS));
            assertTrue(stopped.await(3, TimeUnit.SECONDS));
            ExecutionException startFailure =
                    assertThrows(ExecutionException.class, () -> startFuture.get(3, TimeUnit.SECONDS));
            assertInstanceOf(IOException.class, startFailure.getCause());
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
        AtomicReference<String> updateType = new AtomicReference<>();
        AtomicReference<String> decisionInput = new AtomicReference<>();

        try (SecureServerSocket server = new SecureServerSocket(0)) {
            Thread serverThread = Thread.ofVirtual().start(() -> {
                try (SecureSocket socket = server.accept()) {
                    handshake.set(socket.receiveStr(2000));
                    String response = "Unsupported version:6.0.1|6.0.2";
                    socket.sendStr(response);
                    updateReply.set(socket.receiveStr(2000));
                    updateType.set(socket.receiveStr(2000));
                    socket.sendStr("https://download.example.test/neolink");
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
            assertEquals(expectedUpdateType(), updateType.get());
            assertEquals("https://download.example.test/neolink", neoLink.getUpdateURL());
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
                    Thread.sleep(3000);
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

            CompletableFuture<Void> startFuture = startAsync(neoLink);
            assertTrue(debugExceptionReceived.await(3, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, callbackFailure.get());
            awaitRunning(neoLink);
            assertFalse(startFuture.isDone(), "start() must block even when callbacks throw.");

            neoLink.close();
            assertStartCompleted(startFuture);
            serverThread.join(3000);
            if (serverError.get() != null) {
                fail("Lifecycle test server failed", serverError.get());
            }
        }
    }

    private static String expectedUpdateType() {
        return OshiUtils.isWindows() ? "exe" : "jar";
    }

    private static CompletableFuture<Void> startAsync(NeoLinkAPI neoLink) {
        return startAsync(neoLink, null);
    }

    private static CompletableFuture<Void> startAsync(NeoLinkAPI neoLink, Integer connectToNpsTimeoutMillis) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                if (connectToNpsTimeoutMillis == null) {
                    neoLink.start();
                } else {
                    neoLink.start(connectToNpsTimeoutMillis);
                }
                future.complete(null);
            } catch (Throwable e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    private static void awaitRunning(NeoLinkAPI neoLink) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (neoLink.isActive() && neoLink.getState() == NeoLinkState.RUNNING) {
                return;
            }
            Thread.sleep(10);
        }
        fail("NeoLinkAPI did not enter RUNNING. state=" + neoLink.getState());
    }

    private static void assertStartCompleted(CompletableFuture<Void> startFuture) throws Exception {
        startFuture.get(3, TimeUnit.SECONDS);
    }
}
