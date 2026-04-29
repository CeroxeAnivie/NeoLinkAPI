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
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * NeoLinkAPI 的唯一隧道控制入口。
 *
 * <p>该类把控制连接、心跳检测、服务端指令监听和 TCP/UDP 转发连接统一收束到一个
 * 可关闭的实例中。调用方只需要管理自身业务生命周期；API 内部负责在异常路径上释放
 * socket、线程和活动转发连接。</p>
 */
public final class NeoLinkAPI implements AutoCloseable {
    private static final String UNKNOWN_HOST = "unknown";
    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;
    private static final Runnable NOOP = () -> {
    };

    private final NeoLinkCfg cfg;
    private final Object lifecycleLock = new Object();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean closeRequested = new AtomicBoolean(false);
    private final AtomicLong lifecycleGeneration = new AtomicLong(0);
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

    private volatile BiConsumer<InetSocketAddress, InetSocketAddress> onConnect = NeoLinkAPI::ignoreConnectionEvent;
    private volatile BiConsumer<InetSocketAddress, InetSocketAddress> onDisconnect = NeoLinkAPI::ignoreConnectionEvent;
    private volatile Runnable onConnectNeoFailure = NOOP;
    private volatile Runnable onConnectLocalFailure = NOOP;

    /**
     * 绑定一个不可为空的配置对象。
     *
     * <p>{@link #start()} 会复制该配置，之后运行中的隧道不再读取原配置对象，
     * 这样调用方在启动后继续修改 {@link NeoLinkCfg} 也不会产生半生效状态。</p>
     *
     * @param cfg 隧道配置
     */
    public NeoLinkAPI(NeoLinkCfg cfg) {
        this.cfg = Objects.requireNonNull(cfg, "cfg");
    }

    /**
     * 建立控制连接、完成握手并启动服务端指令监听。
     *
     * <p>该方法会阻塞到握手成功或失败。TCP/UDP、PPv2、语言和 debug 等协议相关
     * 配置必须在调用本方法之前写入 {@link NeoLinkCfg}，因为它们会参与握手或转发
     * 线程初始化。</p>
     *
     * @throws IOException 控制连接、传输连接或本地下游连接发生 I/O 错误
     * @throws UnsupportedVersionException 服务端拒绝当前 API 版本
     * @throws NoSuchKeyException 服务端拒绝访问密钥
     * @throws NoMoreNetworkFlowException 服务端通知剩余流量耗尽
     */
    public void start()
            throws IOException, UnsupportedVersionException, NoSuchKeyException, NoMoreNetworkFlowException {
        synchronized (lifecycleLock) {
            if (isActive()) {
                debug("start() ignored because this NeoLinkAPI instance is already active.");
                return;
            }

            long generation = lifecycleGeneration.incrementAndGet();
            runtimeCfg = cfg.copy();
            debug("Starting NeoLinkAPI tunnel. " + describeRuntimeConfig());
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
                    this::emitError,
                    isDebugEnabled()
            );
            closeRequested.set(false);
            running.set(true);
            supervisorFuture = workerExecutor.submit(() -> runCore(generation));
        }

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

    /**
     * 查询隧道是否处于运行状态。
     *
     * @return {@code true} 表示控制连接仍处于活动状态
     */
    public boolean isActive() {
        return running.get();
    }

    /**
     * 返回服务端下发的公网端口。
     *
     * @return 尚未收到端口时返回 {@code 0}
     */
    public int getRemotePort() {
        return remotePort;
    }

    /**
     * 设置转发连接建立回调。
     *
     * @param onConnect 参数依次为远端访问者地址和本地下游地址
     * @return 当前隧道对象，便于链式调用
     */
    public NeoLinkAPI setOnConnect(BiConsumer<InetSocketAddress, InetSocketAddress> onConnect) {
        this.onConnect = Objects.requireNonNull(onConnect, "onConnect");
        return this;
    }

    /**
     * 设置不需要地址参数的转发连接建立回调。
     *
     * @param onConnect 转发连接建立时执行的回调
     * @return 当前隧道对象，便于链式调用
     */
    public NeoLinkAPI setOnConnect(Runnable onConnect) {
        Objects.requireNonNull(onConnect, "onConnect");
        return setOnConnect((source, target) -> onConnect.run());
    }

    /**
     * 设置转发连接断开回调。
     *
     * @param onDisconnect 参数依次为远端访问者地址和本地下游地址
     * @return 当前隧道对象，便于链式调用
     */
    public NeoLinkAPI setOnDisconnect(BiConsumer<InetSocketAddress, InetSocketAddress> onDisconnect) {
        this.onDisconnect = Objects.requireNonNull(onDisconnect, "onDisconnect");
        return this;
    }

    /**
     * 设置不需要地址参数的转发连接断开回调。
     *
     * @param onDisconnect 转发连接断开时执行的回调
     * @return 当前隧道对象，便于链式调用
     */
    public NeoLinkAPI setOnDisconnect(Runnable onDisconnect) {
        Objects.requireNonNull(onDisconnect, "onDisconnect");
        return setOnDisconnect((source, target) -> onDisconnect.run());
    }

    /**
     * 设置连接 NeoProxyServer 传输端口失败时的回调。
     *
     * @param onConnectNeoFailure 远端传输端口连接失败时执行
     * @return 当前隧道对象，便于链式调用
     */
    public NeoLinkAPI setOnConnectNeoFailure(Runnable onConnectNeoFailure) {
        this.onConnectNeoFailure = Objects.requireNonNull(onConnectNeoFailure, "onConnectNeoFailure");
        return this;
    }

    /**
     * 设置连接本地下游服务失败时的回调。
     *
     * @param onConnectLocalFailure 本地下游连接失败时执行
     * @return 当前隧道对象，便于链式调用
     */
    public NeoLinkAPI setOnConnectLocalFailure(Runnable onConnectLocalFailure) {
        this.onConnectLocalFailure = Objects.requireNonNull(onConnectLocalFailure, "onConnectLocalFailure");
        return this;
    }

    /**
     * 返回当前 API 包版本。
     *
     * @return 从 {@code api.properties} 解析出的版本号
     */
    public static String version() {
        return VersionInfo.VERSION;
    }

    private void runCore(long generation) {
        try {
            connectToNeoServer();
            exchangeClientInfoWithServer();
            CheckAliveThread activeCheckAliveThread = checkAliveThread;
            if (activeCheckAliveThread != null) {
                activeCheckAliveThread.startThread();
            }
            completeStartup(generation, null);
            listenForServerCommands();
        } catch (UnsupportedVersionException | NoSuchKeyException | NoMoreNetworkFlowException e) {
            completeStartup(generation, e);
            closeRequested.set(true);
            emitError(e.getMessage(), e);
        } catch (IOException e) {
            completeStartup(generation, e);
            if (running.get() && !closeRequested.get()) {
                emitError("NeoLinkAPI 隧道异常停止。", e);
                debug(e);
            }
        } catch (Exception e) {
            IOException exception = toIOException("NeoLinkAPI 隧道异常停止。", e);
            completeStartup(generation, exception);
            if (running.get() && !closeRequested.get()) {
                emitError(exception.getMessage(), exception);
                debug(exception);
            }
        } finally {
            cleanupLifecycle(generation);
        }
    }

    private void connectToNeoServer() throws IOException {
        if (proxyOperator.hasProxy(ProxyOperator.TO_NEO)) {
            debug("Connecting to NeoProxyServer hook through per-instance proxy. target="
                    + runtimeCfg.getRemoteDomainName() + ":" + runtimeCfg.getHookPort());
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
            debug("Connecting to NeoProxyServer hook directly. target="
                    + runtimeCfg.getRemoteDomainName() + ":" + runtimeCfg.getHookPort()
                    + ", timeoutMs=" + CONNECT_TIMEOUT_MILLIS);
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
        String clientInfo = formatClientInfoString();
        debug("Sending client handshake. value=" + maskClientInfo(clientInfo));
        hookSocket.sendStr(clientInfo);
        String serverResponse = hookSocket.receiveStr();
        if (serverResponse == null) {
            throw new IOException("NeoProxyServer 在握手阶段关闭了连接。");
        }
        debug("Received server handshake response. value=" + serverResponse);

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
                .append(runtimeCfg.getLanguage())
                .append(';')
                .append(VersionInfo.VERSION)
                .append(';')
                .append(runtimeCfg.getKey())
                .append(';');
        if (runtimeCfg.isTCPEnabled()) {
            info.append('T');
        }
        if (runtimeCfg.isUDPEnabled()) {
            info.append('U');
        }
        return info.toString();
    }

    private void listenForServerCommands() throws IOException, NoMoreNetworkFlowException {
        String message;
        while (running.get() && (message = hookSocket.receiveStr()) != null) {
            lastReceivedTime = System.currentTimeMillis();
            debug("Received server message. value=" + message);
            if (message.startsWith(":>")) {
                handleServerCommand(message.substring(2));
            } else {
                debug("Ignored non-command server message.");
            }
        }
        throw new IOException("NeoProxyServer 连接已关闭。");
    }

    private void handleServerCommand(String command) throws NoMoreNetworkFlowException {
        String[] parts = command.split(";");
        switch (parts[0]) {
            case "sendSocketTCP" -> {
                if (runtimeCfg.isTCPEnabled() && parts.length >= 3) {
                    debug("Scheduling TCP tunnel. socketId=" + parts[1] + ", remoteAddress=" + parts[2]);
                    submitWorker(() -> createNewTCPConnection(parts[1], parts[2]));
                } else {
                    debug("Ignored TCP tunnel command because TCP is disabled or command is malformed.");
                }
            }
            case "sendSocketUDP" -> {
                if (runtimeCfg.isUDPEnabled() && parts.length >= 3) {
                    debug("Scheduling UDP tunnel. socketId=" + parts[1] + ", remoteAddress=" + parts[2]);
                    submitWorker(() -> createNewUDPConnection(parts[1], parts[2]));
                } else {
                    debug("Ignored UDP tunnel command because UDP is disabled or command is malformed.");
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
            debug("Remote public port updated. remotePort=" + remotePort);
        } catch (NumberFormatException e) {
            debug("Ignored unknown server command. value=" + value);
        }
    }

    private void createNewTCPConnection(String socketId, String remoteAddress) {
        Socket localServerSocket = null;
        SecureSocket neoTransferSocket = null;
        try {
            debug("Creating TCP tunnel. socketId=" + socketId + ", remoteAddress=" + remoteAddress);
            localServerSocket = openLocalSocket();
            neoTransferSocket = openTransferSocket();
            registerConnection(localServerSocket);
            registerConnection(neoTransferSocket);
            neoTransferSocket.sendStr("TCP" + ";" + socketId);

            emitConnectionEvent(onConnect, remoteAddress);

            TCPTransformer serverToLocalTask = new TCPTransformer(
                    neoTransferSocket,
                    localServerSocket,
                    runtimeCfg.isPPV2Enabled(),
                    isDebugEnabled()
            );
            TCPTransformer localToServerTask = new TCPTransformer(
                    localServerSocket,
                    neoTransferSocket,
                    false,
                    isDebugEnabled()
            );
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
            debug("Creating UDP tunnel. socketId=" + socketId + ", remoteAddress=" + remoteAddress);
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
                    runtimeCfg.getLocalPort(),
                    isDebugEnabled()
            );
            UDPTransformer neoToLocalTask = new UDPTransformer(
                    neoTransferSocket,
                    datagramSocket,
                    runtimeCfg.getLocalDomainName(),
                    runtimeCfg.getLocalPort(),
                    isDebugEnabled()
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
            debug("Connecting to local service through per-instance proxy. target="
                    + runtimeCfg.getLocalDomainName() + ":" + runtimeCfg.getLocalPort()
                    + ", timeoutMs=" + CONNECT_TIMEOUT_MILLIS);
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
                debug("Trying local resolved address. address=" + address.getHostAddress()
                        + ", port=" + port + ", timeoutMs=" + CONNECT_TIMEOUT_MILLIS);
                socket.connect(new InetSocketAddress(address, port), CONNECT_TIMEOUT_MILLIS);
                return socket;
            } catch (IOException e) {
                lastException = e;
                debug("Local resolved address failed. address=" + address.getHostAddress()
                        + ", port=" + port + ", error=" + e.getMessage());
                InternetOperator.close(socket);
                unregisterConnection(socket);
            }
        }
        throw lastException != null ? lastException : new IOException("解析本地域名失败：" + host);
    }

    private SecureSocket openTransferSocket() throws IOException {
        if (proxyOperator.hasProxy(ProxyOperator.TO_NEO)) {
            debug("Connecting to NeoProxyServer transfer port through per-instance proxy. target="
                    + runtimeCfg.getRemoteDomainName() + ":" + runtimeCfg.getHostConnectPort()
                    + ", timeoutMs=" + CONNECT_TIMEOUT_MILLIS);
            return proxyOperator.getHandledSecureSocket(
                    ProxyOperator.TO_NEO,
                    runtimeCfg.getHostConnectPort(),
                    CONNECT_TIMEOUT_MILLIS
            );
        }

        Socket socket = new Socket();
        registerConnection(socket);
        try {
            debug("Connecting to NeoProxyServer transfer port directly. target="
                    + runtimeCfg.getRemoteDomainName() + ":" + runtimeCfg.getHostConnectPort()
                    + ", timeoutMs=" + CONNECT_TIMEOUT_MILLIS);
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
            debug(e);
        }
        try {
            second.get();
        } catch (Exception e) {
            debug(e);
        } finally {
            InternetOperator.close(trackedConnections);
            unregisterConnection(trackedConnections);
            emitConnectionEvent(onDisconnect, remoteAddress);
        }
    }

    private void submitWorker(Runnable task) {
        ExecutorService executor = workerExecutor;
        if (executor == null || executor.isShutdown() || !running.get()) {
            debug("Worker task ignored because executor is not available.");
            return;
        }

        try {
            executor.submit(task);
        } catch (RuntimeException e) {
            debug(e);
        }
    }

    private ExecutorService requireWorkerExecutor() throws IOException {
        ExecutorService executor = workerExecutor;
        if (executor == null || executor.isShutdown()) {
            throw new IOException("NeoLinkAPI 隧道已经停止，无法创建新的转发连接。");
        }
        return executor;
    }

    @Override
    public void close() {
        synchronized (lifecycleLock) {
            debug("close() requested.");
            closeRequested.set(true);
            running.set(false);
            CheckAliveThread activeCheckAliveThread = checkAliveThread;
            if (activeCheckAliveThread != null) {
                activeCheckAliveThread.stopThread();
            }
            closeActiveConnection();
            shutdownWorkerExecutor();
            completeStartup(new IOException("NeoLinkAPI 启动已取消。"));
        }
    }

    private void awaitStartup()
            throws IOException, UnsupportedVersionException, NoSuchKeyException, NoMoreNetworkFlowException {
        try {
            startupFuture.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("等待 NeoLinkAPI 启动时线程被中断。", e);
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
            throw new IOException("NeoLinkAPI 启动失败。", cause);
        }
    }

    private void completeStartup(Throwable failure) {
        completeStartup(lifecycleGeneration.get(), failure);
    }

    private void completeStartup(long generation, Throwable failure) {
        if (!isCurrentGeneration(generation)) {
            return;
        }
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

    private void cleanupLifecycle(long generation) {
        synchronized (lifecycleLock) {
            if (!isCurrentGeneration(generation)) {
                debug("Ignoring cleanup from a stale lifecycle generation. generation=" + generation);
                return;
            }

            CheckAliveThread activeCheckAliveThread = checkAliveThread;
            if (activeCheckAliveThread != null) {
                activeCheckAliveThread.stopThread();
            }
            closeActiveConnection();
            running.set(false);
            shutdownWorkerExecutor();
        }
    }

    private boolean isCurrentGeneration(long generation) {
        return lifecycleGeneration.get() == generation;
    }

    private void closeActiveConnection() {
        debug("Closing active control and transfer connections.");
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
            debug(e);
        }
    }

    private void runCallback(Runnable callback) {
        try {
            callback.run();
        } catch (RuntimeException e) {
            debug(e);
        }
    }

    private void emitError(String message, Throwable cause) {
        debug("ERROR: " + message);
        if (cause instanceof Exception exception) {
            debug(exception);
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

    private boolean isDebugEnabled() {
        NeoLinkCfg activeCfg = runtimeCfg;
        return Debugger.isEnabled() || (activeCfg != null ? activeCfg.isDebugMsg() : cfg.isDebugMsg());
    }

    private void debug(String message) {
        Debugger.debugOperation(isDebugEnabled(), message);
    }

    private void debug(Exception e) {
        Debugger.debugOperation(isDebugEnabled(), e);
    }

    private String describeRuntimeConfig() {
        NeoLinkCfg activeCfg = runtimeCfg;
        return "remote=" + activeCfg.getRemoteDomainName()
                + ", hookPort=" + activeCfg.getHookPort()
                + ", connectPort=" + activeCfg.getHostConnectPort()
                + ", local=" + activeCfg.getLocalDomainName() + ":" + activeCfg.getLocalPort()
                + ", language=" + activeCfg.getLanguage()
                + ", tcpEnabled=" + activeCfg.isTCPEnabled()
                + ", udpEnabled=" + activeCfg.isUDPEnabled()
                + ", ppv2Enabled=" + activeCfg.isPPV2Enabled()
                + ", heartbeatDelayMs=" + activeCfg.getHeartBeatPacketDelay()
                + ", proxyToNeo=" + (activeCfg.getProxyIPToNeoServer().isBlank() ? "direct" : "configured")
                + ", proxyToLocal=" + (activeCfg.getProxyIPToLocalServer().isBlank() ? "direct" : "configured")
                + ", connectTimeoutMs=" + CONNECT_TIMEOUT_MILLIS;
    }

    private static String maskClientInfo(String clientInfo) {
        String[] parts = clientInfo.split(";", -1);
        if (parts.length < 4) {
            return clientInfo;
        }
        parts[2] = maskSecret(parts[2]);
        return String.join(";", parts);
    }

    private static String maskSecret(String secret) {
        if (secret == null || secret.isBlank()) {
            return "";
        }
        if (secret.length() <= 4) {
            return "****";
        }
        return secret.substring(0, 2) + "****" + secret.substring(secret.length() - 2);
    }
}
