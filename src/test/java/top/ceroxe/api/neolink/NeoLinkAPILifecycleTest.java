package top.ceroxe.api.neolink;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.ceroxe.api.OshiUtils;
import top.ceroxe.api.net.SecureServerSocket;
import top.ceroxe.api.net.SecureSocket;
import top.ceroxe.api.neolink.exception.NoMoreNetworkFlowException;
import top.ceroxe.api.neolink.exception.NoMorePortException;
import top.ceroxe.api.neolink.exception.OutDatedKeyException;
import top.ceroxe.api.neolink.exception.PortOccupiedException;
import top.ceroxe.api.neolink.exception.UnRecognizedKeyException;
import top.ceroxe.api.neolink.exception.UnsupportedVersionException;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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
                        socket.sendStr("Connection build up successfully");
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
    @DisplayName("重启后 getTunAddr 必须等待并返回最新 tunnel 地址")
    void getTunAddrReturnsLatestAddressAfterRestart() throws Exception {
        CountDownLatch firstHandshake = new CountDownLatch(1);
        CountDownLatch secondHandshake = new CountDownLatch(1);
        AtomicReference<Throwable> serverError = new AtomicReference<>();

        try (SecureServerSocket server = new SecureServerSocket(0)) {
            Thread serverThread = Thread.ofVirtual().start(() -> {
                try (SecureSocket firstSocket = server.accept()) {
                    assertNotNull(firstSocket.receiveStr(2000));
                    firstSocket.sendStr("Connection build up successfully");
                    firstSocket.sendStr("Use the address: first.example.test:10001 to start up connections.");
                    firstHandshake.countDown();
                    Thread.sleep(1000);
                } catch (Throwable e) {
                    serverError.compareAndSet(null, e);
                    return;
                }

                try (SecureSocket secondSocket = server.accept()) {
                    assertNotNull(secondSocket.receiveStr(2000));
                    secondSocket.sendStr("Connection build up successfully");
                    Thread.sleep(300);
                    secondSocket.sendStr("Use the address: second.example.test:10002 to start up connections.");
                    secondHandshake.countDown();
                    Thread.sleep(1000);
                } catch (Throwable e) {
                    serverError.compareAndSet(null, e);
                }
            });

            NeoLinkCfg cfg = new NeoLinkCfg("localhost", server.getLocalPort(), server.getLocalPort(), "key", 25565)
                    .setTCPEnabled(false)
                    .setUDPEnabled(false);
            NeoLinkAPI neoLink = new NeoLinkAPI(cfg);

            CompletableFuture<Void> firstStart = startAsync(neoLink);
            assertTrue(firstHandshake.await(3, TimeUnit.SECONDS));
            assertEquals("first.example.test:10001", neoLink.getTunAddr());

            neoLink.close();
            assertStartCompleted(firstStart);

            CompletableFuture<Void> secondStart = startAsync(neoLink);
            CompletableFuture<String> secondTunAddr = CompletableFuture.supplyAsync(neoLink::getTunAddr);
            Thread.sleep(100);
            assertFalse(secondTunAddr.isDone(), "getTunAddr() must wait for the restarted tunnel address.");

            assertTrue(secondHandshake.await(3, TimeUnit.SECONDS));
            assertEquals("second.example.test:10002", secondTunAddr.get(3, TimeUnit.SECONDS));

            neoLink.close();
            assertStartCompleted(secondStart);
            serverThread.join(3000);
            if (serverError.get() != null) {
                fail("Lifecycle test server failed", serverError.get());
            }
        }
    }

    @Test
    @DisplayName("NPS 下发的中英文连接地址消息应解析为纯隧道地址")
    void npsTunnelAddressMessagesAreParsedAsPlainAddress() {
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
    @DisplayName("生命周期、隧道地址和服务端消息应通过稳定 API 暴露")
    void lifecycleTunnelAddressAndServerMessagesAreObservable() throws Exception {
        CountDownLatch serverMessageReceived = new CountDownLatch(2);
        AtomicReference<Throwable> serverError = new AtomicReference<>();
        List<String> serverMessages = new CopyOnWriteArrayList<>();
        List<NeoLinkState> states = new CopyOnWriteArrayList<>();

        try (SecureServerSocket server = new SecureServerSocket(0)) {
            Thread serverThread = Thread.ofVirtual().start(() -> {
                try (SecureSocket socket = server.accept()) {
                    assertNotNull(socket.receiveStr(2000));
                    socket.sendStr("Connection build up successfully");
                    socket.sendStr(":>45678");
                    socket.sendStr("Use the address: tunnel.example.test:45678 to start up connections.");
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
                    .setOnServerMessage(message -> {
                        serverMessages.add(message);
                        serverMessageReceived.countDown();
                    });

            CompletableFuture<String> tunAddrBeforeStart = CompletableFuture.supplyAsync(neoLink::getTunAddr);
            Thread.sleep(100);
            assertFalse(tunAddrBeforeStart.isDone(), "getTunAddr() must block before NPS returns the tunnel address.");

            CompletableFuture<Void> startFuture = startAsync(neoLink);
            assertTrue(serverMessageReceived.await(3, TimeUnit.SECONDS));
            assertFalse(startFuture.isDone(), "start() must keep blocking after startup succeeds.");

            assertNotNull(neoLink.getHookSocket());
            assertTrue(neoLink.getHookSocket().isConnected());
            assertNull(neoLink.getUpdateURL());
            assertEquals("tunnel.example.test:45678", tunAddrBeforeStart.get(3, TimeUnit.SECONDS));
            assertEquals("tunnel.example.test:45678", neoLink.getTunAddr());
            assertTrue(serverMessages.contains("traffic warning from server"));
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
                    socket.sendStr("Connection build up successfully");
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
    @DisplayName("默认超时应为 1000ms，且只影响未重载的 start()")
    void defaultStartUsesOneSecondConnectTimeout() throws Exception {
        CountDownLatch timeoutObserved = new CountDownLatch(1);
        AtomicReference<Throwable> serverError = new AtomicReference<>();

        try (SecureServerSocket server = new SecureServerSocket(0)) {
            Thread serverThread = Thread.ofVirtual().start(() -> {
                try (SecureSocket socket = server.accept()) {
                    assertNotNull(socket.receiveStr(2000));
                    socket.sendStr("Connection build up successfully");
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
                        if (message != null && message.contains("connectToLocalTimeoutMs=1000")
                                && message.contains("connectToNpsTimeoutMs=1000")) {
                            timeoutObserved.countDown();
                        }
                    });

            CompletableFuture<Void> startFuture = startAsync(neoLink);
            awaitRunning(neoLink);
            assertTrue(timeoutObserved.await(3, TimeUnit.SECONDS));

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
                    socket.sendStr("Connection build up successfully");
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
                    socket.sendStr("Connection build up successfully");
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
                    socket.sendStr("Connection build up successfully");
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
    @DisplayName("运行期 TCP/UDP 切换应发送服务端原生控制标记")
    void runtimeProtocolSwitchSendsNativeControlFlags() throws Exception {
        CountDownLatch commandReceived = new CountDownLatch(1);
        AtomicReference<Throwable> serverError = new AtomicReference<>();
        AtomicReference<String> command = new AtomicReference<>();

        try (SecureServerSocket server = new SecureServerSocket(0)) {
            Thread serverThread = Thread.ofVirtual().start(() -> {
                try (SecureSocket socket = server.accept()) {
                    assertNotNull(socket.receiveStr(2000));
                    socket.sendStr("Connection build up successfully");
                    command.set(socket.receiveStr(3000));
                    commandReceived.countDown();
                    Thread.sleep(500);
                } catch (Throwable e) {
                    serverError.compareAndSet(null, e);
                }
            });

            NeoLinkCfg cfg = new NeoLinkCfg("localhost", server.getLocalPort(), server.getLocalPort(), "key", 25565)
                    .setUDPEnabled(false);
            NeoLinkAPI neoLink = new NeoLinkAPI(cfg);

            CompletableFuture<Void> startFuture = startAsync(neoLink);
            awaitRunning(neoLink);
            neoLink.updateRuntimeProtocolFlags(false, true);

            assertTrue(commandReceived.await(5, TimeUnit.SECONDS));
            assertEquals("U", command.get());

            neoLink.close();
            assertStartCompleted(startFuture);
            serverThread.join(3000);
            if (serverError.get() != null) {
                fail("Runtime protocol-switch test server failed", serverError.get());
            }
        }
    }

    @Test
    @DisplayName("运行期协议切换过渡窗口不应丢掉服务端按旧状态派发的 TCP 连接")
    void runtimeProtocolSwitchGraceWindowKeepsOldTcpDispatchAlive() throws Exception {
        CountDownLatch controlCommandReceived = new CountDownLatch(1);
        CountDownLatch transferHandshakeReceived = new CountDownLatch(1);
        CountDownLatch localAccepted = new CountDownLatch(1);
        AtomicReference<Throwable> serverError = new AtomicReference<>();
        AtomicReference<String> controlCommand = new AtomicReference<>();
        AtomicReference<String> transferHandshake = new AtomicReference<>();

        try (SecureServerSocket hookServer = new SecureServerSocket(0);
             SecureServerSocket transferServer = new SecureServerSocket(0);
             ServerSocket localServer = new ServerSocket(0)) {
            Thread hookThread = Thread.ofVirtual().start(() -> {
                try (SecureSocket socket = hookServer.accept()) {
                    assertNotNull(socket.receiveStr(2000));
                    socket.sendStr("Connection build up successfully");
                    controlCommand.set(socket.receiveStr(3000));
                    controlCommandReceived.countDown();
                    socket.sendStr(":>sendSocketTCP;socket-after-switch;203.0.113.20:4567");
                    Thread.sleep(1000);
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
            NeoLinkAPI neoLink = new NeoLinkAPI(cfg);

            CompletableFuture<Void> startFuture = startAsync(neoLink);
            awaitRunning(neoLink);
            neoLink.updateRuntimeProtocolFlags(false, true);

            assertTrue(controlCommandReceived.await(5, TimeUnit.SECONDS));
            assertEquals("U", controlCommand.get());
            assertTrue(transferHandshakeReceived.await(5, TimeUnit.SECONDS));
            assertTrue(localAccepted.await(5, TimeUnit.SECONDS));
            assertEquals("TCP;socket-after-switch", transferHandshake.get());

            neoLink.close();
            assertStartCompleted(startFuture);
            hookThread.join(3000);
            transferThread.join(3000);
            localThread.join(3000);
            if (serverError.get() != null) {
                fail("Runtime protocol-switch grace-window test server failed", serverError.get());
            }
        }
    }

    @Test
    @DisplayName("服务端运行期下发过期密钥文案加 exit 应映射为过期密钥异常")
    void runtimeOutdatedKeyExitMapsToOutDatedKeyException() throws Exception {
        CountDownLatch errorReceived = new CountDownLatch(1);
        AtomicReference<Throwable> serverError = new AtomicReference<>();
        AtomicReference<Throwable> runtimeFailure = new AtomicReference<>();

        try (SecureServerSocket server = new SecureServerSocket(0)) {
            Thread serverThread = Thread.ofVirtual().start(() -> {
                try (SecureSocket socket = server.accept()) {
                    assertNotNull(socket.receiveStr(2000));
                    socket.sendStr("Connection build up successfully");
                    socket.sendStr("Key key are out of date.");
                    socket.sendStr(":>exit");
                } catch (Throwable e) {
                    serverError.compareAndSet(null, e);
                }
            });

            NeoLinkCfg cfg = new NeoLinkCfg("localhost", server.getLocalPort(), server.getLocalPort(), "key", 25565)
                    .setTCPEnabled(false)
                    .setUDPEnabled(false);
            NeoLinkAPI neoLink = new NeoLinkAPI(cfg)
                    .setOnError((message, cause) -> {
                        runtimeFailure.set(cause);
                        errorReceived.countDown();
                    });

            CompletableFuture<Void> startFuture = startAsync(neoLink);

            assertTrue(errorReceived.await(5, TimeUnit.SECONDS));
            ExecutionException startFailure =
                    assertThrows(ExecutionException.class, () -> startFuture.get(5, TimeUnit.SECONDS));
            assertInstanceOf(OutDatedKeyException.class, startFailure.getCause());
            assertInstanceOf(OutDatedKeyException.class, runtimeFailure.get());

            serverThread.join(3000);
            if (serverError.get() != null) {
                fail("Runtime outdated-key exit test server failed", serverError.get());
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
                    String response = "Unsupported version ! It should be :6.0.1|6.0.2";
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
            assertEquals("Unsupported version ! It should be :6.0.1|6.0.2", decisionInput.get());
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
                    socket.sendStr("Connection build up successfully");
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

    @Test
    @DisplayName("服务端未识别密钥文案应映射为同名结构化异常")
    void currentServerAccessDeniedResponseMapsToUnRecognizedKeyException() throws Exception {
        assertStartupResponseThrows(
                "Access denied , force exiting...",
                UnRecognizedKeyException.class
        );
    }

    @Test
    @DisplayName("服务端过期密钥文案应映射为同名结构化异常")
    void currentServerOutdatedKeyResponseMapsToOutDatedKeyException() throws Exception {
        assertStartupResponseThrows(
                "Key key are out of date.",
                OutDatedKeyException.class
        );
    }

    @Test
    @DisplayName("服务端流量耗尽文案应映射为流量耗尽异常")
    void currentServerNoFlowResponseMapsToNoMoreNetworkFlowException() throws Exception {
        assertStartupResponseThrows(
                "This key have no network flow left ! Force exiting...",
                NoMoreNetworkFlowException.class
        );
    }

    @Test
    @DisplayName("服务端远端端口占用文案应映射为同名结构化异常")
    void currentServerRemotePortOccupiedResponseMapsToPortOccupiedException() throws Exception {
        assertStartupResponseThrows(
                "Connection rejected: Port occupied by another node or limit reached.",
                PortOccupiedException.class
        );
    }

    @Test
    @DisplayName("服务端无可用端口文案应映射为同名结构化异常")
    void currentServerNoMorePortResponseMapsToNoMorePortException() throws Exception {
        assertStartupResponseThrows(
                "This port is already in use. Please try with a different node.",
                NoMorePortException.class
        );
    }

    private static String expectedUpdateType() {
        return OshiUtils.isWindows() ? "exe" : "jar";
    }

    private static <T extends Throwable> void assertStartupResponseThrows(
            String serverResponse,
            Class<T> expectedType
    ) throws Exception {
        AtomicReference<Throwable> serverError = new AtomicReference<>();

        try (SecureServerSocket server = new SecureServerSocket(0)) {
            Thread serverThread = Thread.ofVirtual().start(() -> {
                try (SecureSocket socket = server.accept()) {
                    assertNotNull(socket.receiveStr(2000));
                    socket.sendStr(serverResponse);
                    Thread.sleep(100);
                } catch (Throwable e) {
                    serverError.compareAndSet(null, e);
                }
            });

            NeoLinkCfg cfg = new NeoLinkCfg("localhost", server.getLocalPort(), server.getLocalPort(), "key", 25565)
                    .setTCPEnabled(false)
                    .setUDPEnabled(false);
            NeoLinkAPI neoLink = new NeoLinkAPI(cfg);

            assertThrows(expectedType, neoLink::start);

            serverThread.join(3000);
            if (serverError.get() != null) {
                fail("Startup rejection test server failed", serverError.get());
            }
        }
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
