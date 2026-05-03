package top.ceroxe.api.neolink.transparency;

import top.ceroxe.api.neolink.NeoLinkAPI;
import top.ceroxe.api.neolink.NeoLinkCfg;
import top.ceroxe.api.neolink.NeoLinkState;
import top.ceroxe.api.neolink.exception.NoMoreNetworkFlowException;
import top.ceroxe.api.neolink.exception.NoSuchKeyException;
import top.ceroxe.api.neolink.exception.UnsupportedVersionException;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * API 透明性校验服务端。
 *
 * <p>职责只做两件事：
 * 1. 在本地指定端口同时启动 TCP/UDP 回环服务，确保下游行为绝对可预测；
 * 2. 使用当前仓库的 {@link NeoLinkAPI} 把该本地端口反代到 NeoProxyServer，输出最终 TUNAddr。
 *
 * <p>这样做的原因不是“为了方便演示”，而是为了把变量严格收束：
 * 本地服务只负责原样回显，任何数据错乱、截断、乱序、丢包、半关闭异常，都更容易定位到隧道链路本身。
 */
public final class APITransparencyServer {
    private static final String DEFAULT_REMOTE_DOMAIN = "p.ceroxe.top";
    private static final int DEFAULT_HOOK_PORT = 44801;
    private static final int DEFAULT_CONNECT_PORT = 44802;
    private static final int DEFAULT_LOCAL_PORT = 7777;
    private static final String DEFAULT_LOCAL_BIND_HOST = "127.0.0.1";

    private static final AtomicBoolean RUNNING = new AtomicBoolean(true);

    private APITransparencyServer() {
    }

    public static void main(String[] args) throws Exception {
        RuntimeArgs runtimeArgs = RuntimeArgs.parse(args);
        try (RunningServer server = start(runtimeArgs)) {
            Runtime.getRuntime().addShutdownHook(new Thread(server::close, "neolink-transparency-shutdown"));

            System.out.println("[server] 使用的 remote domain = " + runtimeArgs.remoteDomain());
            System.out.println("[server] 使用的 hook port = " + runtimeArgs.hookPort());
            System.out.println("[server] 使用的 connect port = " + runtimeArgs.connectPort());
            System.out.println("[server] 本地 echo 已监听 tcp/udp: " + runtimeArgs.localBindHost() + ":" + runtimeArgs.localPort());
            System.out.println("[server] tunnel address = " + server.tunAddr());
            System.out.println("[server] 请将此 TUNAddr 传给 APITransparencyClient");

            server.awaitTermination();
        }
    }

    static RunningServer startForCheck(
            String remoteDomain,
            int hookPort,
            int connectPort,
            String accessKey,
            int localPort,
            String localBindHost
    )
            throws Exception {
        return start(new RuntimeArgs(remoteDomain, hookPort, connectPort, accessKey, localPort, localBindHost));
    }

    private static RunningServer start(RuntimeArgs runtimeArgs) throws Exception {
        RUNNING.set(true);
        EchoRuntime echoRuntime = startLocalEchoRuntime(runtimeArgs.localBindHost(), runtimeArgs.localPort());
        NeoLinkAPI tunnel = buildTunnel(runtimeArgs);
        Thread tunnelThread = Thread.ofVirtual().name("neolink-transparency-tunnel").start(() -> runTunnel(tunnel));
        String tunAddr = waitForTunAddr(tunnel);
        return new RunningServer(tunAddr, tunnel, echoRuntime, tunnelThread);
    }

    private static EchoRuntime startLocalEchoRuntime(String localBindHost, int localPort) throws IOException {
        ServerSocket tcpServer = new ServerSocket(localPort, 128, InetAddress.getByName(localBindHost));
        DatagramSocket udpServer = new DatagramSocket(localPort, InetAddress.getByName(localBindHost));

        Thread.ofVirtual().name("transparency-tcp-accept").start(() -> acceptTcpLoop(tcpServer));
        Thread.ofVirtual().name("transparency-udp-echo").start(() -> serveUdpLoop(udpServer));

        return new EchoRuntime(tcpServer, udpServer);
    }

    private static NeoLinkAPI buildTunnel(RuntimeArgs runtimeArgs) {
        NeoLinkCfg cfg = new NeoLinkCfg(
                runtimeArgs.remoteDomain(),
                runtimeArgs.hookPort(),
                runtimeArgs.connectPort(),
                runtimeArgs.accessKey(),
                runtimeArgs.localPort()
        )
                .setLocalDomainName(runtimeArgs.localBindHost())
                .setTCPEnabled(true)
                .setUDPEnabled(true)
                .setPPV2Enabled(false)
                .setDebugMsg(true)
                .setHeartBeatPacketDelay(1000)
                .setLanguage(NeoLinkCfg.ZH_CH);

        return new NeoLinkAPI(cfg)
                .setOnStateChanged(state -> {
                    if (state == NeoLinkState.RUNNING) {
                        System.out.println("[server] tunnel state = RUNNING");
                    } else if (state == NeoLinkState.FAILED) {
                        System.err.println("[server] tunnel state = FAILED");
                    }
                })
                .setOnServerMessage(message -> System.out.println("[server-message] " + message))
                .setOnConnect((protocol, source, target) ->
                        System.out.println("[connect] 新建 " + protocol + " 连接: " + source + " -> " + target))
                .setOnDisconnect((protocol, source, target) ->
                        System.out.println("[disconnect] 关闭 " + protocol + " 连接: " + source + " -> " + target))
                .setOnError((message, cause) -> {
                    System.err.println("[server-error] " + message);
                    if (cause != null) {
                        cause.printStackTrace(System.err);
                    }
                })
                .setOnConnectNeoFailure(() -> System.err.println("[server-error] 连接 Neo transfer port 失败"))
                .setOnConnectLocalFailure(() -> System.err.println("[server-error] 连接本地 echo 服务失败"));
    }

    private static void runTunnel(NeoLinkAPI tunnel) {
        try {
            tunnel.start();
        } catch (UnsupportedVersionException e) {
            System.err.println("[server-error] unsupported version: " + e.serverResponse());
            System.err.println("[server-error] update url: " + tunnel.getUpdateURL());
        } catch (NoSuchKeyException e) {
            System.err.println("[server-error] key 被拒绝: " + e.serverResponse());
        } catch (NoMoreNetworkFlowException e) {
            System.err.println("[server-error] network flow 已耗尽: " + e.serverResponse());
        } catch (IOException e) {
            System.err.println("[server-error] tunnel IO 异常: " + e.getMessage());
            e.printStackTrace(System.err);
        } finally {
            RUNNING.set(false);
        }
    }

    private static String waitForTunAddr(NeoLinkAPI tunnel) throws InterruptedException {
        Instant begin = Instant.now();
        String tunAddr = tunnel.getTunAddr();
        Duration wait = Duration.between(begin, Instant.now());
        System.out.println("[server] 已获取 TUNAddr，耗时 " + wait.toMillis() + " ms");
        return tunAddr;
    }

    private static void acceptTcpLoop(ServerSocket tcpServer) {
        while (RUNNING.get() && !tcpServer.isClosed()) {
            try {
                Socket socket = tcpServer.accept();
                Thread.ofVirtual().name("transparency-tcp-session").start(() -> handleTcpSession(socket));
            } catch (SocketException e) {
                if (RUNNING.get()) {
                    System.err.println("[server-error] TCP accept 失败: " + e.getMessage());
                }
                return;
            } catch (IOException e) {
                System.err.println("[server-error] TCP accept 失败: " + e.getMessage());
            }
        }
    }

    private static void handleTcpSession(Socket socket) {
        try (socket;
             InputStream input = socket.getInputStream();
             OutputStream output = socket.getOutputStream()) {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(30_000);
            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                output.write(buffer, 0, read);
                output.flush();
            }
            socket.shutdownOutput();
        } catch (EOFException ignored) {
        } catch (SocketException e) {
            if (RUNNING.get()) {
                System.err.println("[server-error] TCP session 异常关闭: " + e.getMessage());
            }
        } catch (IOException e) {
            System.err.println("[server-error] TCP session 处理失败: " + e.getMessage());
        }
    }

    private static void serveUdpLoop(DatagramSocket udpServer) {
        byte[] buffer = new byte[65_535];
        while (RUNNING.get() && !udpServer.isClosed()) {
            try {
                DatagramPacket inbound = new DatagramPacket(buffer, buffer.length);
                udpServer.receive(inbound);
                DatagramPacket outbound = new DatagramPacket(
                        inbound.getData(),
                        inbound.getLength(),
                        inbound.getAddress(),
                        inbound.getPort()
                );
                udpServer.send(outbound);
            } catch (SocketException e) {
                if (RUNNING.get()) {
                    System.err.println("[server-error] UDP loop 失败: " + e.getMessage());
                }
                return;
            } catch (IOException e) {
                System.err.println("[server-error] UDP loop 失败: " + e.getMessage());
            }
        }
    }

    private static void shutdown(NeoLinkAPI tunnel, EchoRuntime echoRuntime) {
        RUNNING.set(false);
        try {
            tunnel.close();
        } catch (Exception ignored) {
        }
        echoRuntime.close();
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw usage(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private static int parsePort(String rawValue, String fieldName) {
        try {
            int port = Integer.parseInt(requireText(rawValue, fieldName));
            validatePort(port, fieldName);
            return port;
        } catch (NumberFormatException e) {
            throw usage(fieldName + " must be an integer in 1..65535");
        }
    }

    private static void validatePort(int port, String fieldName) {
        if (port < 1 || port > 65_535) {
            throw usage(fieldName + " must be in 1..65535");
        }
    }

    private static IllegalArgumentException usage(String reason) {
        return new IllegalArgumentException(reason + System.lineSeparator()
                + "用法: APITransparencyServer [remoteDomain] [hookPort] [connectPort] [accessKey] [localPort] [localBindHost]" + System.lineSeparator()
                + "示例: APITransparencyServer p.ceroxe.top 44801 44802 YOUR_KEY 7777 127.0.0.1");
    }

    private record EchoRuntime(ServerSocket tcpServer, DatagramSocket udpServer) implements AutoCloseable {
        private EchoRuntime {
            Objects.requireNonNull(tcpServer, "tcpServer");
            Objects.requireNonNull(udpServer, "udpServer");
        }

        @Override
        public void close() {
            try {
                tcpServer.close();
            } catch (IOException ignored) {
            }
            udpServer.close();
        }
    }

    static final class RunningServer implements AutoCloseable {
        private final String tunAddr;
        private final NeoLinkAPI tunnel;
        private final EchoRuntime echoRuntime;
        private final Thread tunnelThread;

        private RunningServer(String tunAddr, NeoLinkAPI tunnel, EchoRuntime echoRuntime, Thread tunnelThread) {
            this.tunAddr = Objects.requireNonNull(tunAddr, "tunAddr");
            this.tunnel = Objects.requireNonNull(tunnel, "tunnel");
            this.echoRuntime = Objects.requireNonNull(echoRuntime, "echoRuntime");
            this.tunnelThread = Objects.requireNonNull(tunnelThread, "tunnelThread");
        }

        String tunAddr() {
            return tunAddr;
        }

        void awaitTermination() throws InterruptedException {
            tunnelThread.join();
        }

        @Override
        public void close() {
            shutdown(tunnel, echoRuntime);
            try {
                tunnelThread.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private record RuntimeArgs(String remoteDomain, int hookPort, int connectPort, String accessKey, int localPort,
                               String localBindHost) {
        private RuntimeArgs {
            remoteDomain = requireText(remoteDomain, "remoteDomain");
            accessKey = requireText(accessKey, "accessKey");
            validatePort(localPort, "localPort");
            localBindHost = requireText(localBindHost, "localBindHost");
            validatePort(hookPort, "hookPort");
            validatePort(connectPort, "connectPort");
        }

        private static RuntimeArgs parse(String[] args) {
            if (args.length > 6) {
                throw usage("too many arguments");
            }

            String remoteDomain = args.length >= 1 ? args[0] : DEFAULT_REMOTE_DOMAIN;
            int hookPort = args.length >= 2 ? parsePort(args[1], "hookPort") : DEFAULT_HOOK_PORT;
            int connectPort = args.length >= 3 ? parsePort(args[2], "connectPort") : DEFAULT_CONNECT_PORT;
            String accessKey = args.length >= 4 ? args[3] : "";
            int localPort = args.length >= 5 ? parsePort(args[4], "localPort") : DEFAULT_LOCAL_PORT;
            String localBindHost = args.length >= 6 ? args[5] : DEFAULT_LOCAL_BIND_HOST;
            return new RuntimeArgs(remoteDomain, hookPort, connectPort, accessKey, localPort, localBindHost);
        }
    }
}
