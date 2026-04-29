package top.ceroxe.api.neolink;

import fun.ceroxe.api.net.SecureSocket;
import top.ceroxe.api.neolink.exception.NoMoreNetworkFlowException;
import top.ceroxe.api.neolink.exception.NoSuchKeyException;
import top.ceroxe.api.neolink.exception.UnsupportedVersionException;
import top.ceroxe.api.neolink.network.InternetOperator;
import top.ceroxe.api.neolink.network.ProxyOperator;
import top.ceroxe.api.neolink.network.threads.CheckAliveThread;
import top.ceroxe.api.neolink.network.threads.TCPTransformer;
import top.ceroxe.api.neolink.network.threads.UDPTransformer;
import top.ceroxe.api.neolink.util.Debugger;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * NeoLinkAPI 的唯一隧道控制入口。
 *
 * <p>该类把控制连接、心跳检测、服务端指令监听和 TCP/UDP 转发连接统一收束到一个
 * 可关闭的实例中。调用方只需要管理自身业务生命周期；API 内部负责在异常路径上释放
 * socket、线程和活动转发连接。</p>
 */
public final class NeoLink implements AutoCloseable {
    private static final String UNKNOWN_HOST = "unknown";
    private static final String PROTOCOL_LANGUAGE = "zh";
    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    private static final Runnable NOOP = () -> {
    };

    private final NeoLinkCfg cfg;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean closeRequested = new AtomicBoolean(false);
    private final AtomicBoolean tcpEnabled = new AtomicBoolean(true);
    private final AtomicBoolean udpEnabled = new AtomicBoolean(true);
    private final Set<Closeable> activeConnections = ConcurrentHashMap.newKeySet();

    private volatile NeoLinkCfg runtimeCfg;
    private volatile ProxyOperator proxyOperator;
    private volatile ExecutorService workerExecutor;
    private volatile CheckAliveThread checkAliveThread;
    private volatile CompletableFuture<Void> startupFuture;
    private volatile SecureSocket hookSocket;
    private volatile Socket connectingSocket;
    private volatile Future<?> supervisorFuture;
    private volatile long lastReceivedTime = System.currentTimeMillis();
    private volatile int remotePort;

    private volatile BiConsumer<InetSocketAddress, InetSocketAddress> onConnect = NeoLink::ignoreConnectionEvent;
    private volatile BiConsumer<InetSocketAddress, InetSocketAddress> onDisconnect = NeoLink::ignoreConnectionEvent;
    private volatile Runnable onConnectNeoFailure = NOOP;
    private volatile Runnable onConnectLocalFailure = NOOP;

    public NeoLink(NeoLinkCfg cfg) {
        this.cfg = Objects.requireNonNull(cfg, "cfg");
    }

    public synchronized void start()
            throws IOException, UnsupportedVersionException, NoSuchKeyException, NoMoreNetworkFlowException {
        if (isActive()) {
            return;
        }

        runtimeCfg = cfg.copy();
        proxyOperator = new ProxyOperator(
                runtimeCfg.getRemoteDomainName(),
                runtimeCfg.getLocalDomainName(),
                runtimeCfg.getProxyIPToNeoServer(),
                runtimeCfg.getProxyIPToLocalServer()
        );
        workerExecutor = Executors.newVirtualThreadPerTaskExecutor();
        startupFuture = new CompletableFuture<>();
        checkAliveThread = new CheckAliveThread(
                () -> hookSocket,
                () -> lastReceivedTime,
                runtimeCfg.getHeartBeatPacketDelay(),
                this::emitError
        );
        closeRequested.set(false);
        running.set(true);
        supervisorFuture = workerExecutor.submit(this::runCore);

        try {
            awaitStartup();
        } catch (IOException | UnsupportedVersionException | NoSuchKeyException | NoMoreNetworkFlowException e) {
            close();
            throw e;
        } catch (RuntimeException e) {
            close();
            throw e;
        }
    }

    public boolean isActive() {
        return running.get();
    }

    public int getRemotePort() {
        return remotePort;
    }

    public NeoLink setTCPEnabled(boolean enabled) throws IOException {
        requireActiveTunnel();
        tcpEnabled.set(enabled);
        return this;
    }

    public NeoLink setUDPEnabled(boolean enabled) throws IOException {
        requireActiveTunnel();
        udpEnabled.set(enabled);
        return this;
    }

    public NeoLink setOnConnect(BiConsumer<InetSocketAddress, InetSocketAddress> onConnect) {
        this.onConnect = Objects.requireNonNull(onConnect, "onConnect");
        return this;
    }

    public NeoLink setOnConnect(Runnable onConnect) {
        Objects.requireNonNull(onConnect, "onConnect");
        return setOnConnect((source, target) -> onConnect.run());
    }

    public NeoLink setOnDisconnect(BiConsumer<InetSocketAddress, InetSocketAddress> onDisconnect) {
        this.onDisconnect = Objects.requireNonNull(onDisconnect, "onDisconnect");
        return this;
    }

    public NeoLink setOnDisconnect(Runnable onDisconnect) {
        Objects.requireNonNull(onDisconnect, "onDisconnect");
        return setOnDisconnect((source, target) -> onDisconnect.run());
    }

    public NeoLink setOnConnectNeoFailure(Runnable onConnectNeoFailure) {
        this.onConnectNeoFailure = Objects.requireNonNull(onConnectNeoFailure, "onConnectNeoFailure");
        return this;
    }

    public NeoLink setOnConnectLocalFailure(Runnable onConnectLocalFailure) {
        this.onConnectLocalFailure = Objects.requireNonNull(onConnectLocalFailure, "onConnectLocalFailure");
        return this;
    }

    public static String version() {
        return VersionInfo.VERSION;
    }

    private void runCore() {
        try {
            connectToNeoServer();
            exchangeClientInfoWithServer();
            CheckAliveThread activeCheckAliveThread = checkAliveThread;
            if (activeCheckAliveThread != null) {
                activeCheckAliveThread.startThread();
            }
            completeStartup(null);
            listenForServerCommands();
        } catch (UnsupportedVersionException | NoSuchKeyException | NoMoreNetworkFlowException e) {
            completeStartup(e);
            closeRequested.set(true);
            emitError(e.getMessage(), e);
        } catch (IOException e) {
            completeStartup(e);
            if (running.get() && !closeRequested.get()) {
                emitError("NeoLink 隧道异常停止。", e);
                Debugger.debugOperation(e);
            }
        } catch (Exception e) {
            IOException exception = toIOException("NeoLink 隧道异常停止。", e);
            completeStartup(exception);
            if (running.get() && !closeRequested.get()) {
                emitError(exception.getMessage(), exception);
                Debugger.debugOperation(exception);
            }
        } finally {
            CheckAliveThread activeCheckAliveThread = checkAliveThread;
            if (activeCheckAliveThread != null) {
                activeCheckAliveThread.stopThread();
            }
            closeActiveConnection();
            running.set(false);
            shutdownWorkerExecutor();
        }
    }

    private void connectToNeoServer() throws IOException {
        if (proxyOperator.hasProxy(ProxyOperator.TO_NEO)) {
            hookSocket = proxyOperator.getHandledSecureSocket(
                    ProxyOperator.TO_NEO,
                    runtimeCfg.getHookPort(),
                    CONNECT_TIMEOUT_MILLIS
            );
            registerConnection(hookSocket);
            return;
        }

        Socket socket = new Socket();
        registerConnection(socket);
        connectingSocket = socket;
        try {
            socket.connect(
                    new InetSocketAddress(runtimeCfg.getRemoteDomainName(), runtimeCfg.getHookPort()),
                    CONNECT_TIMEOUT_MILLIS
            );
            unregisterConnection(socket);
            hookSocket = new SecureSocket(socket);
            registerConnection(hookSocket);
        } catch (IOException e) {
            InternetOperator.close(socket);
            unregisterConnection(socket);
            throw e;
        } finally {
            connectingSocket = null;
        }
    }

    private void exchangeClientInfoWithServer()
            throws IOException, UnsupportedVersionException, NoSuchKeyException, NoMoreNetworkFlowException {
        hookSocket.sendStr(formatClientInfoString());
        String serverResponse = hookSocket.receiveStr();
        if (serverResponse == null) {
            throw new IOException("NeoProxyServer 在握手阶段关闭了连接。");
        }

        if (isUnsupportedVersionResponse(serverResponse)) {
            hookSocket.sendStr("false");
            throw new UnsupportedVersionException(serverResponse);
        }

        if (isNoMoreNetworkFlowResponse(serverResponse)) {
            throw new NoMoreNetworkFlowException(serverResponse);
        }

        if (isNoSuchKeyResponse(serverResponse)) {
            throw new NoSuchKeyException(serverResponse);
        }

        if (isTerminalServerResponse(serverResponse)) {
            throw new IOException("NeoProxyServer 拒绝建立隧道：" + serverResponse);
        }

        lastReceivedTime = System.currentTimeMillis();
    }

    String formatClientInfoString() {
        StringBuilder info = new StringBuilder()
                .append(PROTOCOL_LANGUAGE)
                .append(';')
                .append(VersionInfo.VERSION)
                .append(';')
                .append(runtimeCfg.getKey())
                .append(';');
        if (tcpEnabled.get()) {
            info.append('T');
        }
        if (udpEnabled.get()) {
            info.append('U');
        }
        return info.toString();
    }

    private void listenForServerCommands() throws IOException, NoMoreNetworkFlowException {
        String message;
        while (running.get() && (message = hookSocket.receiveStr()) != null) {
            lastReceivedTime = System.currentTimeMillis();
            if (message.startsWith(":>")) {
                handleServerCommand(message.substring(2));
            }
        }
        throw new IOException("NeoProxyServer 连接已关闭。");
    }

    private void handleServerCommand(String command) throws NoMoreNetworkFlowException {
        String[] parts = command.split(";");
        switch (parts[0]) {
            case "sendSocketTCP" -> {
                if (tcpEnabled.get() && parts.length >= 3) {
                    submitWorker(() -> createNewTCPConnection(parts[1], parts[2]));
                }
            }
            case "sendSocketUDP" -> {
                if (udpEnabled.get() && parts.length >= 3) {
                    submitWorker(() -> createNewUDPConnection(parts[1], parts[2]));
                }
            }
            case "exitNoFlow" -> {
                closeRequested.set(true);
                running.set(false);
                NoMoreNetworkFlowException exception = new NoMoreNetworkFlowException();
                emitError(exception.getMessage(), exception);
                closeActiveConnection();
                throw exception;
            }
            default -> updateRemotePort(parts[0]);
        }
    }

    private void updateRemotePort(String value) {
        try {
            remotePort = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            Debugger.debugOperation("忽略未知服务端指令：" + value);
        }
    }

    private void createNewTCPConnection(String socketId, String remoteAddress) {
        Socket localServerSocket = null;
        SecureSocket neoTransferSocket = null;
        try {
            localServerSocket = openLocalSocket();
            neoTransferSocket = openTransferSocket();
            registerConnection(localServerSocket);
            registerConnection(neoTransferSocket);
            neoTransferSocket.sendStr("TCP" + ";" + socketId);

            emitConnectionEvent(onConnect, remoteAddress);

            TCPTransformer serverToLocalTask = new TCPTransformer(neoTransferSocket, localServerSocket, false);
            TCPTransformer localToServerTask = new TCPTransformer(localServerSocket, neoTransferSocket, false);
            ExecutorService executor = requireWorkerExecutor();
            Future<?> first = executor.submit(serverToLocalTask);
            Future<?> second = executor.submit(localToServerTask);
            Socket trackedLocalSocket = localServerSocket;
            SecureSocket trackedTransferSocket = neoTransferSocket;
            executor.submit(() -> awaitTransformers(first, second, remoteAddress, trackedLocalSocket, trackedTransferSocket));
        } catch (Exception e) {
            IOException exception;
            if (localServerSocket == null) {
                runCallback(onConnectLocalFailure);
                exception = toIOException("连接本地服务失败：" + runtimeCfg.getLocalDomainName() + ":" + runtimeCfg.getLocalPort(), e);
            } else if (neoTransferSocket == null) {
                runCallback(onConnectNeoFailure);
                exception = toIOException("连接 NeoProxyServer 传输端口失败："
                        + runtimeCfg.getRemoteDomainName() + ":" + runtimeCfg.getHostConnectPort(), e);
            } else {
                exception = toIOException("创建 TCP 隧道失败。", e);
            }
            emitError(exception.getMessage(), exception);
            InternetOperator.close(localServerSocket, neoTransferSocket);
            unregisterConnection(localServerSocket, neoTransferSocket);
        }
    }

    private void createNewUDPConnection(String socketId, String remoteAddress) {
        SecureSocket neoTransferSocket = null;
        DatagramSocket datagramSocket = null;
        try {
            neoTransferSocket = openTransferSocket();
            datagramSocket = new DatagramSocket(new InetSocketAddress(InetAddress.getByName("0.0.0.0"), 0));
            registerConnection(neoTransferSocket);
            registerConnection(datagramSocket);
            neoTransferSocket.sendStr("UDP" + ";" + socketId);

            emitConnectionEvent(onConnect, remoteAddress);

            UDPTransformer localToNeoTask = new UDPTransformer(
                    datagramSocket,
                    neoTransferSocket,
                    runtimeCfg.getLocalDomainName(),
                    runtimeCfg.getLocalPort()
            );
            UDPTransformer neoToLocalTask = new UDPTransformer(
                    neoTransferSocket,
                    datagramSocket,
                    runtimeCfg.getLocalDomainName(),
                    runtimeCfg.getLocalPort()
            );
            ExecutorService executor = requireWorkerExecutor();
            Future<?> first = executor.submit(localToNeoTask);
            Future<?> second = executor.submit(neoToLocalTask);
            SecureSocket trackedTransferSocket = neoTransferSocket;
            DatagramSocket trackedDatagramSocket = datagramSocket;
            executor.submit(() -> awaitTransformers(first, second, remoteAddress, trackedTransferSocket, trackedDatagramSocket));
        } catch (Exception e) {
            IOException exception;
            if (neoTransferSocket == null) {
                runCallback(onConnectNeoFailure);
                exception = toIOException("连接 NeoProxyServer 传输端口失败："
                        + runtimeCfg.getRemoteDomainName() + ":" + runtimeCfg.getHostConnectPort(), e);
            } else {
                exception = toIOException("创建 UDP 隧道失败。", e);
            }
            emitError(exception.getMessage(), exception);
            InternetOperator.close(datagramSocket, neoTransferSocket);
            unregisterConnection(datagramSocket, neoTransferSocket);
        }
    }

    private Socket openLocalSocket() throws IOException {
        if (proxyOperator.hasProxy(ProxyOperator.TO_LOCAL)) {
            return proxyOperator.getHandledSocket(
                    ProxyOperator.TO_LOCAL,
                    runtimeCfg.getLocalPort(),
                    CONNECT_TIMEOUT_MILLIS
            );
        }
        return connectToLocalRobustly(runtimeCfg.getLocalDomainName(), runtimeCfg.getLocalPort());
    }

    private Socket connectToLocalRobustly(String host, int port) throws IOException {
        InetAddress[] addresses = InetAddress.getAllByName(host);
        IOException lastException = null;
        for (InetAddress address : addresses) {
            Socket socket = new Socket();
            registerConnection(socket);
            try {
                socket.connect(new InetSocketAddress(address, port), CONNECT_TIMEOUT_MILLIS);
                return socket;
            } catch (IOException e) {
                lastException = e;
                InternetOperator.close(socket);
                unregisterConnection(socket);
            }
        }
        throw lastException != null ? lastException : new IOException("解析本地域名失败：" + host);
    }

    private SecureSocket openTransferSocket() throws IOException {
        if (proxyOperator.hasProxy(ProxyOperator.TO_NEO)) {
            return proxyOperator.getHandledSecureSocket(
                    ProxyOperator.TO_NEO,
                    runtimeCfg.getHostConnectPort(),
                    CONNECT_TIMEOUT_MILLIS
            );
        }

        Socket socket = new Socket();
        registerConnection(socket);
        try {
            socket.connect(
                    new InetSocketAddress(runtimeCfg.getRemoteDomainName(), runtimeCfg.getHostConnectPort()),
                    CONNECT_TIMEOUT_MILLIS
            );
            unregisterConnection(socket);
            return new SecureSocket(socket);
        } catch (IOException e) {
            InternetOperator.close(socket);
            unregisterConnection(socket);
            throw e;
        }
    }

    private void awaitTransformers(Future<?> first, Future<?> second, String remoteAddress, Closeable... trackedConnections) {
        try {
            first.get();
        } catch (Exception e) {
            Debugger.debugOperation(e);
        }
        try {
            second.get();
        } catch (Exception e) {
            Debugger.debugOperation(e);
        } finally {
            InternetOperator.close(trackedConnections);
            unregisterConnection(trackedConnections);
            emitConnectionEvent(onDisconnect, remoteAddress);
        }
    }

    private void submitWorker(Runnable task) {
        ExecutorService executor = workerExecutor;
        if (executor == null || executor.isShutdown() || !running.get()) {
            return;
        }

        try {
            executor.submit(task);
        } catch (RuntimeException e) {
            Debugger.debugOperation(e);
        }
    }

    private ExecutorService requireWorkerExecutor() throws IOException {
        ExecutorService executor = workerExecutor;
        if (executor == null || executor.isShutdown()) {
            throw new IOException("NeoLink 隧道已经停止，无法创建新的转发连接。");
        }
        return executor;
    }

    @Override
    public void close() {
        closeRequested.set(true);
        running.set(false);
        CheckAliveThread activeCheckAliveThread = checkAliveThread;
        if (activeCheckAliveThread != null) {
            activeCheckAliveThread.stopThread();
        }
        closeActiveConnection();
        shutdownWorkerExecutor();
        completeStartup(new IOException("NeoLink 启动已取消。"));
    }

    private void awaitStartup()
            throws IOException, UnsupportedVersionException, NoSuchKeyException, NoMoreNetworkFlowException {
        try {
            startupFuture.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("等待 NeoLink 启动时线程被中断。", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            if (cause instanceof UnsupportedVersionException unsupportedVersionException) {
                throw unsupportedVersionException;
            }
            if (cause instanceof NoSuchKeyException noSuchKeyException) {
                throw noSuchKeyException;
            }
            if (cause instanceof NoMoreNetworkFlowException noMoreNetworkFlowException) {
                throw noMoreNetworkFlowException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IOException("NeoLink 启动失败。", cause);
        }
    }

    private void completeStartup(Throwable failure) {
        CompletableFuture<Void> future = startupFuture;
        if (future == null || future.isDone()) {
            return;
        }
        if (failure == null) {
            future.complete(null);
        } else {
            future.completeExceptionally(failure);
        }
    }

    private void requireActiveTunnel() throws IOException {
        if (!running.get()) {
            throw new IOException("NeoLink 隧道未启动，必须先调用 start() 才能切换 TCP 或 UDP 状态。");
        }
    }

    private void closeActiveConnection() {
        InternetOperator.close(connectingSocket, hookSocket);
        closeAllTrackedConnections();
        connectingSocket = null;
        hookSocket = null;
        remotePort = 0;
    }

    private void registerConnection(Closeable closeable) {
        if (closeable != null) {
            activeConnections.add(closeable);
        }
    }

    private void unregisterConnection(Closeable... closeables) {
        for (Closeable closeable : closeables) {
            if (closeable != null) {
                activeConnections.remove(closeable);
            }
        }
    }

    private void closeAllTrackedConnections() {
        for (Closeable closeable : activeConnections.toArray(Closeable[]::new)) {
            InternetOperator.close(closeable);
            unregisterConnection(closeable);
        }
    }

    private void shutdownWorkerExecutor() {
        ExecutorService activeExecutor = workerExecutor;
        if (activeExecutor != null) {
            activeExecutor.shutdownNow();
        }
        workerExecutor = null;
    }

    private void emitConnectionEvent(BiConsumer<InetSocketAddress, InetSocketAddress> handler, String remoteAddress) {
        NeoLinkCfg activeCfg = runtimeCfg;
        InetSocketAddress target = InetSocketAddress.createUnresolved(
                activeCfg.getLocalDomainName(),
                activeCfg.getLocalPort()
        );
        try {
            handler.accept(parseRemoteAddress(remoteAddress), target);
        } catch (RuntimeException e) {
            Debugger.debugOperation(e);
        }
    }

    private void runCallback(Runnable callback) {
        try {
            callback.run();
        } catch (RuntimeException e) {
            Debugger.debugOperation(e);
        }
    }

    private void emitError(String message, Throwable cause) {
        Debugger.debugOperation(message);
        if (cause instanceof Exception exception) {
            Debugger.debugOperation(exception);
        }
    }

    private static boolean isUnsupportedVersionResponse(String response) {
        return response.contains("nsupported")
                || response.contains("不")
                || response.contains("旧");
    }

    private static boolean isNoMoreNetworkFlowResponse(String response) {
        return response.contains("exitNoFlow")
                || response.contains("No extra network traffic left")
                || response.contains("没有多余的流量")
                || response.contains("流量耗尽");
    }

    private static boolean isNoSuchKeyResponse(String response) {
        return isTerminalServerResponse(response);
    }

    private static boolean isTerminalServerResponse(String response) {
        return response.contains("exit")
                || response.contains("退")
                || response.contains("错误")
                || response.contains("denied")
                || response.contains("already")
                || response.contains("过期")
                || response.contains("占");
    }

    private static IOException toIOException(String message, Exception cause) {
        if (cause instanceof IOException ioException) {
            return ioException;
        }
        return new IOException(message, cause);
    }

    private static InetSocketAddress parseRemoteAddress(String remoteAddress) {
        if (remoteAddress == null || remoteAddress.isBlank()) {
            return InetSocketAddress.createUnresolved(UNKNOWN_HOST, 0);
        }

        String value = remoteAddress.trim();
        if (value.startsWith("/")) {
            value = value.substring(1);
        }

        if (value.startsWith("[")) {
            int closingBracket = value.indexOf(']');
            if (closingBracket > 1) {
                String host = value.substring(1, closingBracket);
                int port = parsePortAfter(value, closingBracket + 1);
                return InetSocketAddress.createUnresolved(host, port);
            }
        }

        int lastColon = value.lastIndexOf(':');
        if (lastColon > 0 && lastColon + 1 < value.length()) {
            String host = value.substring(0, lastColon);
            int port = parsePort(value.substring(lastColon + 1));
            if (port >= 0) {
                return InetSocketAddress.createUnresolved(host, port);
            }
        }
        return InetSocketAddress.createUnresolved(value, 0);
    }

    private static int parsePortAfter(String value, int index) {
        if (index >= value.length() || value.charAt(index) != ':' || index + 1 >= value.length()) {
            return 0;
        }
        int port = parsePort(value.substring(index + 1));
        return port >= 0 ? port : 0;
    }

    private static int parsePort(String value) {
        try {
            int port = Integer.parseInt(value);
            return port >= 0 && port <= 65535 ? port : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static void ignoreConnectionEvent(InetSocketAddress source, InetSocketAddress target) {
    }
}
